package com.example.creator.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.creator.analytics.OperationReviewService.ReviewFailure;

/** Configurations and immutable weekly requests; transactions end before model execution. */
@Service
public class ReviewJobStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "INSUFFICIENT_DATA", "FAILED", "NEEDS_REVIEW");
    ReviewJobStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public Job save(long ownerId, String accountId, Input input) {
        validate(input);
        if (jdbc.queryForList("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ? FOR UPDATE",
                String.class, ownerId, accountId).isEmpty()) throw new ReviewFailure("ACCOUNT_NOT_FOUND");
        jdbc.update("""
                INSERT INTO review_jobs (id, owner_id, account_id, day_of_week, local_time, time_zone, enabled, topic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE day_of_week = VALUES(day_of_week), local_time = VALUES(local_time),
                    time_zone = VALUES(time_zone), enabled = VALUES(enabled), topic = VALUES(topic)
                """, UUID.randomUUID().toString(), ownerId, accountId, input.dayOfWeek(), input.localTime(),
                input.timeZone(), input.enabled(), input.topic().strip());
        return findByAccount(ownerId, accountId).orElseThrow();
    }
    public Optional<Job> findByAccount(long ownerId, String accountId) {
        if (jdbc.queryForList("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ?",
                String.class, ownerId, accountId).isEmpty()) throw new ReviewFailure("ACCOUNT_NOT_FOUND");
        return jdbc.query("SELECT * FROM review_jobs WHERE owner_id = ? AND account_id = ?", this::job,
                ownerId, accountId).stream().findFirst();
    }
    List<Target> enabled() {
        return jdbc.query("SELECT id, owner_id FROM review_jobs WHERE enabled = TRUE ORDER BY id",
                (row, ignored) -> new Target(row.getString("id"), row.getLong("owner_id")));
    }
    @Transactional
    public Optional<Execution> reserveDue(long ownerId, String jobId, Instant now) {
        var found = jdbc.query("SELECT * FROM review_jobs WHERE id = ? AND owner_id = ? FOR UPDATE",
                this::job, jobId, ownerId);
        if (found.isEmpty() || !found.getFirst().enabled()) return Optional.empty();
        var job = found.getFirst();
        var local = now.atZone(ZoneId.of(job.timeZone()));
        var end = local.toLocalDate().atTime(job.localTime()).atZone(local.getZone());
        if (local.getDayOfWeek().getValue() != job.dayOfWeek() || now.isBefore(end.toInstant())) return Optional.empty();
        var week = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var request = new OperationReview.Request(job.accountId(), job.topic(), end.minusDays(7).toInstant(),
                end.toInstant(), UUID.randomUUID().toString());
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO review_job_triggers (id, job_id, owner_id, account_id, run_key, request_id, request_json, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING') ON DUPLICATE KEY UPDATE id = id
                """, id, job.id(), ownerId, job.accountId(), week, request.requestId(), encode(request));
        var saved = jdbc.queryForObject("SELECT id FROM review_job_triggers WHERE owner_id = ? AND job_id = ? AND run_key = ?",
                String.class, ownerId, job.id(), week);
        return id.equals(saved) ? Optional.of(new Execution(id, ownerId, request)) : Optional.empty();
    }
    public void finish(long ownerId, String id, String status, String errorCode) {
        jdbc.update("UPDATE review_job_triggers SET status = ?, error_code = ? WHERE owner_id = ? AND id = ? AND status IN ('RUNNING', 'GENERATING')",
                status, errorCode, ownerId, id);
    }
    public List<Trigger> triggers(long ownerId, String jobId) {
        if (jdbc.queryForList("SELECT id FROM review_jobs WHERE owner_id = ? AND id = ?", String.class,
                ownerId, jobId).isEmpty()) throw new ReviewFailure("JOB_NOT_FOUND");
        return jdbc.query("""
                SELECT t.*, r.id AS report_id,
                    CASE WHEN r.status IN ('SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED', 'NEEDS_REVIEW')
                         THEN r.status ELSE t.status END AS visible_status,
                    CASE WHEN r.status IN ('SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED', 'NEEDS_REVIEW')
                         THEN r.error_code ELSE t.error_code END AS visible_error
                FROM review_job_triggers t LEFT JOIN operation_reviews r
                    ON r.owner_id = t.owner_id AND r.account_id = t.account_id AND r.request_id = t.request_id
                WHERE t.owner_id = ? AND t.job_id = ? ORDER BY t.created_at DESC, t.id DESC LIMIT 20
                """, (row, ignored) -> new Trigger(row.getString("id"), row.getString("account_id"),
                row.getDate("run_key").toLocalDate().toString(), decode(row.getString("request_json")),
                row.getString("visible_status"), row.getString("report_id"), row.getString("visible_error"),
                row.getTimestamp("created_at").toInstant()), ownerId, jobId);
    }
    /** Called only while the scheduler's shared paid-work lock is held; never resends model requests. */
    @Transactional
    public void reconcile() {
        var running = jdbc.query("""
                SELECT t.id, t.owner_id, r.id AS report_id, r.status, r.error_code FROM review_job_triggers t
                LEFT JOIN operation_reviews r ON r.owner_id = t.owner_id AND r.account_id = t.account_id AND r.request_id = t.request_id
                WHERE t.status IN ('RUNNING', 'GENERATING')
                """, (row, ignored) -> new Interrupted(row.getString("id"), row.getLong("owner_id"),
                row.getString("report_id"), row.getString("status"), row.getString("error_code")));
        for (var item : running) {
            boolean terminal = TERMINAL.contains(item.status() == null ? "" : item.status());
            if (!terminal && item.reportId() != null) jdbc.update("""
                    UPDATE operation_reviews SET status = 'NEEDS_REVIEW', error_code = 'INTERRUPTED_UNKNOWN'
                    WHERE owner_id = ? AND id = ? AND status IN ('RUNNING', 'GENERATING')
                    """, item.ownerId(), item.reportId());
            finish(item.ownerId(), item.id(), terminal ? item.status() : "NEEDS_REVIEW",
                    terminal ? item.errorCode() : "INTERRUPTED_UNKNOWN");
        }
    }
    private void validate(Input input) {
        if (input == null || input.dayOfWeek() < 1 || input.dayOfWeek() > 7 || input.localTime() == null
                || input.localTime().getSecond() != 0 || input.localTime().getNano() != 0
                || input.timeZone() == null || input.timeZone().length() > 64 || input.enabled() == null
                || input.topic() == null || input.topic().isBlank() || input.topic().strip().length() > 100)
            throw new ReviewFailure("INVALID_REVIEW_JOB");
        try { ZoneId.of(input.timeZone()); }
        catch (DateTimeException invalid) { throw new ReviewFailure("INVALID_REVIEW_JOB"); }
    }
    private Job job(ResultSet row, int ignored) throws SQLException {
        return new Job(row.getString("id"), row.getString("account_id"), row.getInt("day_of_week"),
                row.getTime("local_time").toLocalTime(), row.getString("time_zone"), row.getBoolean("enabled"), row.getString("topic"));
    }
    private String encode(OperationReview.Request request) {
        try { return json.writeValueAsString(request); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Review job serialization failed", invalid); }
    }
    private OperationReview.Request decode(String value) {
        try { return json.readValue(value, OperationReview.Request.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid saved review job request", invalid); }
    }
    public record Input(int dayOfWeek, LocalTime localTime, String timeZone, Boolean enabled, String topic) { }
    public record Job(String id, String accountId, int dayOfWeek, LocalTime localTime, String timeZone, boolean enabled, String topic) { }
    public record Trigger(String id, String accountId, String runKey, OperationReview.Request request,
                          String status, String reportId, String errorCode, Instant createdAt) { }
    public record Execution(String id, long ownerId, OperationReview.Request request) { }
    record Target(String id, long ownerId) { }
    private record Interrupted(String id, long ownerId, String reportId, String status, String errorCode) { }
}
