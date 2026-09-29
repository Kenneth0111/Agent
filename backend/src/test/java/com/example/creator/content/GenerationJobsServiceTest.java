package com.example.creator.content;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import java.time.LocalTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class GenerationJobsServiceTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ContentService content;
    @Autowired private GenerationJobsService jobs;
    @Autowired private GenerationJobScanner scanner;
    @Autowired private GenerationJobRecovery recovery;
    @MockitoBean private GenerationService generation;

    @BeforeEach
    void resetGenerator() {
        reset(generation);
    }

    @Test
    void manualAndScheduledTriggersShareTheGeneratorAndKeepOwnerAndSource() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("周任务账号", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "第一条"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "第二条"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "跟读"));
        var input = new GenerationJobsService.JobInput(1, LocalTime.of(9, 0), "Asia/Shanghai",
                true, "准备下周内容", slots);
        var job = jobs.save(owner, account.id(), input);
        assertThat(job.enabled()).isTrue();
        assertThat(jobs.find(owner, job.id())).contains(job);
        assertThat(jobs.find(stranger, job.id())).isEmpty();
        assertThatThrownBy(() -> jobs.save(stranger, account.id(), input))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> jobs.save(owner, account.id(),
                new GenerationJobsService.JobInput(1, LocalTime.of(9, 0), "Bad/Zone",
                        true, "准备下周内容", slots)))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("INVALID_JOB");

        var started = content.startRun(owner, account.id(), "WEEK_PLAN");
        var run = content.finishRun(owner, started.id(), "week-batch-1", 6);
        when(generation.generate(eq(owner), any())).thenReturn(run);
        var manual = jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL);
        var scheduled = jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.SCHEDULED);
        assertThat(manual.triggerSource()).isEqualTo("MANUAL");
        assertThat(scheduled.triggerSource()).isEqualTo("SCHEDULED");
        assertThat(manual.status()).isEqualTo("SUCCEEDED");
        assertThat(scheduled.status()).isEqualTo("SUCCEEDED");
        assertThat(manual.generationRunId()).isEqualTo(run.id());
        assertThat(jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.instruction'))
                FROM generation_job_triggers WHERE id = ?
                """, String.class, manual.id())).isEqualTo("准备下周内容");
        assertThat(jobs.triggers(owner, job.id())).hasSize(2);
        assertThatThrownBy(() -> jobs.trigger(stranger, job.id(), GenerationJobsService.TriggerSource.MANUAL))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("JOB_NOT_FOUND");
        var requests = org.mockito.ArgumentCaptor.forClass(GenerationService.Request.class);
        verify(generation, org.mockito.Mockito.times(2)).generate(eq(owner), requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.mode()).isEqualTo("WEEK_PLAN");
            assertThat(request.accountId()).isEqualTo(account.id());
            assertThat(request.instruction()).isEqualTo("准备下周内容");
            assertThat(request.slots()).isEqualTo(slots);
        });
        assertThat(requests.getAllValues().get(0).requestId())
                .isNotEqualTo(requests.getAllValues().get(1).requestId());

        var disabled = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", false, "准备下周内容", slots));
        assertThat(disabled.id()).isEqualTo(job.id());
        assertThatThrownBy(() -> jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.SCHEDULED))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("JOB_DISABLED");
    }

    @Test
    void concurrentScansCreateOnlyOneWeeklyRun() throws Exception {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("定时去重账号", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "第一条"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "第二条"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "跟读"));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", true, "准备下周内容", slots));
        scanner.scanDue(Instant.parse("2026-09-28T00:59:00Z"));
        assertThat(jobs.triggers(owner, job.id())).isEmpty();

        var started = content.startRun(owner, account.id(), "WEEK_PLAN");
        var run = content.finishRun(owner, started.id(), "week-batch-2", 6);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(generation.generate(eq(owner), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return run;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> scanner.scanDue(Instant.parse("2026-09-28T01:01:00Z")));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            scanner.scanDue(Instant.parse("2026-09-28T01:01:00Z"));
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            scanner.scanDue(Instant.parse("2026-09-28T01:02:00Z"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(jobs.triggers(owner, job.id())).hasSize(1);
        verify(generation, org.mockito.Mockito.times(1)).generate(eq(owner), any());
    }

    @Test
    void recoveryLinksFinishedRunsAndFlagsUnknownPaidCalls() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("恢复测试账号", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "第一条"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "第二条"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "跟读"));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", false, "准备下周内容", slots));
        var completed = content.startRun(owner, account.id(), "WEEK_PLAN");
        content.finishRun(owner, completed.id(), "saved-batch", 6);
        var completedRequest = UUID.randomUUID().toString();
        jdbc.update("UPDATE generation_runs SET request_id = ? WHERE id = ?", completedRequest, completed.id());
        var completedTrigger = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO generation_job_triggers (id, job_id, owner_id, account_id,
                                                     trigger_source, status, request_id, run_key)
                VALUES (?, ?, ?, ?, 'SCHEDULED', 'RUNNING', ?, ?)
                """, completedTrigger, job.id(), owner, account.id(), completedRequest, "2026-09-14");

        var unknown = content.startRun(owner, account.id(), "WEEK_PLAN");
        var unknownRequest = UUID.randomUUID().toString();
        jdbc.update("UPDATE generation_runs SET request_id = ? WHERE id = ?", unknownRequest, unknown.id());
        var unknownTrigger = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO generation_job_triggers (id, job_id, owner_id, account_id,
                                                     trigger_source, status, request_id, run_key)
                VALUES (?, ?, ?, ?, 'SCHEDULED', 'RUNNING', ?, ?)
                """, unknownTrigger, job.id(), owner, account.id(), unknownRequest, "2026-09-21");
        var missingTrigger = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO generation_job_triggers (id, job_id, owner_id, account_id,
                                                     trigger_source, status, request_id, run_key)
                VALUES (?, ?, ?, ?, 'SCHEDULED', 'RUNNING', ?, ?)
                """, missingTrigger, job.id(), owner, account.id(), UUID.randomUUID().toString(),
                "2026-09-07");

        recovery.reconcile();
        var runs = jobs.triggers(owner, job.id());
        assertThat(runs).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(completedTrigger);
            assertThat(item.status()).isEqualTo("SUCCEEDED");
            assertThat(item.generationRunId()).isEqualTo(completed.id());
        });
        assertThat(runs).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(unknownTrigger);
            assertThat(item.status()).isEqualTo("NEEDS_REVIEW");
            assertThat(item.errorCode()).isEqualTo("INTERRUPTED_UNKNOWN");
        });
        assertThat(runs).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(missingTrigger);
            assertThat(item.status()).isEqualTo("NEEDS_REVIEW");
            assertThat(item.generationRunId()).isNull();
        });
        assertThat(content.findRun(owner, unknown.id()).orElseThrow().status()).isEqualTo("NEEDS_REVIEW");
    }
}
