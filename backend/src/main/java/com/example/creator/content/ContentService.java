package com.example.creator.content;

import com.example.creator.agent.AccountProfiles;
import com.example.creator.content.ContentValidator.Script;
import com.example.creator.content.ContentValidator.Topic;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saves only validated drafts for an account owned by the caller. */
@Service
public class ContentService {
    private final JdbcTemplate jdbc;
    private final AccountProfiles accounts;
    private final ContentValidator validator;
    private final ObjectMapper json;

    ContentService(JdbcTemplate jdbc, AccountProfiles accounts, ContentValidator validator, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.validator = validator;
        this.json = json;
    }

    /** A malformed model result may be corrected once; both outcomes remain visible as a run. */
    @Transactional
    public GenerationRun generateTopic(long ownerId, String accountId, String raw,
                                       Set<String> allowedSources, UnaryOperator<String> correct) {
        if (accountId == null || accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        var runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO generation_runs (id, owner_id, account_id, mode, status)
                VALUES (?, ?, ?, 'TOPICS', 'RUNNING')
                """, runId, ownerId, accountId);
        Topic topic;
        int attempts = 1;
        try {
            topic = validator.topic(raw, allowedSources);
        } catch (ContentValidator.ContentInvalid invalid) {
            attempts = 2;
            try {
                topic = validator.topic(correct.apply(raw), allowedSources);
            } catch (RuntimeException failed) {
                var code = failed instanceof ContentValidator.ContentInvalid
                        ? "CONTENT_INVALID_AFTER_CORRECTION" : "GENERATION_CORRECTION_FAILED";
                jdbc.update("UPDATE generation_runs SET status = 'FAILED', attempts = 2, error_code = ? WHERE id = ?",
                        code, runId);
                return new GenerationRun(runId, accountId, "TOPICS", "FAILED", 2, null, code);
            }
        }
        var topicId = saveTopic(ownerId, accountId, topic, allowedSources);
        jdbc.update("UPDATE generation_runs SET status = 'SUCCEEDED', attempts = ?, result_id = ? WHERE id = ?",
                attempts, topicId, runId);
        return new GenerationRun(runId, accountId, "TOPICS", "SUCCEEDED", attempts, topicId, null);
    }

    public Optional<GenerationRun> findRun(long ownerId, String runId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, account_id, mode, status, attempts, result_id, error_code
                    FROM generation_runs WHERE owner_id = ? AND id = ?
                    """, (row, index) -> new GenerationRun(row.getString("id"), row.getString("account_id"),
                    row.getString("mode"), row.getString("status"), row.getInt("attempts"),
                    row.getString("result_id"), row.getString("error_code")), ownerId, runId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public Optional<SavedTopic> findTopic(long ownerId, String topicId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, account_id, column_name, title, audience, angle, hook, outline,
                           rationale, source_ids_json FROM content_topics WHERE owner_id = ? AND id = ?
                    """, (row, index) -> new SavedTopic(row.getString("id"), row.getString("account_id"),
                    new Topic(row.getString("column_name"), row.getString("title"), row.getString("audience"),
                            row.getString("angle"), row.getString("hook"), row.getString("outline"),
                            readSources(row.getString("source_ids_json")), row.getString("rationale"))), ownerId, topicId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public List<SavedTopic> topics(long ownerId, String accountId) {
        if (accounts.find(ownerId, accountId).isEmpty()) throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        return jdbc.query("""
                SELECT id, account_id, column_name, title, audience, angle, hook, outline,
                       rationale, source_ids_json FROM content_topics
                WHERE owner_id = ? AND account_id = ? ORDER BY created_at DESC, id DESC LIMIT 50
                """, (row, index) -> new SavedTopic(row.getString("id"), row.getString("account_id"),
                new Topic(row.getString("column_name"), row.getString("title"), row.getString("audience"),
                        row.getString("angle"), row.getString("hook"), row.getString("outline"),
                        readSources(row.getString("source_ids_json")), row.getString("rationale"))), ownerId, accountId);
    }

    public List<SavedScript> scripts(long ownerId, String topicId) {
        if (findTopic(ownerId, topicId).isEmpty()) throw new ContentValidator.ContentInvalid("TOPIC_NOT_FOUND");
        return jdbc.query("""
                SELECT id, topic_id, spoken_text, shooting_notes, source_ids_json, status, version
                FROM content_scripts WHERE owner_id = ? AND topic_id = ? ORDER BY created_at DESC, id DESC LIMIT 50
                """, (row, index) -> new SavedScript(row.getString("id"), row.getString("topic_id"),
                new Script(row.getString("spoken_text"), row.getString("shooting_notes"),
                        readSources(row.getString("source_ids_json"))), row.getString("status"),
                row.getInt("version")), ownerId, topicId);
    }

    public Optional<SavedScript> findScript(long ownerId, String scriptId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, topic_id, spoken_text, shooting_notes, source_ids_json, status, version
                    FROM content_scripts WHERE owner_id = ? AND id = ?
                    """, (row, index) -> new SavedScript(row.getString("id"), row.getString("topic_id"),
                    new Script(row.getString("spoken_text"), row.getString("shooting_notes"),
                            readSources(row.getString("source_ids_json"))), row.getString("status"),
                    row.getInt("version")), ownerId, scriptId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public GenerationRun startRun(long ownerId, String accountId, String mode) {
        if (accounts.find(ownerId, accountId).isEmpty()) throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        if (!List.of("TOPICS", "SCRIPT").contains(mode)) throw new ContentValidator.ContentInvalid("INVALID_MODE");
        var id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO generation_runs (id, owner_id, account_id, mode, status) VALUES (?, ?, ?, ?, 'RUNNING')",
                id, ownerId, accountId, mode);
        return new GenerationRun(id, accountId, mode, "RUNNING", 0, null, null);
    }

    public GenerationRun finishRun(long ownerId, String runId, String resultId, int attempts) {
        jdbc.update("UPDATE generation_runs SET status = 'SUCCEEDED', result_id = ?, attempts = ? WHERE owner_id = ? AND id = ?",
                resultId, attempts, ownerId, runId);
        return findRun(ownerId, runId).orElseThrow();
    }

    public GenerationRun failRun(long ownerId, String runId, String errorCode, int attempts) {
        jdbc.update("UPDATE generation_runs SET status = 'FAILED', error_code = ?, attempts = ? WHERE owner_id = ? AND id = ?",
                errorCode, attempts, ownerId, runId);
        return findRun(ownerId, runId).orElseThrow();
    }

    @Transactional
    public String saveTopic(long ownerId, String accountId, Topic topic, Set<String> allowedSources) {
        if (accountId == null || accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        var clean = validator.validateTopic(topic, allowedSources);
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_topics (id, owner_id, account_id, column_name, title, audience,
                    angle, hook, outline, rationale, source_ids_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, accountId, clean.column(), clean.title(), clean.audience(),
                clean.angle(), clean.hook(), clean.outline(), clean.rationale(), sourcesJson(clean.sourceIds()));
        return id;
    }

    @Transactional
    public String saveScript(long ownerId, String topicId, Script script, Set<String> allowedSources) {
        if (topicId == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM content_topics WHERE id = ? AND owner_id = ?)",
                Boolean.class, topicId, ownerId)))
            throw new ContentValidator.ContentInvalid("TOPIC_NOT_FOUND");
        var clean = validator.validateScript(script, allowedSources);
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_scripts (id, owner_id, topic_id, spoken_text, shooting_notes, source_ids_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, ownerId, topicId, clean.spokenText(), clean.shootingNotes(),
                sourcesJson(clean.sourceIds()));
        return id;
    }

    private String sourcesJson(java.util.List<String> sourceIds) {
        try {
            return json.writeValueAsString(sourceIds);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private List<String> readSources(String sourceIds) {
        try {
            return json.readValue(sourceIds, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid saved source IDs", invalid);
        }
    }

    public record GenerationRun(String id, String accountId, String mode, String status,
                                int attempts, String resultId, String errorCode) { }
    public record SavedTopic(String id, String accountId, Topic topic) { }
    public record SavedScript(String id, String topicId, Script script, String status, int version) { }
}
