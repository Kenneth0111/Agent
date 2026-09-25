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
        assertThat(content.findTopic(owner, savedTopic)).get().extracting(ContentService.SavedTopic::topic)
                .isEqualTo(topic);
        assertThat(content.findScript(owner, savedScript)).get().extracting(ContentService.SavedScript::script)
                .isEqualTo(script);
        assertThat(content.findTopic(stranger, savedTopic)).isEmpty();
        assertThat(content.findScript(stranger, savedScript)).isEmpty();
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

        var staged = content.startRun(owner, account.id(), "SCRIPT");
        var nodeFailure = content.failRun(owner, staged.id(), "INSUFFICIENT_MATERIAL", "retrieveEvidence", 0);
        assertThat(content.findRun(owner, staged.id())).contains(nodeFailure);
        assertThat(nodeFailure.failedNode()).isEqualTo("retrieveEvidence");
        assertThat(content.findRun(stranger, staged.id())).isEmpty();
    }

    @Test
    void scriptRevisionKeepsOldVersionAndIsolatesUsersDraftsAndConversations() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("版本测试", "程序员",
                "Java 面试", List.of("Java 面试"), 2));
        var topic = new ContentValidator.Topic("Java 面试", "并发", "程序员", "可见性", "问题",
                "问题和简答", List.of("m-1"), "资料");
        var topicId = content.saveTopic(owner, account.id(), topic, Set.of("m-1"));
        var base = new ContentValidator.Script("原版：录屏解释可见性。", "录屏", List.of("m-1"));
        var firstId = content.saveScript(owner, topicId, base, Set.of("m-1"));
        var secondId = content.saveScript(owner, topicId, base, Set.of("m-1"));
        var mouth = new ContentValidator.Script("新版：口播解释可见性。", "正面口播", List.of("m-1"));

        var revised = content.reviseScript(owner, firstId, 1, null, "改成口播", mouth);
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.conversationId()).isNotBlank();
        assertThat(content.versions(owner, firstId)).hasSize(1)
                .first().extracting(ContentService.ScriptVersion::script).isEqualTo(base);
        assertThat(content.findScript(owner, secondId)).get().extracting(ContentService.SavedScript::script)
                .isEqualTo(base);
        assertThat(content.findScript(stranger, firstId)).isEmpty();
        assertThatThrownBy(() -> content.reviseScript(owner, firstId, 1, revised.conversationId(),
                "旧版本覆盖", mouth)).isInstanceOf(ContentService.VersionConflict.class);

        var shorter = new ContentValidator.Script("短版：volatile 保证可见性。", "口播", List.of("m-1"));
        var third = content.reviseScript(owner, firstId, 2, revised.conversationId(), "缩短时长", shorter);
        assertThat(third.version()).isEqualTo(3);
        assertThat(content.recentInstructions(owner, revised.conversationId()))
                .containsExactlyInAnyOrder("改成口播", "缩短时长");
        var otherSession = content.reviseScript(owner, secondId, 1, null, "语气更严肃", mouth);
        assertThat(otherSession.conversationId()).isNotEqualTo(revised.conversationId());
        assertThat(content.recentInstructions(owner, otherSession.conversationId()))
                .containsExactly("语气更严肃");
        assertThat(content.recentInstructions(stranger, revised.conversationId())).isEmpty();
        assertThatThrownBy(() -> content.reviseScript(stranger, firstId, 3, revised.conversationId(),
                "跨用户修改", shorter)).isInstanceOf(ContentValidator.ContentInvalid.class)
                .hasMessage("SCRIPT_NOT_FOUND");
    }

    @Test
    void weekDraftStoresThreeOwnedTopicScriptPairs() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("周草稿", "程序员",
                "Java 面试和英语跟读", List.of("Java 面试", "英语跟读"), 3));
        var items = new java.util.ArrayList<ContentService.WeekItem>();
        for (int index = 0; index < 3; index++) {
            var column = index == 2 ? "英语跟读" : "Java 面试";
            var source = "source-" + index;
            var topic = content.saveTopic(owner, account.id(), new ContentValidator.Topic(column,
                    "选题 " + index, "程序员", "角度", "开头", "提纲", List.of(source), "资料"), Set.of(source));
            var script = content.saveScript(owner, topic, new ContentValidator.Script(
                    "脚本 " + index, "口播", List.of(source)), Set.of(source));
            items.add(new ContentService.WeekItem(column, topic, script));
        }
        var weekId = content.saveWeekPlan(owner, account.id(), items);
        assertThat(content.findWeekPlan(owner, weekId)).get().extracting(ContentService.WeekPlan::items)
                .isEqualTo(items);
        assertThat(content.weekPlans(owner, account.id())).extracting(ContentService.WeekPlan::id)
                .contains(weekId);
        assertThat(content.findWeekPlan(stranger, weekId)).isEmpty();
        assertThatThrownBy(() -> content.saveWeekPlan(stranger, account.id(), items))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("ACCOUNT_NOT_FOUND");
    }
}
