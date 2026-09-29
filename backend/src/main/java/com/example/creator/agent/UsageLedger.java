package com.example.creator.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One durable row per external call attempt. Unknown usage or price remains NULL. */
@Service
public class UsageLedger {
    private final JdbcTemplate jdbc;
    private final BigDecimal modelInputRate;
    private final BigDecimal modelOutputRate;
    private final BigDecimal searchCallRate;

    UsageLedger(JdbcTemplate jdbc,
                @Value("${creator.usage.model-input-cny-per-million:}") String inputRate,
                @Value("${creator.usage.model-output-cny-per-million:}") String outputRate,
                @Value("${creator.usage.search-cny-per-call:}") String searchRate) {
        this.jdbc = jdbc;
        this.modelInputRate = rate(inputRate);
        this.modelOutputRate = rate(outputRate);
        this.searchCallRate = rate(searchRate);
    }

    private static BigDecimal rate(String value) {
        if (value == null || value.isBlank()) return null;
        var parsed = new BigDecimal(value);
        if (parsed.signum() < 0 || parsed.scale() > 6)
            throw new IllegalArgumentException("Usage price must be non-negative with at most 6 decimals");
        return parsed;
    }

    public String start(long ownerId, String taskId, String kind, String provider, String operation) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64
                || provider == null || provider.isBlank() || provider.length() > 64
                || operation == null || operation.isBlank() || operation.length() > 32
                || !List.of("MODEL", "MCP_SEARCH").contains(kind))
            throw new IllegalArgumentException("Invalid usage call identity");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO usage_calls (id, owner_id, task_id, kind, provider, operation, status,
                                         input_rate_cny_per_million, output_rate_cny_per_million, call_rate_cny)
                VALUES (?, ?, ?, ?, ?, ?, 'STARTED', ?, ?, ?)
                """, id, ownerId, taskId, kind, provider, operation,
                "MODEL".equals(kind) ? modelInputRate : null,
                "MODEL".equals(kind) ? modelOutputRate : null,
                "MCP_SEARCH".equals(kind) ? searchCallRate : null);
        return id;
    }

    public void modelSucceeded(String id, Integer inputTokens, Integer outputTokens) {
        BigDecimal cost = inputTokens == null || outputTokens == null
                || modelInputRate == null || modelOutputRate == null ? null
                : modelInputRate.multiply(BigDecimal.valueOf(inputTokens))
                        .add(modelOutputRate.multiply(BigDecimal.valueOf(outputTokens)))
                        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        finish(id, "SUCCEEDED", inputTokens, outputTokens, cost, null);
    }

    public void searchSucceeded(String id) {
        finish(id, "SUCCEEDED", null, null, searchCallRate, null);
    }

    public void failed(String id, String code) {
        finish(id, "FAILED", null, null, null, code);
    }

    private void finish(String id, String status, Integer inputTokens, Integer outputTokens,
                        BigDecimal cost, String code) {
        int changed = jdbc.update("""
                UPDATE usage_calls SET status = ?, input_tokens = ?, output_tokens = ?, cost_cny = ?, error_code = ?
                WHERE id = ? AND status = 'STARTED'
                """, status, inputTokens, outputTokens, cost, code, id);
        if (changed != 1) throw new IllegalStateException("Usage call was not started");
    }

    public Summary summary(long ownerId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS calls, SUM(status = 'SUCCEEDED') AS succeeded,
                       SUM(status = 'FAILED') AS failed,
                       SUM(cost_cny IS NULL) AS pending_reconciliation,
                       SUM(cost_cny) AS known_cost_cny
                FROM usage_calls WHERE owner_id = ?
                """, (row, ignored) -> new Summary(row.getLong("calls"), row.getLong("succeeded"),
                row.getLong("failed"), row.getLong("pending_reconciliation"),
                row.getBigDecimal("known_cost_cny")), ownerId);
    }

    public List<Call> recent(long ownerId) {
        return jdbc.query("""
                SELECT id, task_id, kind, provider, operation, status, input_tokens, output_tokens,
                       input_rate_cny_per_million, output_rate_cny_per_million, call_rate_cny,
                       cost_cny, error_code
                FROM usage_calls WHERE owner_id = ? ORDER BY created_at DESC, id DESC LIMIT 100
                """, (row, ignored) -> new Call(row.getString("id"), row.getString("task_id"),
                row.getString("kind"), row.getString("provider"), row.getString("operation"),
                row.getString("status"), nullableInt(row.getObject("input_tokens")),
                nullableInt(row.getObject("output_tokens")), row.getBigDecimal("input_rate_cny_per_million"),
                row.getBigDecimal("output_rate_cny_per_million"), row.getBigDecimal("call_rate_cny"),
                row.getBigDecimal("cost_cny"), row.getString("error_code")), ownerId);
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public record Summary(long calls, long succeeded, long failed, long pendingReconciliation,
                          BigDecimal knownCostCny) { }
    public record Call(String id, String taskId, String kind, String provider, String operation,
                       String status, Integer inputTokens, Integer outputTokens,
                       BigDecimal inputRateCnyPerMillion, BigDecimal outputRateCnyPerMillion,
                       BigDecimal callRateCny, BigDecimal costCny, String errorCode) { }
}
