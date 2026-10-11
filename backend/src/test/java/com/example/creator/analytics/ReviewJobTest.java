package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReviewJobTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ReviewJobStore jobs;
    @Autowired private ReviewJobScanner scanner;
    @Autowired private OperationReviewService reviews;
    @Autowired private ReviewStore reports;
    @Autowired private ReviewFactsService facts;
    @Autowired private RedissonClient redisson;
    private final List<Fixture> fixtures = new ArrayList<>();
    @AfterEach void disableFixtures() {
        for (var fixture : fixtures) jobs.findByAccount(fixture.owner(), fixture.account()).ifPresent(job ->
                jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(job.dayOfWeek(), job.localTime(), job.timeZone(), false, job.topic())));
    }

    @Test
    void dueColdStartUsesTheScheduledWindowAndOnlyCreatesOneReportPerLocalWeek() {
        var fixture = fixture();
        var job = jobs.save(fixture.owner(), fixture.account(),
                new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池"));
        scanner.scanDue(Instant.parse("2026-10-11T00:59:00Z"));
        assertThat(jobs.triggers(fixture.owner(), job.id())).isEmpty();
        scanner.scanDue(Instant.parse("2026-10-11T01:05:00Z"));
        scanner.scanDue(Instant.parse("2026-10-11T02:00:00Z"));
        var trigger = jobs.triggers(fixture.owner(), job.id()).getFirst();
        assertThat(trigger.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(trigger.request().from()).isEqualTo(Instant.parse("2026-10-04T01:00:00Z"));
        assertThat(trigger.request().to()).isEqualTo(Instant.parse("2026-10-11T01:00:00Z"));
        assertThat(trigger.runKey()).isEqualTo("2026-10-05");
        var report = reviews.find(fixture.owner(), trigger.reportId()).orElseThrow();
        assertThat(report.attempts()).isZero();
        assertThat(reviews.list(fixture.owner(), fixture.account())).containsExactly(report);
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(1);
    }

    @Test
    void disablingAndEditingDoNotReplaceAPreviouslyReservedWeeklyRequest() {
        var fixture = fixture();
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", false, "线程池"));
        var due = Instant.parse("2026-10-11T02:00:00Z");
        scanner.scanDue(due);
        assertThat(jobs.triggers(fixture.owner(), job.id())).isEmpty();
        jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池"));
        scanner.scanDue(due);
        var first = jobs.triggers(fixture.owner(), job.id()).getFirst();
        var edited = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(10, 0), "Asia/Shanghai", true, "Redis"));
        assertThat(edited.id()).isEqualTo(job.id());
        scanner.scanDue(due);
        assertThat(jobs.triggers(fixture.owner(), job.id())).containsExactly(first);
        scanner.scanDue(due.plusSeconds(7 * 86400));
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(2)
                .anySatisfy(trigger -> assertThat(trigger.request().topic()).isEqualTo("Redis"));
    }

    @Test
    void concurrentReservationsAreUniqueAndOwnershipAndInputAreEnforced() throws Exception {
        var fixture = fixture();
        var stranger = fixture();
        var input = new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池");
        var job = jobs.save(fixture.owner(), fixture.account(), input);
        assertThatThrownBy(() -> jobs.save(stranger.owner(), fixture.account(), input)).hasMessage("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> jobs.findByAccount(stranger.owner(), fixture.account())).hasMessage("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> jobs.triggers(stranger.owner(), job.id())).hasMessage("JOB_NOT_FOUND");
        assertThatThrownBy(() -> jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.NOON, "Bad/Zone", true, "Java")))
                .hasMessage("INVALID_REVIEW_JOB");
        assertThatThrownBy(() -> jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0, 1), "UTC", true, "Java")))
                .hasMessage("INVALID_REVIEW_JOB");
        var due = Instant.parse("2026-10-11T02:00:00Z");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> jobs.reserveDue(fixture.owner(), job.id(), due));
            var b = pool.submit(() -> jobs.reserveDue(fixture.owner(), job.id(), due));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(1);
        assertThat(jobs.reserveDue(stranger.owner(), job.id(), due)).isEmpty();
    }

    @Test
    void interruptedAndAlreadySavedReportsAreReconciledWithoutGeneratingAgain() {
        var a = fixture(); var b = fixture(); var c = fixture();
        var input = new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池");
        var ja = jobs.save(a.owner(), a.account(), input); var jb = jobs.save(b.owner(), b.account(), input); var jc = jobs.save(c.owner(), c.account(), input);
        var due = Instant.parse("2026-10-11T02:00:00Z");
        jobs.reserveDue(a.owner(), ja.id(), due).orElseThrow();
        var saved = jobs.reserveDue(b.owner(), jb.id(), due).orElseThrow();
        var completed = reviews.generate(b.owner(), saved.request());
        var pending = jobs.reserveDue(c.owner(), jc.id(), due).orElseThrow();
        var incomplete = reports.reserve(c.owner(), pending.request()).report();
        var persistedFacts = facts.collect(c.owner(), pending.request());
        reports.facts(c.owner(), incomplete.id(), persistedFacts);
        reports.attempt(c.owner(), incomplete.id(), 1);
        scanner.reconcile();
        assertThat(jobs.triggers(a.owner(), ja.id()).getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jobs.triggers(a.owner(), ja.id()).getFirst().reportId()).isNull();
        assertThat(jobs.triggers(b.owner(), jb.id()).getFirst().reportId()).isEqualTo(completed.id());
        assertThat(jobs.triggers(b.owner(), jb.id()).getFirst().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(jobs.triggers(c.owner(), jc.id()).getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(jobs.triggers(c.owner(), jc.id()).getFirst().reportId()).isEqualTo(incomplete.id());
        assertThat(reviews.find(c.owner(), incomplete.id()).orElseThrow().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(reviews.find(c.owner(), incomplete.id()).orElseThrow().errorCode()).isEqualTo("INTERRUPTED_UNKNOWN");
        scanner.scanDue(due.plusSeconds(3600));
        assertThat(reviews.list(a.owner(), a.account())).isEmpty();
        assertThat(reviews.list(c.owner(), c.account())).containsExactly(reviews.find(c.owner(), incomplete.id()).orElseThrow());
        assertThat(reviews.find(c.owner(), incomplete.id()).orElseThrow().attempts()).isEqualTo(1);
        assertThat(reviews.find(c.owner(), incomplete.id()).orElseThrow().facts()).isEqualTo(persistedFacts);
    }

    @Test
    void sevenLocalDaysIncludeTheDaylightSavingChangeAndBusyPaidWorkDefersReservation() throws Exception {
        var fixture = fixture();
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Europe/London", true, "Java"));
        var due = Instant.parse("2026-10-25T09:05:00Z");
        var lock = redisson.getLock("generation:paid");
        lock.lock();
        try (var pool = Executors.newSingleThreadExecutor()) { pool.submit(() -> scanner.scanDue(due)).get(10, TimeUnit.SECONDS); }
        finally { lock.unlock(); }
        assertThat(jobs.triggers(fixture.owner(), job.id())).isEmpty();
        scanner.scanDue(due);
        var trigger = jobs.triggers(fixture.owner(), job.id()).getFirst();
        assertThat(trigger.request().from()).isEqualTo(Instant.parse("2026-10-18T08:00:00Z"));
        assertThat(trigger.request().to()).isEqualTo(Instant.parse("2026-10-25T09:00:00Z"));
    }

    @Test
    void theEnabledScheduledEntryPointRunsButTheDefaultPausedOneDoesNot() {
        var fixture = fixture();
        var today = Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai"));
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(today.getDayOfWeek().getValue(),
                LocalTime.MIDNIGHT, "Asia/Shanghai", true, "Java"));
        assertThat(scanner.enabled()).isFalse();
        scanner.scheduledScan();
        assertThat(jobs.triggers(fixture.owner(), job.id())).isEmpty();
        var enabled = new ReviewJobScanner(jobs, reviews, redisson, true);
        enabled.scheduledScan(); enabled.scheduledScan();
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(1);
        assertThat(jobs.triggers(fixture.owner(), job.id()).getFirst().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void manualReviewsWaitForTheSharedLockSoRecoveryCannotInterruptLiveCalls() throws Exception {
        var fixture = fixture();
        var input = new OperationReview.Request(fixture.account(), "Java", Instant.parse("2026-10-04T01:00:00Z"),
                Instant.parse("2026-10-11T01:00:00Z"), UUID.randomUUID().toString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var lock = redisson.getLock("generation:paid"); lock.lock();
            java.util.concurrent.Future<OperationReview.Report> pending;
            try {
                var entered = new java.util.concurrent.CountDownLatch(1);
                pending = pool.submit(() -> { entered.countDown(); return reviews.generate(fixture.owner(), input); });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(reviews.list(fixture.owner(), fixture.account())).isEmpty();
            } finally { lock.unlock(); }
            assertThat(pending.get(10, TimeUnit.SECONDS).status()).isEqualTo("INSUFFICIENT_DATA");
        }
    }

    private Fixture fixture() {
        var email = UUID.randomUUID() + "@review-job.test";
        jdbc.update("INSERT INTO users (email,password_hash,display_name) VALUES (?, 'unused', '定时测试')", email);
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = accounts.create(owner, new AccountService.AccountInput("DEMO 定时复盘", "程序员", "Java", List.of("Java 面试"), 3));
        var fixture = new Fixture(owner, account.id());
        fixtures.add(fixture);
        return fixture;
    }
    private record Fixture(long owner, String account) { }
}
