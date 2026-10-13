package com.example.creator.analytics;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/works")
public class MetricsController {
    private final CurrentUser currentUser;
    private final MetricsService metrics;

    MetricsController(CurrentUser currentUser, MetricsService metrics) {
        this.currentUser = currentUser;
        this.metrics = metrics;
    }

    @GetMapping
    public List<MetricsService.Work> works(@RequestParam String accountId) {
        return metrics.works(currentUser.id(), accountId);
    }

    @GetMapping("/{workId}/trend")
    public MetricsService.Trend trend(@PathVariable String workId, @RequestParam String source) {
        return metrics.trend(currentUser.id(), workId, source);
    }
}
