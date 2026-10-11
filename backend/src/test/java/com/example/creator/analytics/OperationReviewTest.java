package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import com.example.creator.content.ContentService;
import com.example.creator.content.ContentValidator;
import com.example.creator.content.ScheduleService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OperationReviewTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private OperationReviewService reviews;
    @Autowired private ReviewFactsService facts;
    @Autowired private ImportService metrics;
    @Autowired private CommentImportService comments;
    @Autowired private ContentService content;
    @Autowired private ScheduleService schedules;

    @Test
    void coldStartPersistsExplicitInsufficientDataWithoutCallingAnUnconfiguredModel() {
        var fixture = fixture();
        var request = request(fixture);
        var report = reviews.generate(fixture.owner(), request);
        assertThat(report.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report.attempts()).isZero();
        assertThat(report.facts().metrics()).isEmpty();
        assertThat(report.facts().account().positioning()).isEqualTo("Java 面试与英语跟读");
        assertThat(report.facts().evidence().commentStatus()).isEqualTo("NO_COMMENT_SAMPLES");
        assertThat(report.facts().limitations()).anyMatch(text -> text.contains("缺少运营数据"));
        assertThat(report.advice().hypotheses()).isEmpty();
        assertThat(reviews.find(fixture.owner(), report.id())).contains(report);
        assertThat(reviews.generate(fixture.owner(), request)).isEqualTo(report);
        assertThat(reviews.list(fixture.owner(), fixture.account())).containsExactly(report);
    }

    @Test
    void collectsOnlyWindowObservationsAndMatchesPublicationToTheOriginalPlan() {
        var fixture = fixture();
        var csv = "accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + fixture.account() + ",0000123,2026-10-09T23:00:00Z,50,1,,DEMO\n"
                + fixture.account() + ",0000123,2026-10-10T10:00:00Z,100,5,0,DEMO\n"
                + fixture.account() + ",0000123,2026-10-10T11:00:00Z,150,,2,DEMO\n"
                + fixture.account() + ",0000123,2026-10-11T00:00:00Z,999,99,9,DEMO\n";
        metrics.confirm(fixture.owner(), csv.getBytes(StandardCharsets.UTF_8));
        var commentCsv = "accountId,videoId,commentId,text,publishedAt,capturedAt,source\n"
                + fixture.account() + ",0000123,c1,线程池问题,,2026-10-10T12:00:00Z,DEMO\n"
                + fixture.account() + ",0000123,c2,线程池窗口外评论,,2026-10-11T00:00:00Z,DEMO\n";
        comments.confirm(fixture.owner(), commentCsv.getBytes(StandardCharsets.UTF_8));
        var topic = content.saveTopic(fixture.owner(), fixture.account(), new ContentValidator.Topic("Java 面试",
                "线程池", "程序员", "面试", "问题", "提纲", List.of(), "待核实"), Set.of());
        var script = content.saveScript(fixture.owner(), topic, new ContentValidator.Script("演示脚本", "录屏", List.of()), Set.of());
        content.confirmScript(fixture.owner(), script, 1);
        var week = schedules.create(fixture.owner(), fixture.account(), LocalDate.of(2026, 10, 5));
        var assigned = schedules.replaceDraft(fixture.owner(), week.items().getFirst().id(), 1, topic, script);
        schedules.setPublication(fixture.owner(), assigned.id(), assigned.version(), true,
                "https://www.douyin.com/video/0000123", "0000123");
        var snapshot = facts.collect(fixture.owner(), request(fixture));
        assertThat(snapshot.metrics()).hasSize(1);
        var metric = snapshot.metrics().getFirst();
        assertThat(metric.observations()).isEqualTo(2);
        assertThat(metric.latest().views()).isEqualTo("150");
        assertThat(metric.latest().viewsDelta()).isEqualTo("50");
        assertThat(metric.latest().likesDelta()).isNull();
        assertThat(metric.previous().viewsDelta()).isNull();
        assertThat(snapshot.evidence().comments()).hasSize(1);
        assertThat(snapshot.dataUpdatedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(snapshot.publications()).hasSize(1);
        var publication = snapshot.publications().getFirst();
        assertThat(publication.workId()).isEqualTo(metric.workId());
        assertThat(publication.planId()).isEqualTo(week.id());
        assertThat(publication.itemId()).isEqualTo(assigned.id());
        assertThat(publication.scriptId()).isEqualTo(script);
    }

    @Test
    void rejectsForeignAccountsChangedReplayAndInvalidWindows() {
        var a = fixture();
        var b = fixture();
        var input = request(a);
        var report = reviews.generate(a.owner(), input);
        assertThat(reviews.find(b.owner(), report.id())).isEmpty();
        assertThatThrownBy(() -> reviews.list(b.owner(), a.account()))
                .isInstanceOf(OperationReviewService.ReviewFailure.class).hasMessage("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> reviews.generate(b.owner(), input))
                .isInstanceOf(OperationReviewService.ReviewFailure.class).hasMessage("ACCOUNT_NOT_FOUND");
        assertThatThrownBy(() -> reviews.generate(a.owner(), new OperationReview.Request(a.account(), "Redis",
                input.from(), input.to(), input.requestId())))
                .isInstanceOf(OperationReviewService.ReviewFailure.class).hasMessage("REVIEW_REQUEST_CONFLICT");
        assertThatThrownBy(() -> reviews.generate(a.owner(), new OperationReview.Request(a.account(), "Java",
                input.to(), input.from(), UUID.randomUUID().toString())))
                .isInstanceOf(OperationReviewService.ReviewFailure.class).hasMessage("INVALID_REVIEW_REQUEST");
        assertThatThrownBy(() -> reviews.generate(a.owner(), new OperationReview.Request(a.account(), "Java",
                input.from(), input.from().plusSeconds(32 * 86400), UUID.randomUUID().toString())))
                .isInstanceOf(OperationReviewService.ReviewFailure.class).hasMessage("INVALID_REVIEW_REQUEST");
    }

    @Test
    void limitsReportScopeBeforePaymentAndExcludesHistoricalCommentsFromColdStart() {
        var fixture = fixture();
        comments.confirm(fixture.owner(), ("accountId,videoId,commentId,text,publishedAt,capturedAt,source\n"
                + fixture.account() + ",v,c1,线程池,,2026-10-09T00:00:00Z,DEMO\n").getBytes(StandardCharsets.UTF_8));
        var report = reviews.generate(fixture.owner(), request(fixture));
        assertThat(report.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report.facts().evidence().comments()).isEmpty();
        var csv = new StringBuilder("accountId,videoId,capturedAt,views,likes,commentCount,source\n");
        for (int i = 0; i < 21; i++) csv.append(fixture.account()).append(",v,2026-10-10T10:00:00Z,1,0,,SOURCE_").append(i).append('\n');
        metrics.confirm(fixture.owner(), csv.toString().getBytes(StandardCharsets.UTF_8));
        var tooLarge = reviews.generate(fixture.owner(), request(fixture));
        assertThat(tooLarge.status()).isEqualTo("FAILED");
        assertThat(tooLarge.errorCode()).isEqualTo("REVIEW_SCOPE_TOO_LARGE");
        assertThat(tooLarge.attempts()).isZero();
    }

    private OperationReview.Request request(Fixture fixture) {
        return new OperationReview.Request(fixture.account(), "线程池", Instant.parse("2026-10-10T00:00:00Z"),
                Instant.parse("2026-10-11T00:00:00Z"), UUID.randomUUID().toString());
    }
    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '复盘测试')", email);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = accounts.create(owner, new AccountService.AccountInput("复盘测试", "程序员",
                "Java 面试与英语跟读", List.of("Java 面试", "英语跟读"), 3));
        return new Fixture(owner, account.id());
    }
    private record Fixture(long owner, String account) { }
}
