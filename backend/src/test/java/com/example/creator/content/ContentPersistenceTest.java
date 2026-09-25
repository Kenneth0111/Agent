package com.example.creator.content;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class ContentPersistenceTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ContentService content;

    @Test
    void validTopicAndScriptPersistWhileForeignReferencesAreRejected() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("Java 内容", "程序员",
                "面试快问快答", List.of("Java 面试"), 2));
        var topic = new ContentValidator.Topic("Java 面试", "volatile", "程序员", "并发", "为什么？",
                "问题、简答、追问", List.of("source-1"), "来自笔记");
        var savedTopic = content.saveTopic(owner, account.id(), topic, Set.of("source-1"));
        var script = new ContentValidator.Script("volatile 保证可见性，不保证复合操作的原子性。",
                "展示线程示例", List.of("source-1"));
        var savedScript = content.saveScript(owner, savedTopic, script, Set.of("source-1"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_topics WHERE id = ? AND owner_id = ?",
                Integer.class, savedTopic, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM content_scripts WHERE id = ?",
                String.class, savedScript)).isEqualTo("DRAFT");
        assertThatThrownBy(() -> content.saveScript(stranger, savedTopic, script, Set.of("source-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("TOPIC_NOT_FOUND");
        assertThatThrownBy(() -> content.saveTopic(owner, account.id(), topic, Set.of("other-source")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SOURCE_NOT_IN_EVIDENCE");
    }

    @Test
    void invalidGenerationIsCorrectedAtMostOnceAndFailureIsQueryable() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("修正测试", "程序员",
                "Java 面试", List.of("Java 面试"), 2));
        var valid = """
                {"column":"Java 面试","title":"并发快问快答","audience":"程序员",
                 "angle":"可见性","hook":"volatile 有什么用？","outline":"问题、简答",
                 "sourceIds":["source-1"],"rationale":"依据本次检索"}
                """;
        var corrections = new AtomicInteger();
        var succeeded = content.generateTopic(owner, account.id(), "{}", Set.of("source-1"), bad -> {
            corrections.incrementAndGet();
            return valid;
        });
        assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.attempts()).isEqualTo(2);
        assertThat(content.findRun(owner, succeeded.id())).contains(succeeded);
        assertThat(content.findRun(stranger, succeeded.id())).isEmpty();
        assertThat(corrections.get()).isEqualTo(1);

        var failed = content.generateTopic(owner, account.id(), "{}", Set.of("source-1"), bad -> {
            corrections.incrementAndGet();
            return "{}";
        });
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("CONTENT_INVALID_AFTER_CORRECTION");
        assertThat(failed.resultId()).isNull();
        assertThat(content.findRun(owner, failed.id())).contains(failed);
        assertThat(corrections.get()).isEqualTo(2);

        var firstTry = content.generateTopic(owner, account.id(), valid, Set.of("source-1"), bad -> {
            throw new AssertionError("valid output must not be corrected");
        });
        assertThat(firstTry.attempts()).isEqualTo(1);
        assertThat(firstTry.status()).isEqualTo("SUCCEEDED");
    }
}
