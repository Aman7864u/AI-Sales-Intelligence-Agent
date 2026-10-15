package com.example.salesagent.analytics;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

import com.example.salesagent.analytics.Analytics.CategoryStat;
import com.example.salesagent.analytics.Analytics.Dashboard;
import com.example.salesagent.analytics.Analytics.MonthComparison;
import com.example.salesagent.analytics.Analytics.MonthPoint;
import com.example.salesagent.analytics.Analytics.PeriodSummary;
import com.example.salesagent.analytics.Analytics.ProductChange;
import com.example.salesagent.analytics.Analytics.ProductStat;
import com.example.salesagent.analytics.Analytics.ProductTrend;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The only place that talks to the sales collection. Every method is a fixed, parameterised,
 * read-only aggregation: the LLM can choose parameters (year, month, limit) but can never
 * write its own queries.
 */
@Service
public class SalesAnalyticsService {

    private static final String SALES = "sales";

    private final MongoTemplate mongo;

    public SalesAnalyticsService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---------------------------------------------------------------- period helpers

    public static int key(YearMonth p) {
        return p.getYear() * 100 + p.getMonthValue();
    }

    public static String label(YearMonth p) {
        return p.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + p.getYear();
    }

    private static String shortLabel(YearMonth p) {
        return p.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + String.valueOf(p.getYear()).substring(2);
    }

    // ---------------------------------------------------------------- totals

    public PeriodSummary summary(YearMonth p) {
        Aggregation agg = newAggregation(
                match(Criteria.where("yearMonth").is(key(p))),
                group().sum("revenue").as("revenue").sum("quantity").as("units").count().as("orders"));
        Document d = mongo.aggregate(agg, SALES, Document.class).getUniqueMappedResult();
        double revenue = d == null ? 0 : num(d, "revenue");
        long units = d == null ? 0 : (long) num(d, "units");
        long orders = d == null ? 0 : (long) num(d, "orders");
        return new PeriodSummary(p.getYear(), p.getMonthValue(), label(p), r2(revenue), units, orders,
                orders == 0 ? 0 : r2(revenue / orders));
    }

    public MonthComparison compare(YearMonth p) {
        PeriodSummary cur = summary(p);
        PeriodSummary prev = summary(p.minusMonths(1));
        return new MonthComparison(cur, prev, r2(cur.revenue() - prev.revenue()), pct(cur.revenue(), prev.revenue()));
    }

    // ---------------------------------------------------------------- products

    public List<ProductStat> productStats(YearMonth p) {
        Aggregation agg = newAggregation(
                match(Criteria.where("yearMonth").is(key(p))),
                group("productName").first("category").as("category")
                        .sum("revenue").as("revenue").sum("quantity").as("units").count().as("orders"));
        return mongo.aggregate(agg, SALES, Document.class).getMappedResults().stream()
                .map(d -> new ProductStat(d.getString("_id"), d.getString("category"), r2(num(d, "revenue")),
                        (long) num(d, "units"), (long) num(d, "orders")))
                .toList();
    }

    /** Every product's revenue vs the previous month, sorted from biggest percentage drop to biggest gain. */
    public List<ProductChange> changes(YearMonth p) {
        Map<String, ProductStat> cur = productStats(p).stream()
                .collect(Collectors.toMap(ProductStat::productName, s -> s));
        Map<String, ProductStat> prev = productStats(p.minusMonths(1)).stream()
                .collect(Collectors.toMap(ProductStat::productName, s -> s));
        Set<String> names = new TreeSet<>(cur.keySet());
        names.addAll(prev.keySet());

        List<ProductChange> out = new ArrayList<>();
        for (String name : names) {
            ProductStat c = cur.get(name);
            ProductStat v = prev.get(name);
            double rev = c == null ? 0 : c.revenue();
            double prevRev = v == null ? 0 : v.revenue();
            long units = c == null ? 0 : c.units();
            long prevUnits = v == null ? 0 : v.units();
            String category = c != null ? c.category() : v.category();
            out.add(new ProductChange(name, category, rev, prevRev, r2(rev - prevRev), pct(rev, prevRev), units, prevUnits));
        }
        out.sort(Comparator.comparingDouble(ProductChange::changePct));
        return out;
    }

    public List<ProductChange> topProducts(YearMonth p, int limit) {
        return changes(p).stream()
                .sorted(Comparator.comparingDouble(ProductChange::revenue).reversed())
                .limit(limit)
                .toList();
    }

    public List<ProductChange> underperformers(YearMonth p, int limit) {
        return changes(p).stream().filter(c -> c.revenueChange() < 0).limit(limit).toList();
    }

    public List<String> productNames() {
        return mongo.findDistinct(new Query(), "productName", SALES, String.class).stream().sorted().toList();
    }

    // ---------------------------------------------------------------- categories

    private Map<String, Double> revenueByCategory(YearMonth p) {
        Aggregation agg = newAggregation(
                match(Criteria.where("yearMonth").is(key(p))),
                group("category").sum("revenue").as("revenue"));
        Map<String, Double> out = new HashMap<>();
        for (Document d : mongo.aggregate(agg, SALES, Document.class).getMappedResults()) {
            out.put(d.getString("_id"), num(d, "revenue"));
        }
        return out;
    }

    public List<CategoryStat> categories(YearMonth p) {
        Map<String, Double> cur = revenueByCategory(p);
        Map<String, Double> prev = revenueByCategory(p.minusMonths(1));
        double total = cur.values().stream().mapToDouble(Double::doubleValue).sum();
        return cur.entrySet().stream()
                .map(e -> {
                    double before = prev.getOrDefault(e.getKey(), 0.0);
                    return new CategoryStat(e.getKey(), r2(e.getValue()),
                            total == 0 ? 0 : r1(e.getValue() / total * 100), r2(before), pct(e.getValue(), before));
                })
                .sorted(Comparator.comparingDouble(CategoryStat::revenue).reversed())
                .toList();
    }

    // ---------------------------------------------------------------- trends

    public List<MonthPoint> trend(YearMonth to, int months) {
        return series(null, to, months);
    }

    public ProductTrend productTrend(String productName, YearMonth to, int months) {
        List<MonthPoint> points = series(productName.trim(), to, months);
        boolean empty = points.stream().allMatch(m -> m.revenue() == 0);
        String note = empty ? "No sales found for '" + productName + "'. Call listProducts to get the exact names." : null;
        return new ProductTrend(productName.trim(), points, note);
    }

    private List<MonthPoint> series(String productName, YearMonth to, int months) {
        YearMonth from = to.minusMonths(months - 1L);
        Criteria criteria = Criteria.where("yearMonth").gte(key(from)).lte(key(to));
        if (productName != null) {
            criteria = criteria.and("productName").regex("^" + Pattern.quote(productName) + "$", "i");
        }
        Aggregation agg = newAggregation(
                match(criteria),
                group("yearMonth").sum("revenue").as("revenue").sum("quantity").as("units"));
        Map<Integer, Document> byKey = new HashMap<>();
        for (Document d : mongo.aggregate(agg, SALES, Document.class).getMappedResults()) {
            byKey.put(((Number) d.get("_id")).intValue(), d);
        }
        List<MonthPoint> out = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            Document d = byKey.get(key(m));
            out.add(new MonthPoint(m.getYear(), m.getMonthValue(), shortLabel(m),
                    d == null ? 0 : r2(num(d, "revenue")), d == null ? 0 : (long) num(d, "units")));
        }
        return out;
    }

    // ---------------------------------------------------------------- dashboard + metadata

    public Dashboard dashboard(YearMonth p) {
        PeriodSummary cur = summary(p);
        PeriodSummary prev = summary(p.minusMonths(1));
        List<ProductChange> all = changes(p);
        List<ProductChange> top = all.stream()
                .sorted(Comparator.comparingDouble(ProductChange::revenue).reversed())
                .limit(5).toList();
        List<ProductChange> under = all.stream().filter(c -> c.revenueChange() < 0).limit(5).toList();
        return new Dashboard(cur, prev, pct(cur.revenue(), prev.revenue()), trend(p, 12),
                categories(p), top, under, all);
    }

    /** Months that actually have data, newest first. */
    public List<YearMonth> availableMonths() {
        return mongo.findDistinct(new Query(), "yearMonth", SALES, Integer.class).stream()
                .sorted(Comparator.reverseOrder())
                .map(k -> YearMonth.of(k / 100, k % 100))
                .toList();
    }

    // ---------------------------------------------------------------- math

    private static double num(Document d, String field) {
        return d.get(field) instanceof Number n ? n.doubleValue() : 0;
    }

    private static double pct(double current, double previous) {
        if (previous == 0) {
            return current > 0 ? 100 : 0;
        }
        return r1((current - previous) / previous * 100);
    }

    private static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
