package com.example.creator.agent;

import com.example.creator.IntegrationTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"DEV_USER_PASSWORD=development-fixture-only",
        "creator.usage.model-input-cny-per-million=2.00",
        "creator.usage.model-output-cny-per-million=8.00",
        "creator.usage.search-cny-per-call=0.01"})
@ActiveProfiles("dev")
class UsageLedgerTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsageLedger ledger;

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

        var noPrice = new UsageLedger(jdbc, "", "", "");
        noPrice.modelSucceeded(noPrice.start(owner, task, "MODEL", "deepseek-chat", "reply"),
                20, 10);
        assertThat(noPrice.recent(owner)).anySatisfy(call -> {
            assertThat(call.inputTokens()).isEqualTo(20);
            assertThat(call.costCny()).isNull();
        });
        assertThat(noPrice.summary(owner).pendingReconciliation()).isEqualTo(3);
    }
}
