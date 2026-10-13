package com.example.creator.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReviewOutputValidator {
    private final ObjectMapper json;
    ReviewOutputValidator(ObjectMapper json) { this.json = json; }

    public OperationReview.Advice validate(String raw, OperationReview.Facts facts) {
        try {
            if (raw == null || raw.length() > 20_000) throw invalid();
            JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            fields(root, Set.of("hypotheses", "experiments"));
            var observations = new HashSet<String>();
            facts.metrics().forEach(metric -> observations.add(metric.citationId()));
            facts.evidence().comments().forEach(comment -> observations.add(comment.citationId()));
            var all = new HashSet<>(observations);
            all.add("account");
            facts.evidence().referenceMaterials().results().forEach(material -> all.add("material:" + material.materialId()));
            var hypotheses = new ArrayList<OperationReview.Hypothesis>();
            for (var item : array(root, "hypotheses", 0, 3)) {
                fields(item, Set.of("possibleExplanation", "evidenceIds"));
                hypotheses.add(new OperationReview.Hypothesis(text(item, "possibleExplanation", 400), citations(item, observations)));
            }
            var experiments = new ArrayList<OperationReview.Experiment>();
            for (var item : array(root, "experiments", 1, 3)) {
                fields(item, Set.of("proposedTopic", "action", "measurement", "evidenceIds"));
                experiments.add(new OperationReview.Experiment(text(item, "proposedTopic", 80), text(item, "action", 300),
                        text(item, "measurement", 250), citations(item, all)));
            }
            return new OperationReview.Advice(List.copyOf(hypotheses), List.copyOf(experiments));
        } catch (JsonProcessingException invalid) { throw invalid(); }
    }
    private static void fields(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw invalid();
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    private static JsonNode array(JsonNode value, String field, int minimum, int maximum) {
        var array = value.path(field);
        if (!array.isArray() || array.size() < minimum || array.size() > maximum) throw invalid();
        return array;
    }
    private static String text(JsonNode value, String field, int maximum) {
        var text = value.path(field);
        if (!text.isTextual() || text.asText().isBlank() || text.asText().strip().length() > maximum) throw invalid();
        return text.asText().strip();
    }
    private static List<String> citations(JsonNode value, Set<String> allowed) {
        var results = new ArrayList<String>();
        for (var item : array(value, "evidenceIds", 1, 3)) {
            if (!item.isTextual() || !allowed.contains(item.asText()) || results.contains(item.asText())) throw invalid();
            results.add(item.asText());
        }
        return List.copyOf(results);
    }
    private static OperationReviewService.ReviewFailure invalid() {
        return new OperationReviewService.ReviewFailure("REVIEW_OUTPUT_INVALID");
    }
}
