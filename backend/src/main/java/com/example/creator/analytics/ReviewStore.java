package com.example.creator.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ReviewStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public Reservation reserve(long ownerId, OperationReview.Request request) {
        if (jdbc.queryForList("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ? FOR UPDATE",
                String.class, ownerId, request.accountId()).isEmpty())
            throw new OperationReviewService.ReviewFailure("ACCOUNT_NOT_FOUND");
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO operation_reviews (id, owner_id, account_id, request_id, request_json, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING') ON DUPLICATE KEY UPDATE id = id
                """, id, ownerId, request.accountId(), request.requestId(), encode(request));
        var existing = jdbc.query("SELECT * FROM operation_reviews WHERE owner_id = ? AND request_id = ?",
                this::report, ownerId, request.requestId()).getFirst();
        if (!existing.request().equals(request)) throw new OperationReviewService.ReviewFailure("REVIEW_REQUEST_CONFLICT");
        return new Reservation(existing, id.equals(existing.id()));
    }

    public Optional<OperationReview.Report> find(long ownerId, String id) {
        return jdbc.query("SELECT * FROM operation_reviews WHERE owner_id = ? AND id = ?", this::report,
                ownerId, id).stream().findFirst();
    }

    public List<OperationReview.Report> list(long ownerId, String accountId) {
        if (jdbc.queryForList("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ?",
                String.class, ownerId, accountId).isEmpty()) throw new OperationReviewService.ReviewFailure("ACCOUNT_NOT_FOUND");
        return jdbc.query("""
                SELECT * FROM operation_reviews WHERE owner_id = ? AND account_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 20
                """, this::report, ownerId, accountId);
    }

    public void facts(long ownerId, String id, OperationReview.Facts facts) {
        jdbc.update("UPDATE operation_reviews SET facts_json = ? WHERE owner_id = ? AND id = ? AND status = 'RUNNING'",
                encode(facts), ownerId, id);
    }

    public void attempt(long ownerId, String id, int attempts) {
        jdbc.update("""
                UPDATE operation_reviews SET status = 'GENERATING', attempts = ?
                WHERE owner_id = ? AND id = ? AND status IN ('RUNNING', 'GENERATING')
                """, attempts, ownerId, id);
    }

    public OperationReview.Report finish(long ownerId, String id, String status, OperationReview.Advice advice, String errorCode) {
        jdbc.update("UPDATE operation_reviews SET status = ?, advice_json = ?, error_code = ? WHERE owner_id = ? AND id = ?",
                status, advice == null ? null : encode(advice), errorCode, ownerId, id);
        return find(ownerId, id).orElseThrow();
    }

    private OperationReview.Report report(ResultSet row, int ignored) throws SQLException {
        return new OperationReview.Report(row.getString("id"), row.getString("account_id"), row.getString("status"),
                decode(row.getString("request_json"), OperationReview.Request.class),
                decode(row.getString("facts_json"), OperationReview.Facts.class),
                decode(row.getString("advice_json"), OperationReview.Advice.class), row.getInt("attempts"),
                row.getString("error_code"), row.getTimestamp("created_at").toInstant());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Review serialization failed", invalid); }
    }
    private <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid saved review", invalid); }
    }
    public record Reservation(OperationReview.Report report, boolean created) { }
}
