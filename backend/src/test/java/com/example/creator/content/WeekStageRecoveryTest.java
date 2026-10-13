package com.example.creator.content;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"DEV_USER_PASSWORD=development-fixture-only",
        "creator.jobs.scheduler-enabled=true"})
@ActiveProfiles("dev")
class WeekStageRecoveryTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ContentService content;
    @Autowired private GenerationJobsService jobs;
    @Autowired private GenerationJobRecovery recovery;
    @Autowired private ObjectMapper json;

    @Test
    void restartFinalizesCompletedWeekWithoutSavingItAgainAndFlagsUncertainStage() throws Exception {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("阶段恢复账号", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "第一条"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "第二条"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "跟读"));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", false, "准备下周内容", slots));
        var completedRequest = request(account.id(), slots);
        var completed = content.reserveWeekRun(owner, completedRequest).run();
        for (int index = 0; index < 7; index++) {
            String name = index == 6 ? "saveWeekPlan" : "slot" + (index / 2 + 1) + "/"
                    + (index % 2 == 0 ? "TOPICS" : "SCRIPT");
            content.startWeekStage(owner, completed.id(), index, name, completedRequest);
            content.completeWeekStage(owner, completed.id(), index,
                    index == 6 ? "saved-week-plan" : "saved-stage-" + index, index == 6 ? 0 : 1);
        }
        String completedTrigger = trigger(owner, account.id(), job.id(), completedRequest);

        var unknownRequest = request(account.id(), slots);
        var unknown = content.reserveWeekRun(owner, unknownRequest).run();
        content.startWeekStage(owner, unknown.id(), 0, "slot1/TOPICS", unknownRequest);
        String unknownTrigger = trigger(owner, account.id(), job.id(), unknownRequest);

        recovery.reconcile();

        assertThat(content.findRun(owner, completed.id()).orElseThrow().resultId())
                .isEqualTo("saved-week-plan");
        assertThat(jobs.triggers(owner, job.id())).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(completedTrigger);
            assertThat(item.status()).isEqualTo("SUCCEEDED");
            assertThat(item.generationRunId()).isEqualTo(completed.id());
        }).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(unknownTrigger);
            assertThat(item.status()).isEqualTo("NEEDS_REVIEW");
        });
        assertThat(content.findRun(owner, unknown.id()).orElseThrow().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(content.weekStages(owner, completed.id())).hasSize(7);
        assertThat(content.weekStages(owner, unknown.id())).hasSize(1);
    }

    private GenerationService.Request request(String accountId, List<GenerationService.WeekSlot> slots) {
        return new GenerationService.Request(accountId, "WEEK_PLAN", null, "准备下周内容", null,
                null, slots, UUID.randomUUID().toString());
    }

    private String trigger(long owner, String accountId, String jobId, GenerationService.Request request)
            throws Exception {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO generation_job_triggers (id, job_id, owner_id, account_id,
                                                     trigger_source, status, request_id, request_json)
                VALUES (?, ?, ?, ?, 'MANUAL', 'RUNNING', ?, ?)
                """, id, jobId, owner, accountId, request.requestId(), json.writeValueAsString(request));
        return id;
    }
}
