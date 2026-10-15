package com.example.salesagent.seed;

import com.example.salesagent.model.Product;
import com.example.salesagent.model.SaleRecord;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Generates 12 months of demo sales on first start (only if the sales collection is empty).
 * The data is deterministic and relative to today's date, so "last month" always exists, and a few
 * products are deliberately weak last month so the agent has something to investigate. The product
 * names match the documents in src/main/resources/knowledge.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private record Spec(String name, String category, double price, double ordersPerDay) {
    }

    private static final List<Spec> PRODUCTS = List.of(
            new Spec("Aurora Wireless Earbuds", "Electronics", 89, 3.2),
            new Spec("Nimbus Smart Speaker", "Electronics", 129, 2.4),
            new Spec("Pulse Fitness Band", "Electronics", 59, 2.8),
            new Spec("Vertex Monitor 27in", "Electronics", 289, 1.3),
            new Spec("ChefMate Air Fryer", "Home & Kitchen", 119, 2.6),
            new Spec("BrewPro Coffee Maker", "Home & Kitchen", 99, 2.0),
            new Spec("PureAir Purifier", "Home & Kitchen", 179, 1.4),
            new Spec("CozyHeat Space Heater", "Home & Kitchen", 69, 2.2),
            new Spec("FlexFit Yoga Mat", "Fitness", 35, 3.0),
            new Spec("IronCore Dumbbells", "Fitness", 149, 1.2),
            new Spec("TrailRunner Shoes", "Fitness", 110, 2.1),
            new Spec("HydroMax Bottle", "Fitness", 25, 3.4),
            new Spec("ErgoLift Standing Desk", "Office", 449, 0.9),
            new Spec("Lumina Desk Lamp", "Office", 45, 2.3),
            new Spec("NoteMaster Notebook Set", "Office", 18, 3.1),
            new Spec("ClearView Whiteboard", "Office", 79, 1.5),
            new Spec("Urban Backpack", "Accessories", 65, 2.5),
            new Spec("Shield Phone Case", "Accessories", 22, 3.6),
            new Spec("Volt Power Bank", "Accessories", 39, 2.7),
            new Spec("Sonic Charging Cable", "Accessories", 15, 3.3));

    /** {two months ago, last month} volume multipliers vs. normal. Anything not listed stays at 1.0. */
    private static final Map<String, double[]> SHAPE = Map.ofEntries(
            Map.entry("Nimbus Smart Speaker", new double[]{1.00, 0.58}),
            Map.entry("CozyHeat Space Heater", new double[]{1.00, 0.62}),
            Map.entry("ClearView Whiteboard", new double[]{0.88, 0.63}),
            Map.entry("Shield Phone Case", new double[]{0.96, 0.71}),
            Map.entry("NoteMaster Notebook Set", new double[]{0.97, 0.82}),
            Map.entry("TrailRunner Shoes", new double[]{1.00, 0.84}),
            Map.entry("Aurora Wireless Earbuds", new double[]{1.06, 1.24}),
            Map.entry("Volt Power Bank", new double[]{1.00, 1.19}),
            Map.entry("ErgoLift Standing Desk", new double[]{1.00, 1.13}));

    private static final String[] REGIONS = {"North", "South", "East", "West"};

    private final MongoTemplate mongo;

    public DataSeeder(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mongo.collectionExists(SaleRecord.class) && mongo.estimatedCount("sales") > 0) {
            log.info("Sales data already present, skipping seed.");
            return;
        }
        log.info("Seeding demo sales data...");

        mongo.dropCollection(Product.class);
        mongo.insert(PRODUCTS.stream().map(p -> new Product(null, p.name(), p.category(), p.price())).toList(), Product.class);

        Random rnd = new Random(42);
        LocalDate today = LocalDate.now();
        LocalDate start = YearMonth.from(today).minusMonths(11).atDay(1);
        List<SaleRecord> batch = new ArrayList<>();
        long orderNo = 100000;
        long total = 0;

        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            int offset = (YearMonth.from(today).getYear() * 12 + YearMonth.from(today).getMonthValue())
                    - (day.getYear() * 12 + day.getMonthValue());
            double seasonal = 1 + 0.07 * Math.sin(2 * Math.PI * day.getMonthValue() / 12.0);
            double weekend = day.getDayOfWeek().getValue() >= 6 ? 1.15 : 1.0;

            for (Spec p : PRODUCTS) {
                double lambda = p.ordersPerDay() * seasonal * weekend * multiplier(p.name(), offset)
                        * (0.85 + 0.3 * rnd.nextDouble());
                int orders = (int) lambda + (rnd.nextDouble() < lambda - (int) lambda ? 1 : 0);
                for (int i = 0; i < orders; i++) {
                    int qty = 1 + (rnd.nextDouble() < 0.25 ? 1 : 0) + (rnd.nextDouble() < 0.08 ? 1 : 0);
                    double price = rnd.nextDouble() < 0.2 ? Math.round(p.price() * 0.9 * 100) / 100.0 : p.price();
                    batch.add(new SaleRecord(null, "ORD-" + (orderNo++), day, day.getYear(), day.getMonthValue(),
                            day.getYear() * 100 + day.getMonthValue(), p.name(), p.category(),
                            REGIONS[rnd.nextInt(REGIONS.length)], qty, price, Math.round(price * qty * 100) / 100.0));
                }
            }
            if (batch.size() >= 2000) {
                mongo.insert(batch, SaleRecord.class);
                total += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mongo.insert(batch, SaleRecord.class);
            total += batch.size();
        }
        log.info("Seeded {} sales records for {} products.", total, PRODUCTS.size());
    }

    /** offset 0 = current month (continues last month's level), 1 = last month, 2 = two months ago. */
    private static double multiplier(String product, int offset) {
        double[] shape = SHAPE.get(product);
        if (shape == null || offset >= 3) {
            return 1.0;
        }
        return offset == 2 ? shape[0] : shape[1];
    }
}
