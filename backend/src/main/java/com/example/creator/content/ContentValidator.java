package com.example.creator.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates model output against the exact source IDs retrieved for this run. */
@Component
public class ContentValidator {
    private final ObjectMapper json;

    public ContentValidator(ObjectMapper json) { this.json = json; }

    public Topic topic(String raw, Set<String> allowedSources) {
        var node = parse(raw);
        return validateTopic(new Topic(required(node, "column", 80), required(node, "title", 200),
                required(node, "audience", 160), required(node, "angle", 500), required(node, "hook", 500),
                outline(node), sources(node), required(node, "rationale", 1000)), allowedSources);
    }

    public Script script(String raw, Set<String> allowedSources) {
        var node = parse(raw);
        return validateScript(new Script(required(node, "spokenText", 20_000),
                required(node, "shootingNotes", 4000), sources(node)), allowedSources);
    }

    public Topic validateTopic(Topic topic, Set<String> allowedSources) {
        if (topic == null) throw new ContentInvalid("CONTENT_INVALID");
        return new Topic(clean(topic.column(), 80), clean(topic.title(), 200), clean(topic.audience(), 160),
                clean(topic.angle(), 500), clean(topic.hook(), 500), clean(topic.outline(), 4000),
                validateSources(topic.sourceIds(), allowedSources), clean(topic.rationale(), 1000));
    }

    public Script validateScript(Script script, Set<String> allowedSources) {
        if (script == null) throw new ContentInvalid("CONTENT_INVALID");
        return new Script(clean(script.spokenText(), 20_000), clean(script.shootingNotes(), 4000),
                validateSources(script.sourceIds(), allowedSources));
    }

    private JsonNode parse(String raw) {
        try {
            var node = json.readTree(raw == null ? "" : raw);
            if (node == null || !node.isObject()) throw new ContentInvalid("CONTENT_INVALID");
            return node;
        } catch (JsonProcessingException invalid) {
            throw new ContentInvalid("CONTENT_INVALID");
        }
    }

    private String required(JsonNode node, String field, int maxLength) {
        var value = node.path(field);
        if (!value.isTextual()) throw new ContentInvalid("CONTENT_INVALID");
        return clean(value.asText(), maxLength);
    }

    private String outline(JsonNode node) {
        var value = node.path("outline");
        if (value.isTextual()) return clean(value.asText(), 4000);
        if (!value.isArray() || value.isEmpty() || value.size() > 10)
            throw new ContentInvalid("CONTENT_INVALID");
        var lines = new ArrayList<String>();
        value.forEach(line -> {
            if (!line.isTextual()) throw new ContentInvalid("CONTENT_INVALID");
            lines.add(clean(line.asText(), 500));
        });
        return clean(String.join("\n", lines), 4000);
    }

    private String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) throw new ContentInvalid("CONTENT_INVALID");
        String clean = value.strip();
        if (clean.length() > maxLength) throw new ContentInvalid("CONTENT_INVALID");
        return clean;
    }

    private List<String> sources(JsonNode node) {
        var values = node.path("sourceIds");
        if (!values.isArray()) throw new ContentInvalid("CONTENT_INVALID");
        var sources = new ArrayList<String>();
        values.forEach(value -> {
            if (!value.isTextual()) throw new ContentInvalid("CONTENT_INVALID");
            sources.add(value.asText());
        });
        return sources;
    }

    private List<String> validateSources(List<String> sourceIds, Set<String> allowedSources) {
        if (sourceIds == null || allowedSources == null || sourceIds.size() > 3)
            throw new ContentInvalid("CONTENT_INVALID");
        var unique = new HashSet<String>();
        for (var sourceId : sourceIds) {
            if (sourceId == null || sourceId.isBlank() || !unique.add(sourceId))
                throw new ContentInvalid("CONTENT_INVALID");
            if (!allowedSources.contains(sourceId)) throw new ContentInvalid("SOURCE_NOT_IN_EVIDENCE");
        }
        return List.copyOf(sourceIds);
    }

    public record Topic(String column, String title, String audience, String angle, String hook,
                        String outline, List<String> sourceIds, String rationale) implements java.io.Serializable { }
    public record Script(String spokenText, String shootingNotes, List<String> sourceIds) implements java.io.Serializable { }
    public static final class ContentInvalid extends RuntimeException {
        public ContentInvalid(String code) { super(code, null, false, false); }
    }
}
