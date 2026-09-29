package com.example.creator.agent;

import com.example.creator.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"DEV_USER_PASSWORD=development-fixture-only",
        "creator.usage.model-input-cny-per-million=2.00",
        "creator.usage.model-output-cny-per-million=8.00",
        "creator.usage.search-cny-per-call=0.01"})
@ActiveProfiles("dev")
class UsageLedgerTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsageLedger ledger;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void clearUsage() {
        jdbc.update("DELETE FROM usage_calls");
    }

    @Test
    void recordsPriceSnapshotsUnknownFeesAndOwnerScopedTotals() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long other = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        String task = java.util.UUID.randomUUID().toString();
        ledger.modelSucceeded(ledger.start(owner, task, "MODEL", "deepseek-chat", "replyJson"),
                1_000, 500);
        ledger.searchSucceeded(ledger.start(owner, task, "MCP_SEARCH", "tavily", "tavily_search"));
        ledger.failed(ledger.start(owner, task, "MODEL", "deepseek-chat", "replyJson"),
                "MODEL_TIMEOUT");
        ledger.modelSucceeded(ledger.start(owner, task, "MODEL", "deepseek-chat", "reply"),
                null, null);

        var summary = ledger.summary(owner);
        assertThat(summary.calls()).isEqualTo(4);
        assertThat(summary.succeeded()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.pendingReconciliation()).isEqualTo(2);
        assertThat(summary.knownCostCny()).isEqualByComparingTo(new BigDecimal("0.016000"));
        assertThat(ledger.recent(owner)).hasSize(4).anySatisfy(call -> {
            assertThat(call.kind()).isEqualTo("MODEL");
            assertThat(call.inputTokens()).isEqualTo(1_000);
            assertThat(call.outputTokens()).isEqualTo(500);
            assertThat(call.inputRateCnyPerMillion()).isEqualByComparingTo("2.00");
            assertThat(call.outputRateCnyPerMillion()).isEqualByComparingTo("8.00");
            assertThat(call.costCny()).isEqualByComparingTo("0.006000");
        });
        assertThat(ledger.summary(other).calls()).isZero();
        assertThat(ledger.summary(other).knownCostCny()).isNull();
        assertThat(ledger.recent(other)).isEmpty();

        var noPrice = new UsageLedger(jdbc, transactions, "", "", "", false, 100, "100");
        noPrice.modelSucceeded(noPrice.start(owner, task, "MODEL", "deepseek-chat", "reply"),
                20, 10);
        assertThat(noPrice.recent(owner)).anySatisfy(call -> {
            assertThat(call.inputTokens()).isEqualTo(20);
            assertThat(call.costCny()).isNull();
        });
        assertThat(noPrice.summary(owner).pendingReconciliation()).isEqualTo(3);
    }

    @Test
    void concurrentCallsCannotSpendTheSameUserQuota() {
        long owner = testUser();
        var limited = new UsageLedger(jdbc, transactions, "2", "8", "0.01", true, 1, "100");
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var first = attempt(limited, owner, ready, go);
        var second = attempt(limited, owner, ready, go);
        try {
            ready.await();
            go.countDown();
            assertThat(java.util.List.of(first.join(), second.join()))
                    .containsExactlyInAnyOrder("STARTED", "USER_CALL_LIMIT_REACHED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertThat(limited.recent(owner)).hasSize(1);
    }

    @Test
    void systemBudgetHoldsUnknownCostsAndBlocksOtherUsers() {
        long firstOwner = testUser();
        long secondOwner = testUser();
        var limited = new UsageLedger(jdbc, transactions, "2", "8", "0.01", true, 100, "0.01");
        String call = limited.start(firstOwner, UUID.randomUUID().toString(),
                "MCP_SEARCH", "tavily", "tavily_search");
        limited.failed(call, "MCP_UNAVAILABLE");
        assertThat(limited.budgetStatus(firstOwner).systemExposureCny())
                .isEqualByComparingTo("0.01");
        assertThat(limited.budgetStatus(firstOwner).pauseCode()).isEqualTo("BUDGET_LIMIT_REACHED");
        assertThat(limited.recent(firstOwner).getFirst().reservedCny()).isEqualByComparingTo("0.01");
        assertThatThrownBy(() -> limited.start(secondOwner, UUID.randomUUID().toString(),
                "MCP_SEARCH", "tavily", "tavily_search"))
                .isInstanceOf(UsageLedger.BudgetPaused.class).hasMessage("BUDGET_LIMIT_REACHED");
        assertThat(limited.recent(secondOwner)).isEmpty();
    }

    @Test
    void budgetRequiresVerifiedPricesBeforeCalls() {
        long owner = testUser();
        var noPrice = new UsageLedger(jdbc, transactions, "", "", "", true, 100, "100");
        assertThatThrownBy(() -> noPrice.start(owner, UUID.randomUUID().toString(),
                "MODEL", "deepseek-chat", "reply", 500))
                .isInstanceOf(UsageLedger.BudgetPaused.class).hasMessage("BUDGET_PRICE_UNCONFIGURED");
        assertThat(noPrice.budgetStatus(owner).pauseCode()).isEqualTo("BUDGET_PRICE_UNCONFIGURED");
        assertThat(noPrice.recent(owner)).isEmpty();
    }

    private long testUser() {
        String email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'unused', 'Budget Test')",
                email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static CompletableFuture<String> attempt(UsageLedger ledger, long owner,
                                                     CountDownLatch ready, CountDownLatch go) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try { go.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            try {
                ledger.start(owner, UUID.randomUUID().toString(),
                        "MCP_SEARCH", "tavily", "tavily_search");
                return "STARTED";
            } catch (UsageLedger.BudgetPaused paused) {
                return paused.getMessage();
            }
        });
    }
}
