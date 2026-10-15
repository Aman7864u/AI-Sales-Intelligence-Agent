package com.example.salesagent.tools;

import com.example.salesagent.analytics.Analytics.CategoryStat;
import com.example.salesagent.analytics.Analytics.MonthComparison;
import com.example.salesagent.analytics.Analytics.PeriodSummary;
import com.example.salesagent.analytics.Analytics.ProductChange;
import com.example.salesagent.analytics.Analytics.ProductTrend;
import com.example.salesagent.analytics.SalesAnalyticsService;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The approved, read-only analytics operations the agent may use. The same methods are exposed
 * to the in-app agent (via ChatClient.tools) and to external MCP clients (see AgentConfig).
 * Nothing here accepts SQL/Mongo queries: the model only supplies validated parameters.
 */
@Component
public class SalesTools {

    private final SalesAnalyticsService analytics;

    public SalesTools(SalesAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @Tool(description = "Get total revenue, units sold and number of orders for one calendar month. "
            + "Use this for any question about how much was sold in a month.")
    public PeriodSummary getMonthlyRevenue(
            @ToolParam(description = "Four-digit year, e.g. 2026") int year,
            @ToolParam(description = "Month number from 1 (January) to 12 (December)") int month) {
        PeriodSummary r = analytics.summary(period(year, month));
        ToolTrace.record("getMonthlyRevenue", args(year, month), money(r.revenue()) + ", " + r.units() + " units");
        return r;
    }

    @Tool(description = "Compare a month's revenue with the previous month and return the absolute and "
            + "percentage growth. Use this for month-over-month questions.")
    public MonthComparison compareWithPreviousMonth(
            @ToolParam(description = "Four-digit year of the month to analyse") int year,
            @ToolParam(description = "Month number from 1 to 12") int month) {
        MonthComparison r = analytics.compare(period(year, month));
        ToolTrace.record("compareWithPreviousMonth", args(year, month),
                money(r.current().revenue()) + " vs " + money(r.previous().revenue()) + " (" + signed(r.revenueChangePct()) + ")");
        return r;
    }

    @Tool(description = "List the best-selling products of a month by revenue, with their change versus the previous month.")
    public List<ProductChange> getTopProducts(
            @ToolParam(description = "Four-digit year") int year,
            @ToolParam(description = "Month number from 1 to 12") int month,
            @ToolParam(description = "How many products to return, 1 to 20") int limit) {
        List<ProductChange> r = analytics.topProducts(period(year, month), clamp(limit));
        ToolTrace.record("getTopProducts", args(year, month) + ", limit=" + clamp(limit), names(r));
        return r;
    }

    @Tool(description = "List the products whose revenue DECLINED versus the previous month, ranked from the "
            + "largest percentage drop. Use this to answer which products performed poorly.")
    public List<ProductChange> getUnderperformingProducts(
            @ToolParam(description = "Four-digit year") int year,
            @ToolParam(description = "Month number from 1 to 12") int month,
            @ToolParam(description = "How many products to return, 1 to 20") int limit) {
        List<ProductChange> r = analytics.underperformers(period(year, month), clamp(limit));
        ToolTrace.record("getUnderperformingProducts", args(year, month) + ", limit=" + clamp(limit),
                r.isEmpty() ? "no declining products" : names(r));
        return r;
    }

    @Tool(description = "Revenue by product category for a month, with share of total and change versus the previous month.")
    public List<CategoryStat> getCategoryBreakdown(
            @ToolParam(description = "Four-digit year") int year,
            @ToolParam(description = "Month number from 1 to 12") int month) {
        List<CategoryStat> r = analytics.categories(period(year, month));
        ToolTrace.record("getCategoryBreakdown", args(year, month), r.size() + " categories");
        return r;
    }

    @Tool(description = "Monthly revenue and units for one product over the last N months, ending at the given month. "
            + "Use this to see whether a product's decline is sudden or gradual.")
    public ProductTrend getProductRevenueTrend(
            @ToolParam(description = "Exact product name, e.g. 'Nimbus Smart Speaker'") String productName,
            @ToolParam(description = "Four-digit year of the last month to include") int endYear,
            @ToolParam(description = "Month number (1-12) of the last month to include") int endMonth,
            @ToolParam(description = "Number of months to return, 2 to 12") int months) {
        if (productName == null || productName.isBlank() || productName.length() > 80) {
            throw new IllegalArgumentException("productName must be a non-empty product name (max 80 characters)");
        }
        int n = Math.max(2, Math.min(12, months));
        ProductTrend r = analytics.productTrend(productName, period(endYear, endMonth), n);
        ToolTrace.record("getProductRevenueTrend", "product=" + r.productName() + ", " + args(endYear, endMonth) + ", months=" + n,
                r.note() != null ? r.note() : n + " monthly data points");
        return r;
    }

    @Tool(description = "List the exact names of all products that have sales. Call this first if you are unsure of a product name.")
    public List<String> listProducts() {
        List<String> r = analytics.productNames();
        ToolTrace.record("listProducts", "", r.size() + " products");
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static YearMonth period(int year, int month) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("year must be between 2000 and 2100, got " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12, got " + month);
        }
        return YearMonth.of(year, month);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(20, limit));
    }

    private static String args(int year, int month) {
        return "year=" + year + ", month=" + month;
    }

    private static String money(double v) {
        return String.format(Locale.US, "%,.0f", v);
    }

    private static String signed(double pct) {
        return String.format(Locale.US, "%+.1f%%", pct);
    }

    private static String names(List<ProductChange> list) {
        return list.stream()
                .map(c -> c.productName() + " " + signed(c.changePct()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("none");
    }
}
