package com.example.salesagent.web;

import com.example.salesagent.analytics.Analytics.Dashboard;
import com.example.salesagent.analytics.SalesAnalyticsService;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class DashboardController {

    public record MonthOption(String key, String label) {
    }

    public record MonthList(String defaultKey, List<MonthOption> months) {
    }

    private final SalesAnalyticsService analytics;
    private final String currency;
    private final String chatModel;
    private final String embeddingModel;

    public DashboardController(SalesAnalyticsService analytics,
                               @Value("${app.currency:USD}") String currency,
                               @Value("${spring.ai.ollama.chat.options.model}") String chatModel,
                               @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel) {
        this.analytics = analytics;
        this.currency = currency;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("currency", currency, "chatModel", chatModel, "embeddingModel", embeddingModel);
    }

    /** Months that have data. The default is last calendar month, or the newest month with data. */
    @GetMapping("/months")
    public MonthList months() {
        List<YearMonth> available = analytics.availableMonths();
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        YearMonth def = available.contains(lastMonth) ? lastMonth : available.isEmpty() ? lastMonth : available.get(0);
        return new MonthList(def.toString(), available.stream()
                .map(m -> new MonthOption(m.toString(), SalesAnalyticsService.label(m)))
                .toList());
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam int year, @RequestParam int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid year or month");
        }
        return analytics.dashboard(YearMonth.of(year, month));
    }
}
