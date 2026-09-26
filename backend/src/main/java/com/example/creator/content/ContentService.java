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
                    SELECT id, account_id, mode, status, attempts, result_id, error_code, failed_node
                    FROM generation_runs WHERE owner_id = ? AND id = ?
                    """, (row, index) -> new GenerationRun(row.getString("id"), row.getString("account_id"),
                    row.getString("mode"), row.getString("status"), row.getInt("attempts"),
                    row.getString("result_id"), row.getString("error_code"), row.getString("failed_node")), ownerId, runId));
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
                SELECT id, topic_id, spoken_text, shooting_notes, source_ids_json, status, version, conversation_id
                FROM content_scripts WHERE owner_id = ? AND topic_id = ? ORDER BY created_at DESC, id DESC LIMIT 50
                """, (row, index) -> new SavedScript(row.getString("id"), row.getString("topic_id"),
                new Script(row.getString("spoken_text"), row.getString("shooting_notes"),
                        readSources(row.getString("source_ids_json"))), row.getString("status"),
                row.getInt("version"), row.getString("conversation_id")), ownerId, topicId);
    }

    public Optional<SavedScript> findScript(long ownerId, String scriptId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, topic_id, spoken_text, shooting_notes, source_ids_json, status, version, conversation_id
                    FROM content_scripts WHERE owner_id = ? AND id = ?
                    """, (row, index) -> new SavedScript(row.getString("id"), row.getString("topic_id"),
                    new Script(row.getString("spoken_text"), row.getString("shooting_notes"),
                            readSources(row.getString("source_ids_json"))), row.getString("status"),
                    row.getInt("version"), row.getString("conversation_id")), ownerId, scriptId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Transactional
    public SavedScript confirmScript(long ownerId, String scriptId, int expectedVersion) {
        return changeScriptStatus(ownerId, scriptId, expectedVersion, "DRAFT", "CONFIRMED");
    }

    @Transactional
    public SavedScript reopenScript(long ownerId, String scriptId, int expectedVersion) {
        return changeScriptStatus(ownerId, scriptId, expectedVersion, "CONFIRMED", "DRAFT");
    }

    private SavedScript changeScriptStatus(long ownerId, String scriptId, int expectedVersion,
                                          String from, String to) {
        var current = findScript(ownerId, scriptId)
                .orElseThrow(() -> new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND"));
        if (current.version() != expectedVersion) throw new VersionConflict();
        if (!current.status().equals(from))
            throw new ContentValidator.ContentInvalid("INVALID_SCRIPT_STATUS");
        int changed = jdbc.update("""
                UPDATE content_scripts SET status = ?, version = version + 1
                WHERE owner_id = ? AND id = ? AND version = ? AND status = ?
                """, to, ownerId, scriptId, expectedVersion, from);
        if (changed == 0) throw new VersionConflict();
        return findScript(ownerId, scriptId).orElseThrow();
    }

    public List<String> recentInstructions(long ownerId, String conversationId) {
        return jdbc.queryForList("""
                SELECT v.instruction FROM content_script_versions v
                JOIN content_scripts s ON s.id = v.script_id
                WHERE s.owner_id = ? AND v.conversation_id = ?
                ORDER BY v.created_at DESC LIMIT 3
                """, String.class, ownerId, conversationId);
    }

    public boolean ownsConversation(long ownerId, String accountId, String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM generation_conversations
                              WHERE id = ? AND owner_id = ? AND account_id = ?)
                """, Boolean.class, conversationId, ownerId, accountId));
    }

    @Transactional
    public String saveWeekPlan(long ownerId, String accountId, List<WeekItem> items) {
        if (accounts.find(ownerId, accountId).isEmpty()) throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        if (items == null || items.size() != 3 || items.stream().filter(item -> "Java 面试".equals(item.column())).count() != 2
                || items.stream().filter(item -> "英语跟读".equals(item.column())).count() != 1)
            throw new ContentValidator.ContentInvalid("INVALID_WEEK_PLAN");
        for (var item : items) {
            var topic = findTopic(ownerId, item.topicId()).orElseThrow(() -> new ContentValidator.ContentInvalid("TOPIC_NOT_FOUND"));
            var script = findScript(ownerId, item.scriptId()).orElseThrow(() -> new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND"));
            if (!accountId.equals(topic.accountId()) || !topic.topic().column().equals(item.column())
                    || !script.topicId().equals(item.topicId()))
                throw new ContentValidator.ContentInvalid("INVALID_WEEK_PLAN");
        }
        var id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO content_week_plans (id, owner_id, account_id, items_json) VALUES (?, ?, ?, ?)",
                    id, ownerId, accountId, json.writeValueAsString(items));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return id;
    }

    public Optional<WeekPlan> findWeekPlan(long ownerId, String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, account_id, items_json, status FROM content_week_plans
                    WHERE id = ? AND owner_id = ?
                    """, (row, index) -> new WeekPlan(row.getString("id"), row.getString("account_id"),
                    readWeekItems(row.getString("items_json")), row.getString("status")), id, ownerId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public List<WeekPlan> weekPlans(long ownerId, String accountId) {
        if (accounts.find(ownerId, accountId).isEmpty()) throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        return jdbc.query("""
                SELECT id, account_id, items_json, status FROM content_week_plans
                WHERE owner_id = ? AND account_id = ? ORDER BY created_at DESC, id DESC LIMIT 20
                """, (row, index) -> new WeekPlan(row.getString("id"), row.getString("account_id"),
                readWeekItems(row.getString("items_json")), row.getString("status")), ownerId, accountId);
    }

    public List<ScriptVersion> versions(long ownerId, String scriptId) {
        if (findScript(ownerId, scriptId).isEmpty()) throw new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND");
        return jdbc.query("""
                SELECT version, spoken_text, shooting_notes, source_ids_json, conversation_id, instruction
                FROM content_script_versions WHERE script_id = ? ORDER BY version
                """, (row, index) -> new ScriptVersion(row.getInt("version"),
                new Script(row.getString("spoken_text"), row.getString("shooting_notes"),
                        readSources(row.getString("source_ids_json"))), row.getString("conversation_id"),
                row.getString("instruction")), scriptId);
    }

    @Transactional
    public SavedScript reviseScript(long ownerId, String scriptId, int expectedVersion,
                                     String conversationId, String instruction, Script revision) {
        if (scriptId == null || expectedVersion < 1 || instruction == null || instruction.isBlank()
                || instruction.strip().length() > 500)
            throw new ContentValidator.ContentInvalid("INVALID_REVISION");
        try {
            jdbc.queryForObject("SELECT version FROM content_scripts WHERE owner_id = ? AND id = ? FOR UPDATE",
                    Integer.class, ownerId, scriptId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND");
        }
        var previous = findScript(ownerId, scriptId).orElseThrow();
        if (previous.version() != expectedVersion) throw new VersionConflict();
        if ("CONFIRMED".equals(previous.status()))
            throw new ContentValidator.ContentInvalid("SCRIPT_CONFIRMED");
        var accountId = findTopic(ownerId, previous.topicId()).orElseThrow().accountId();
        var sources = Set.copyOf(previous.script().sourceIds());
        var clean = validator.validateScript(revision, sources);
        if (!sources.equals(Set.copyOf(clean.sourceIds())))
            throw new ContentValidator.ContentInvalid("SOURCE_CHANGED");
        String sessionId;
        if (conversationId == null || conversationId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO generation_conversations (id, owner_id, account_id) VALUES (?, ?, ?)",
                    sessionId, ownerId, accountId);
        } else {
            sessionId = conversationId;
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM generation_conversations
                                  WHERE id = ? AND owner_id = ? AND account_id = ?)
                    """, Boolean.class, sessionId, ownerId, accountId)))
                throw new ContentValidator.ContentInvalid("CONVERSATION_NOT_FOUND");
        }
        jdbc.update("""
                INSERT INTO content_script_versions (script_id, version, spoken_text, shooting_notes,
                    source_ids_json, conversation_id, instruction) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, scriptId, previous.version(), previous.script().spokenText(), previous.script().shootingNotes(),
                sourcesJson(previous.script().sourceIds()), sessionId, instruction.strip());
        jdbc.update("""
                UPDATE content_scripts SET spoken_text = ?, shooting_notes = ?, source_ids_json = ?,
                    version = version + 1, conversation_id = ? WHERE id = ? AND owner_id = ?
                """, clean.spokenText(), clean.shootingNotes(), sourcesJson(clean.sourceIds()),
                sessionId, scriptId, ownerId);
        return findScript(ownerId, scriptId).orElseThrow();
    }

    public GenerationRun startRun(long ownerId, String accountId, String mode) {
        if (accounts.find(ownerId, accountId).isEmpty()) throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        if (!List.of("TOPICS", "SCRIPT", "WEEK_PLAN").contains(mode))
            throw new ContentValidator.ContentInvalid("INVALID_MODE");
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

    public GenerationRun failRun(long ownerId, String runId, String errorCode, String failedNode, int attempts) {
        jdbc.update("UPDATE generation_runs SET status = 'FAILED', error_code = ?, failed_node = ?, attempts = ? WHERE owner_id = ? AND id = ?",
                errorCode, failedNode, attempts, ownerId, runId);
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

    private List<WeekItem> readWeekItems(String value) {
        try {
            return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<WeekItem>>() { });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid saved week items", invalid);
        }
    }

    public record GenerationRun(String id, String accountId, String mode, String status,
                                int attempts, String resultId, String errorCode, String failedNode) {
        public GenerationRun(String id, String accountId, String mode, String status,
                             int attempts, String resultId, String errorCode) {
            this(id, accountId, mode, status, attempts, resultId, errorCode, null);
        }
    }
    public record SavedTopic(String id, String accountId, Topic topic) implements java.io.Serializable { }
    public record SavedScript(String id, String topicId, Script script, String status, int version,
                              String conversationId) {
        public SavedScript(String id, String topicId, Script script, String status, int version) {
            this(id, topicId, script, status, version, null);
        }
    }
    public record ScriptVersion(int version, Script script, String conversationId, String instruction) { }
    public record WeekItem(String column, String topicId, String scriptId) { }
    public record WeekPlan(String id, String accountId, List<WeekItem> items, String status) { }
    public static final class VersionConflict extends RuntimeException { }
}
