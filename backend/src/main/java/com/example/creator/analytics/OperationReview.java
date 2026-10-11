package com.example.creator.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class OperationReview {
    private OperationReview() { }
    public record Request(String accountId, String topic, Instant from, Instant to, String requestId) implements java.io.Serializable { }
    public record Account(String name, String audience, String positioning, List<String> columns, int weeklyTarget) implements java.io.Serializable { }
    public record MetricEvidence(String citationId, String workId, String videoId, String source,
                                 int observations, boolean truncated, Instant firstCapturedAt,
                                 MetricsService.Point previous, MetricsService.Point latest) implements java.io.Serializable { }
    public record Publication(String workId, String planId, String itemId, String topicId, String scriptId,
                              String publicationUrl, Instant publishedAt, LocalDate scheduledDate) implements java.io.Serializable { }
    public record Facts(Account account, Instant from, Instant to, Instant dataUpdatedAt,
                        List<MetricEvidence> metrics, ReviewService.Evidence evidence,
                        List<Publication> publications, List<String> limitations) implements java.io.Serializable { }
    public record Hypothesis(String possibleExplanation, List<String> evidenceIds) implements java.io.Serializable { }
    public record Experiment(String proposedTopic, String action, String measurement, List<String> evidenceIds) implements java.io.Serializable { }
    public record Advice(List<Hypothesis> hypotheses, List<Experiment> experiments) implements java.io.Serializable { }
    public record Report(String id, String accountId, String status, Request request, Facts facts,
                         Advice advice, int attempts, String errorCode, Instant createdAt) implements java.io.Serializable { }
}
