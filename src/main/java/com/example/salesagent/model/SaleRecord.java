package com.example.salesagent.model;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One order line. year / month / yearMonth are stored alongside orderDate so that monthly
 * analytics never depend on time zones (yearMonth is e.g. 202609 for September 2026).
 */
@Document("sales")
@CompoundIndex(name = "yearMonth_product", def = "{'yearMonth': 1, 'productName': 1}")
public record SaleRecord(
        @Id String id,
        String orderId,
        LocalDate orderDate,
        int year,
        int month,
        int yearMonth,
        String productName,
        String category,
        String region,
        int quantity,
        double unitPrice,
        double revenue) {
}
