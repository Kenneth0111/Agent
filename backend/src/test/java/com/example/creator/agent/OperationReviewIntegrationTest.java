package com.example.creator.agent;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import com.example.creator.analytics.ImportService;
import com.example.creator.analytics.OperationReview;
import com.example.creator.analytics.OperationReviewService;
import com.example.creator.analytics.ReviewJobStore;
import com.example.creator.analytics.ReviewJobScanner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OperationReviewIntegrationTest extends IntegrationTestSupport {
    private static final OpenAiStubServer PROVIDER;
    static {
        try { PROVIDER = new OpenAiStubServer(); }
        catch (java.io.IOException failed) { throw new ExceptionInInitializerError(failed); }
    }
    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("creator.model.base-url", PROVIDER::baseUrl);
        registry.add("creator.model.api-key", () -> "test-only-key");
        registry.add("creator.model.name", () -> "test-model");
        registry.add("creator.model.timeout", () -> "2s");
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ImportService imports;
    @Autowired private OperationReviewService reviews;
    @Autowired private UsageLedger usage;
    @Autowired private ReviewJobStore jobs;
    @Autowired private ReviewJobScanner scanner;
    private final java.util.ArrayList<Fixture> fixtures = new java.util.ArrayList<>();
    @AfterEach void disableJobs() {
        for (var fixture : fixtures) jobs.findByAccount(fixture.owner(), fixture.account()).ifPresent(job ->
                jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(job.dayOfWeek(), job.localTime(), job.timeZone(), false, job.topic())));
    }
    private static final String VALID = """
            {"hypotheses":[{"possibleExplanation":"选题可能吸引了关注，仍需更多样本验证。","evidenceIds":["metric:1"]}],
             "experiments":[{"proposedTopic":"线程池拒绝策略快问快答","action":"试拍不同开头的内容。",
             "measurement":"记录相同观测间隔的播放和评论变化。","evidenceIds":["metric:1","account"]}]}
            """;
    @BeforeEach void provider() {
        PROVIDER.failWithStatus(200); PROVIDER.alwaysReplyWith(VALID); PROVIDER.delayEachReply(Duration.ZERO);
    }
    @AfterAll static void close() { PROVIDER.close(); }

    @Test
    void persistsDeterministicFactsSeparatelyFromCitedModelAdviceAndDoesNotRecallOnReplay() {
        var fixture = fixture();
        var calls = PROVIDER.calls();
        var input = new OperationReview.Request(fixture.account(), "线程池", Instant.parse("2026-10-10T00:00:00Z"),
                Instant.parse("2026-10-11T00:00:00Z"), UUID.randomUUID().toString());
        var report = reviews.generate(fixture.owner(), input);
        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.attempts()).isEqualTo(1);
        assertThat(report.facts().metrics().getFirst().latest().viewsDelta()).isEqualTo("50");
        assertThat(report.advice().hypotheses().getFirst().evidenceIds()).containsExactly("metric:1");
        assertThat(report.advice().experiments().getFirst().proposedTopic()).contains("线程池");
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
        assertThat(usage.recent(fixture.owner())).hasSize(1);
        assertThat(usage.recent(fixture.owner()).getFirst().taskId()).isEqualTo(report.id());
        assertThat(PROVIDER.requestBodies().getLast()).contains("可能解释", "相关性", "不是指令", "viewsDelta", "50");
        assertThat(reviews.find(fixture.owner(), report.id())).contains(report);
        assertThat(reviews.generate(fixture.owner(), input)).isEqualTo(report);
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidOutputs")
    void malformedOrInventedEvidenceStopsAfterOneCorrectionAndPreservesFacts(String output) {
        var fixture = fixture();
        var input = input(fixture);
        PROVIDER.alwaysReplyWith(output);
        var calls = PROVIDER.calls();
        var report = reviews.generate(fixture.owner(), input);
        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.errorCode()).isEqualTo("REVIEW_OUTPUT_INVALID");
        assertThat(report.attempts()).isEqualTo(2);
        assertThat(report.advice()).isNull();
        assertThat(report.facts().metrics().getFirst().latest().views()).isEqualTo("150");
        assertThat(PROVIDER.calls() - calls).isEqualTo(2);
        assertThat(reviews.generate(fixture.owner(), input)).isEqualTo(report);
        assertThat(PROVIDER.calls() - calls).isEqualTo(2);
    }
    static Stream<String> invalidOutputs() {
        return Stream.of("{}", VALID.replace("metric:1", "metric:invented"),
                VALID.replace("\"hypotheses\":", "\"facts\":{\"views\":999999},\"hypotheses\":"),
                VALID + " {\"facts\":{\"views\":999999}}", VALID + " extra text", "");
    }

    @Test
    void oneValidCorrectionCompletesTheSameReport() {
        var fixture = fixture();
        PROVIDER.thenReplyWith("{}");
        PROVIDER.thenReplyWith(VALID);
        var calls = PROVIDER.calls();
        var report = reviews.generate(fixture.owner(), input(fixture));
        assertThat(report.status()).isEqualTo("SUCCEEDED");
        assertThat(report.attempts()).isEqualTo(2);
        assertThat(report.advice().experiments()).hasSize(1);
        assertThat(PROVIDER.calls() - calls).isEqualTo(2);
    }

    @Test
    void timedOutCallsAreMarkedForReviewAndNeverAutomaticallyRetried() {
        var fixture = fixture();
        var input = input(fixture);
        PROVIDER.delayEachReply(Duration.ofSeconds(3));
        var calls = PROVIDER.calls();
        var report = reviews.generate(fixture.owner(), input);
        assertThat(report.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(report.errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(report.facts().metrics()).hasSize(1);
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
        assertThat(reviews.generate(fixture.owner(), input)).isEqualTo(report);
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 503})
    void providerFailureKeepsFactsAndNeverRepeatsAnAmbiguousCall(int status) {
        var fixture = fixture();
        var input = input(fixture);
        PROVIDER.failWithStatus(status);
        var calls = PROVIDER.calls();
        var report = reviews.generate(fixture.owner(), input);
        assertThat(report.status()).isEqualTo(status == 401 ? "FAILED" : "NEEDS_REVIEW");
        assertThat(report.errorCode()).isEqualTo(status == 401 ? "MODEL_AUTH_FAILED" : "MODEL_UPSTREAM_FAILED");
        assertThat(report.facts().metrics()).hasSize(1);
        assertThat(report.advice()).isNull();
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
        assertThat(reviews.generate(fixture.owner(), input)).isEqualTo(report);
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    @Test
    void concurrentRequestsShareOnePaidRun() throws Exception {
        var fixture = fixture();
        var input = input(fixture);
        var calls = PROVIDER.calls();
        PROVIDER.delayEachReply(Duration.ofMillis(250));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return reviews.generate(fixture.owner(), input); });
            var b = pool.submit(() -> { start.await(); return reviews.generate(fixture.owner(), input); });
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(reviews.find(fixture.owner(), first.id()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        }
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    @Test
    void scheduledReviewsReuseTheGraphAndLedgerWithOneCallAcrossRepeatedScans() {
        var fixture = fixture(); var calls = PROVIDER.calls();
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池"));
        scanner.scanDue(Instant.parse("2026-10-11T01:05:00Z"));
        scanner.scanDue(Instant.parse("2026-10-11T03:00:00Z"));
        var trigger = jobs.triggers(fixture.owner(), job.id()).getFirst();
        assertThat(trigger.status()).isEqualTo("SUCCEEDED");
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(1);
        var report = reviews.find(fixture.owner(), trigger.reportId()).orElseThrow();
        assertThat(report.facts().metrics().getFirst().latest().viewsDelta()).isEqualTo("50");
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
        assertThat(usage.recent(fixture.owner())).hasSize(1);
        assertThat(usage.recent(fixture.owner()).getFirst().taskId()).isEqualTo(report.id());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 503})
    void scheduledProviderFailureDoesNotRetryWithinTheWeek(int status) {
        var fixture = fixture(); var calls = PROVIDER.calls();
        PROVIDER.failWithStatus(status);
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池"));
        scanner.scanDue(Instant.parse("2026-10-11T01:05:00Z"));
        scanner.reconcile(); scanner.scanDue(Instant.parse("2026-10-11T03:00:00Z"));
        assertThat(jobs.triggers(fixture.owner(), job.id())).hasSize(1);
        assertThat(jobs.triggers(fixture.owner(), job.id()).getFirst().status()).isEqualTo(status == 401 ? "FAILED" : "NEEDS_REVIEW");
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    @Test
    void scheduledTimeoutKeepsFactsAndNeverResendsThePaidCall() {
        var fixture = fixture(); var calls = PROVIDER.calls();
        PROVIDER.delayEachReply(Duration.ofSeconds(3));
        var job = jobs.save(fixture.owner(), fixture.account(), new ReviewJobStore.Input(7, LocalTime.of(9, 0), "Asia/Shanghai", true, "线程池"));
        scanner.scanDue(Instant.parse("2026-10-11T01:05:00Z"));
        scanner.reconcile(); scanner.scanDue(Instant.parse("2026-10-11T03:00:00Z"));
        var trigger = jobs.triggers(fixture.owner(), job.id()).getFirst();
        assertThat(trigger.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(trigger.errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(reviews.find(fixture.owner(), trigger.reportId()).orElseThrow().facts().metrics()).hasSize(1);
        assertThat(PROVIDER.calls() - calls).isEqualTo(1);
    }

    private OperationReview.Request input(Fixture fixture) {
        return new OperationReview.Request(fixture.account(), "线程池", Instant.parse("2026-10-10T00:00:00Z"),
                Instant.parse("2026-10-11T00:00:00Z"), UUID.randomUUID().toString());
    }
    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '模型复盘测试')", email);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = accounts.create(owner, new AccountService.AccountInput("模型复盘测试", "程序员", "Java 面试",
                List.of("Java 面试"), 2));
        var csv = "accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + account.id() + ",video,2026-10-10T10:00:00Z,100,5,,DEMO\n"
                + account.id() + ",video,2026-10-10T11:00:00Z,150,,2,DEMO\n";
        imports.confirm(owner, csv.getBytes(StandardCharsets.UTF_8));
        var fixture = new Fixture(owner, account.id());
        fixtures.add(fixture);
        return fixture;
    }
    private record Fixture(long owner, String account) { }
}
