package com.example.salesagent.analytics;

import java.util.List;

/** Read-only result types shared by the dashboard API and the agent tools. */
public final class Analytics {

    private Analytics() {
    }

    public record PeriodSummary(int year, int month, String label, double revenue, long units,
                                long orders, double avgOrderValue) {
    }

    public record MonthComparison(PeriodSummary current, PeriodSummary previous,
                                  double revenueChange, double revenueChangePct) {
    }

    public record ProductStat(String productName, String category, double revenue, long units, long orders) {
    }

    /** Month-over-month movement of one product. */
    public record ProductChange(String productName, String category, double revenue, double previousRevenue,
                                double revenueChange, double changePct, long units, long previousUnits) {
    }

    public record CategoryStat(String category, double revenue, double sharePct, double previousRevenue,
                               double changePct) {
    }

    public record MonthPoint(int year, int month, String label, double revenue, long units) {
    }

    public record ProductTrend(String productName, List<MonthPoint> months, String note) {
    }

    public record Dashboard(PeriodSummary current, PeriodSummary previous, double growthPct,
                            List<MonthPoint> trend, List<CategoryStat> categories,
                            List<ProductChange> topProducts, List<ProductChange> underperformers,
                            List<ProductChange> allProducts) {
    }
}
