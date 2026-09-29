package com.example.creator.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable row per external call attempt. Unknown usage or price remains NULL. */
@Service
public class UsageLedger {
    static final int MAX_MODEL_OUTPUT_TOKENS = 2048;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BigDecimal modelInputRate;
    private final BigDecimal modelOutputRate;
    private final BigDecimal searchCallRate;
    private final boolean budgetEnabled;
    private final int monthlyUserCallLimit;
    private final BigDecimal monthlySystemBudget;

    UsageLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                @Value("${creator.usage.model-input-cny-per-million:}") String inputRate,
                @Value("${creator.usage.model-output-cny-per-million:}") String outputRate,
                @Value("${creator.usage.search-cny-per-call:}") String searchRate,
                @Value("${creator.usage.budget-enabled:false}") boolean budgetEnabled,
                @Value("${creator.usage.monthly-user-call-limit:100}") int userCallLimit,
                @Value("${creator.usage.monthly-system-budget-cny:100}") String systemBudget) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.modelInputRate = rate(inputRate);
        this.modelOutputRate = rate(outputRate);
        this.searchCallRate = rate(searchRate);
        if (userCallLimit < 1) throw new IllegalArgumentException("Monthly user call limit must be positive");
        this.monthlyUserCallLimit = userCallLimit;
        this.monthlySystemBudget = rate(systemBudget);
        if (this.monthlySystemBudget == null || this.monthlySystemBudget.signum() == 0)
            throw new IllegalArgumentException("Monthly system budget must be positive");
        this.budgetEnabled = budgetEnabled;
    }

    private static BigDecimal rate(String value) {
        if (value == null || value.isBlank()) return null;
        var parsed = new BigDecimal(value);
        if (parsed.signum() < 0 || parsed.scale() > 6)
            throw new IllegalArgumentException("Usage price must be non-negative with at most 6 decimals");
        return parsed;
    }

    public String start(long ownerId, String taskId, String kind, String provider, String operation) {
        return start(ownerId, taskId, kind, provider, operation, 0);
    }

    public String start(long ownerId, String taskId, String kind, String provider,
                        String operation, int estimatedInputTokens) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64
                || provider == null || provider.isBlank() || provider.length() > 64
                || operation == null || operation.isBlank() || operation.length() > 32
                || estimatedInputTokens < 0 || !List.of("MODEL", "MCP_SEARCH").contains(kind))
            throw new IllegalArgumentException("Invalid usage call identity");
        if (!budgetEnabled) return insert(ownerId, taskId, kind, provider, operation, null);
        BigDecimal reserve = reservation(kind, estimatedInputTokens);
        return transactions.execute(status -> {
            lockBudget();
            var month = monthStart();
            var calls = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM usage_calls WHERE owner_id = ? AND created_at >= ?
                    """, Long.class, ownerId, month);
            if (calls >= monthlyUserCallLimit) throw new BudgetPaused("USER_CALL_LIMIT_REACHED");
            var unreserved = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM usage_calls
                    WHERE created_at >= ? AND cost_cny IS NULL AND reserved_cny IS NULL
                    """, Long.class, month);
            if (unreserved > 0) throw new BudgetPaused("BUDGET_RECONCILIATION_REQUIRED");
            var exposure = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(COALESCE(cost_cny, reserved_cny)), 0)
                    FROM usage_calls WHERE created_at >= ?
                    """, BigDecimal.class, month);
            if (exposure.add(reserve).compareTo(monthlySystemBudget) > 0)
                throw new BudgetPaused("BUDGET_LIMIT_REACHED");
            return insert(ownerId, taskId, kind, provider, operation, reserve);
        });
    }

    private BigDecimal reservation(String kind, int estimatedInputTokens) {
        if ("MCP_SEARCH".equals(kind)) {
            if (searchCallRate == null) throw new BudgetPaused("BUDGET_PRICE_UNCONFIGURED");
            return searchCallRate;
        }
        if (modelInputRate == null || modelOutputRate == null)
            throw new BudgetPaused("BUDGET_PRICE_UNCONFIGURED");
        return modelInputRate.multiply(BigDecimal.valueOf(estimatedInputTokens))
                .add(modelOutputRate.multiply(BigDecimal.valueOf(MAX_MODEL_OUTPUT_TOKENS)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.UP);
    }

    private String insert(long ownerId, String taskId, String kind, String provider,
                          String operation, BigDecimal reserve) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO usage_calls (id, owner_id, task_id, kind, provider, operation, status,
                                         input_rate_cny_per_million, output_rate_cny_per_million,
                                         call_rate_cny, reserved_cny)
                VALUES (?, ?, ?, ?, ?, ?, 'STARTED', ?, ?, ?, ?)
                """, id, ownerId, taskId, kind, provider, operation,
                "MODEL".equals(kind) ? modelInputRate : null,
                "MODEL".equals(kind) ? modelOutputRate : null,
                "MCP_SEARCH".equals(kind) ? searchCallRate : null, reserve);
        return id;
    }

    private void lockBudget() {
        jdbc.queryForObject("SELECT id FROM usage_budget_mutex WHERE id = 1 FOR UPDATE", Integer.class);
    }

    private static Timestamp monthStart() {
        return Timestamp.from(YearMonth.now(ZoneOffset.UTC).atDay(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC));
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
        if (budgetEnabled) {
            transactions.executeWithoutResult(transaction -> {
                lockBudget();
                updateFinished(id, status, inputTokens, outputTokens, cost, code);
            });
        } else {
            updateFinished(id, status, inputTokens, outputTokens, cost, code);
        }
    }

    private void updateFinished(String id, String status, Integer inputTokens, Integer outputTokens,
                                BigDecimal cost, String code) {
        int changed = jdbc.update("""
                UPDATE usage_calls SET status = ?, input_tokens = ?, output_tokens = ?, cost_cny = ?,
                                       reserved_cny = IF(? IS NULL, reserved_cny, NULL), error_code = ?
                WHERE id = ? AND status = 'STARTED'
                """, status, inputTokens, outputTokens, cost, cost, code, id);
        if (changed != 1) throw new IllegalStateException("Usage call was not started");
    }

    public BudgetStatus budgetStatus(long ownerId) {
        var month = monthStart();
        var calls = jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_calls WHERE owner_id = ? AND created_at >= ?
                """, Long.class, ownerId, month);
        var exposure = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(cost_cny, reserved_cny)), 0)
                FROM usage_calls WHERE created_at >= ?
                """, BigDecimal.class, month);
        var unreserved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_calls
                WHERE created_at >= ? AND cost_cny IS NULL AND reserved_cny IS NULL
                """, Long.class, month);
        String pause = !budgetEnabled ? null
                : calls >= monthlyUserCallLimit ? "USER_CALL_LIMIT_REACHED"
                : unreserved > 0 ? "BUDGET_RECONCILIATION_REQUIRED"
                : exposure.compareTo(monthlySystemBudget) >= 0 ? "BUDGET_LIMIT_REACHED"
                : modelInputRate == null || modelOutputRate == null || searchCallRate == null
                    ? "BUDGET_PRICE_UNCONFIGURED" : null;
        return new BudgetStatus(budgetEnabled, YearMonth.now(ZoneOffset.UTC).toString(),
                calls, monthlyUserCallLimit, exposure, monthlySystemBudget, unreserved, pause);
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
                       cost_cny, reserved_cny, error_code, created_at, updated_at
                FROM usage_calls WHERE owner_id = ? ORDER BY created_at DESC, id DESC LIMIT 100
                """, (row, ignored) -> new Call(row.getString("id"), row.getString("task_id"),
                row.getString("kind"), row.getString("provider"), row.getString("operation"),
                row.getString("status"), nullableInt(row.getObject("input_tokens")),
                nullableInt(row.getObject("output_tokens")), row.getBigDecimal("input_rate_cny_per_million"),
                row.getBigDecimal("output_rate_cny_per_million"), row.getBigDecimal("call_rate_cny"),
                row.getBigDecimal("cost_cny"), row.getBigDecimal("reserved_cny"),
                row.getString("error_code"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), ownerId);
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public record Summary(long calls, long succeeded, long failed, long pendingReconciliation,
                          BigDecimal knownCostCny) { }
    public record BudgetStatus(boolean enabled, String utcMonth, long userCalls, int userCallLimit,
                               BigDecimal systemExposureCny, BigDecimal systemLimitCny,
                               long unreservedUnknownCalls, String pauseCode) { }
    public static final class BudgetPaused extends RuntimeException {
        BudgetPaused(String code) { super(code, null, false, false); }
    }
    public record Call(String id, String taskId, String kind, String provider, String operation,
                       String status, Integer inputTokens, Integer outputTokens,
                       BigDecimal inputRateCnyPerMillion, BigDecimal outputRateCnyPerMillion,
                       BigDecimal callRateCny, BigDecimal costCny, BigDecimal reservedCny,
                       String errorCode, Instant createdAt, Instant updatedAt) { }
}
