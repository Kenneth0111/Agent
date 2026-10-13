package com.example.creator.agent;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import com.example.creator.content.ContentService;
import com.example.creator.content.ContentValidator;
import com.example.creator.content.GenerationJobScanner;
import com.example.creator.content.GenerationJobRecovery;
import com.example.creator.content.GenerationJobsService;
import com.example.creator.content.GenerationService;
import com.example.creator.material.MaterialService;
import com.example.creator.notification.EmailSender;
import com.example.creator.notification.FeishuSender;
import com.example.creator.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Actual scheduler, graph, HTTP model and database; external notification senders are test doubles. */
@SpringBootTest(properties = {"DEV_USER_PASSWORD=development-fixture-only",
        "NOTIFICATION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("dev")
class ScheduledNotificationIntegrationTest extends IntegrationTestSupport {
    private static final OpenAiStubServer PROVIDER;
    private static final Instant DUE = Instant.parse("2026-10-16T01:01:00Z");
    static {
        try { PROVIDER = new OpenAiStubServer(); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry properties) {
        properties.add("creator.model.base-url", PROVIDER::baseUrl);
        properties.add("creator.model.api-key", () -> "test-only-key");
        properties.add("creator.model.name", () -> "test-model");
        properties.add("creator.model.timeout", () -> "2s");
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AccountService accounts;
    @Autowired private MaterialService materials;
    @Autowired private ContentService content;
    @Autowired private GenerationJobsService jobs;
    @Autowired private GenerationJobScanner scanner;
    @Autowired private GenerationJobRecovery recovery;
    @MockitoSpyBean private NotificationService notifications;
    @MockitoSpyBean private UsageLedger usage;
    @MockitoBean private EmailSender emails;
    @MockitoBean private FeishuSender feishu;
    private Fixture current;

    @BeforeEach void resetProvider() { PROVIDER.failWithStatus(200); PROVIDER.alwaysReplyWith("{}"); }
    @AfterEach void pauseJob() {
        if (current == null) return;
        notifications.save(current.owner(), new NotificationService.SettingsInput(null, null, false,
                null, null, null, null, false, null, null));
        var job = jobs.find(current.owner(), current.job()).orElseThrow();
        jobs.save(current.owner(), job.accountId(), new GenerationJobsService.JobInput(job.dayOfWeek(),
                job.localTime(), job.timeZone(), false, job.instruction(), job.slots()));
    }
    @AfterAll static void closeProvider() { PROVIDER.close(); }

    @Test
    void dueJobSavesThreeDraftsAndQueuesBothChannelsEvenWhenEmailDispatchFails() throws Exception {
        var fixture = fixture();
        var existingTopic = content.saveTopic(fixture.owner(), fixture.account(),
                new ContentValidator.Topic("Java 面试", "已确认选题", "程序员", "角度", "开头", "提纲",
                        List.of(fixture.sources().getFirst()), "依据"), Set.copyOf(fixture.sources()));
        var existingScript = content.saveScript(fixture.owner(), existingTopic,
                new ContentValidator.Script("已确认内容必须保持不变", "口播", List.of(fixture.sources().getFirst())),
                Set.copyOf(fixture.sources()));
        var confirmed = content.confirmScript(fixture.owner(), existingScript, 1);
        prepareWeek(fixture);
        doThrow(new IllegalStateException("local mail dispatch failure")).when(emails)
                .deliverPending(eq(fixture.owner()), anyString());
        int calls = PROVIDER.calls();
        scan(DUE.minusSeconds(120));
        assertThat(jobs.triggers(fixture.owner(), fixture.job())).isEmpty();
        scan(DUE);
        var triggers = jobs.triggers(fixture.owner(), fixture.job());
        assertThat(triggers).hasSize(1);
        var trigger = triggers.getFirst();
        assertThat(trigger.triggerSource()).isEqualTo("SCHEDULED");
        assertThat(trigger.status()).isEqualTo("SUCCEEDED");
        var run = content.findRun(fixture.owner(), trigger.generationRunId()).orElseThrow();
        var batch = content.findWeekPlan(fixture.owner(), run.resultId()).orElseThrow();
        assertThat(batch.items().stream().map(ContentService.WeekItem::column))
                .containsExactly("Java 面试", "Java 面试", "英语跟读");
        assertThat(content.findScript(fixture.owner(), existingScript)).contains(confirmed);
        assertThat(notifications.deliveries(fixture.owner()).stream().map(NotificationService.DeliveryView::channel))
                .containsExactlyInAnyOrder("EMAIL", "FEISHU");
        verify(emails).deliverPending(fixture.owner(), trigger.id());
        verify(feishu).deliverPending(fixture.owner(), trigger.id());
        scan(DUE.plusSeconds(60));
        assertThat(jobs.triggers(fixture.owner(), fixture.job())).hasSize(1);
        assertThat(PROVIDER.calls() - calls).isEqualTo(6);
        assertThat(usage.recent(fixture.owner())).hasSize(6);
        verifyNoMoreInteractions(emails, feishu);
    }

    @Test
    void enqueueFailureRollsBackCompletionAndRecoveryQueuesWithoutRecallingTheModel() throws Exception {
        var fixture = fixture();
        prepareWeek(fixture);
        org.mockito.stubbing.Answer<Object> failAfterQueue = call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after queue insertion");
        };
        doAnswer(failAfterQueue).doAnswer(failAfterQueue).doCallRealMethod().when(notifications)
                .enqueue(eq(fixture.owner()), anyString(), anyString());
        int calls = PROVIDER.calls();
        scan(DUE);
        assertThat(jobs.triggers(fixture.owner(), fixture.job()).getFirst().status()).isEqualTo("RUNNING");
        assertThat(notifications.deliveries(fixture.owner())).isEmpty();
        verifyNoInteractions(emails, feishu);
        recovery.reconcile();
        assertThat(jobs.triggers(fixture.owner(), fixture.job()).getFirst().status()).isEqualTo("RUNNING");
        assertThat(notifications.deliveries(fixture.owner())).isEmpty();
        recovery.reconcile();
        assertThat(jobs.triggers(fixture.owner(), fixture.job()).getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(notifications.deliveries(fixture.owner()).stream().map(NotificationService.DeliveryView::channel))
                .containsExactlyInAnyOrder("EMAIL", "FEISHU");
        recovery.reconcile();
        assertThat(notifications.deliveries(fixture.owner())).hasSize(2);
        assertThat(PROVIDER.calls() - calls).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_UPSTREAM_FAILED", "USER_CALL_LIMIT_REACHED"})
    void failedGenerationDoesNotQueueSuccessNotifications(String code) {
        var fixture = fixture();
        if (code.equals("MODEL_UPSTREAM_FAILED")) PROVIDER.failWithStatus(503);
        else doThrow(new UsageLedger.BudgetPaused(code)).when(usage)
                .start(eq(fixture.owner()), anyString(), eq("MODEL"), anyString(), anyString(), anyInt());
        int calls = PROVIDER.calls();
        scan(DUE);
        var trigger = jobs.triggers(fixture.owner(), fixture.job()).getFirst();
        assertThat(trigger.status()).isEqualTo("FAILED");
        assertThat(trigger.errorCode()).isEqualTo(code);
        var run = content.findRun(fixture.owner(), trigger.generationRunId()).orElseThrow();
        assertThat(run.resultId()).isNull();
        assertThat(run.failedNode()).isEqualTo("slot1/generateDraft");
        assertThat(notifications.deliveries(fixture.owner())).isEmpty();
        scan(DUE.plusSeconds(60));
        assertThat(jobs.triggers(fixture.owner(), fixture.job())).hasSize(1);
        assertThat(PROVIDER.calls() - calls).isEqualTo(code.equals("MODEL_UPSTREAM_FAILED") ? 1 : 0);
        verifyNoInteractions(emails, feishu);
    }

    private void prepareWeek(Fixture fixture) throws Exception {
        for (int i = 0; i < 3; i++) {
            var column = i == 2 ? "英语跟读" : "Java 面试";
            PROVIDER.thenReplyWith(json.writeValueAsString(new ContentValidator.Topic(column,
                    "演练选题 " + i, "程序员", "角度", "开头", "提纲", List.of(fixture.sources().get(i)), "资料依据")));
            PROVIDER.thenReplyWith(json.writeValueAsString(new ContentValidator.Script("演练口播 " + i,
                    "录屏和口播", List.of(fixture.sources().get(i)))));
        }
    }

    private void scan(Instant now) {
        // Drive the production scanner with a fixed clock while automatic wall-clock scans stay disabled.
        ReflectionTestUtils.invokeMethod(scanner, "scanDue", now);
    }

    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)", email,
                "unused-test-password-hash", "通知演练");
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = accounts.create(owner, new AccountService.AccountInput("自动备稿演练", "程序员",
                "Java 与原创英语", List.of("Java 面试", "英语跟读"), 3));
        var sources = new ArrayList<String>();
        for (int i = 0; i < 3; i++) sources.add(materials.create(owner, new MaterialService.MaterialInput(
                "演练资料 " + i, "参考", null, null, "这是一份仅供自动流程验证的演练资料。", "TEXT", List.of(account.id()))).id());
        notifications.save(owner, new NotificationService.SettingsInput("owner@example.test", "sender@example.test", true,
                "smtp.example.test", 587, "test-user", "test-secret", true,
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-token", "test-signature"));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of(sources.get(0)), "第一条"),
                new GenerationService.WeekSlot("Java 面试", List.of(sources.get(1)), "第二条"),
                new GenerationService.WeekSlot("英语跟读", List.of(sources.get(2)), "第三条"));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(5, LocalTime.of(9, 0),
                "Asia/Shanghai", true, "演练一周三条", slots));
        current = new Fixture(owner, account.id(), job.id(), sources);
        return current;
    }

    private record Fixture(long owner, String account, String job, List<String> sources) { }
}
