package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommentImportTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommentImportService imports;
    @Autowired private ImportService metricsImport;
    @Autowired private MetricsService metrics;
    @Autowired private ReviewService reviews;
    private static final String HEADER = "accountId,videoId,commentId,text,publishedAt,capturedAt,source\n";

    @Test
    void previewsRedactedSamplesWithoutWritingAndKeepsUnknownPublicationTime() {
        var fixture = fixture();
        var csv = csv(fixture, """
                ,0000123,c1,"线程池可以讲一下吗？联系13800138000或student@example.test",,2026-10-10T18:00:00+08:00,DEMO
                """);
        var preview = imports.preview(fixture.owner(), csv);
        assertThat(preview.canImport()).isTrue();
        assertThat(preview.rows()).hasSize(1);
        var row = preview.rows().getFirst();
        assertThat(row.status()).isEqualTo("READY");
        assertThat(row.redacted()).isTrue();
        assertThat(row.data().text()).isEqualTo("线程池可以讲一下吗？联系[手机号]或[邮箱]");
        assertThat(row.data().videoId()).isEqualTo("0000123");
        assertThat(row.data().publishedAt()).isNull();
        assertThat(row.data().capturedAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00Z"));
        assertThat(imports.preview(fixture.owner(), csv)).isEqualTo(preview);
    }

    @Test
    void confirmsOneStableSampleAndSkipsReimportsWithDifferentCaptureTimes() {
        var fixture = fixture();
        var csv = csv(fixture, ",video,c1,线程池可以讲一下吗？联系13800138000,,2026-10-10T10:00:00Z,DEMO\n");
        var first = imports.confirm(fixture.owner(), csv);
        assertThat(first.imported()).isEqualTo(1);
        assertThat(first.rows().getFirst().status()).isEqualTo("IMPORTED");
        assertThat(first.rows().getFirst().data().text()).doesNotContain("13800138000");
        assertThat(imports.preview(fixture.owner(), csv).rows().getFirst().status()).isEqualTo("DUPLICATE");
        var later = csv(fixture, ",video,c1,线程池可以讲一下吗？联系13900139000,,2026-10-11T10:00:00Z,DEMO\n");
        var repeated = imports.confirm(fixture.owner(), later);
        assertThat(repeated.imported()).isZero();
        assertThat(repeated.duplicates()).isEqualTo(1);
    }

    @Test
    void rejectsTextOrPublicationConflictsWithoutImportingOtherValidRows() {
        var fixture = fixture();
        imports.confirm(fixture.owner(), csv(fixture, ",video,c1,线程池,,2026-10-10T10:00:00Z,DEMO\n"));
        var conflict = csv(fixture, """
                ,video,c2,新评论,,2026-10-10T11:00:00Z,DEMO
                ,video,c1,线程池内容改变,,2026-10-10T11:00:00Z,DEMO
                """);
        var preview = imports.preview(fixture.owner(), conflict);
        assertThat(preview.canImport()).isFalse();
        assertThat(preview.rows()).extracting(CommentImportService.RowResult::errorCode)
                .containsExactly(null, "COMMENT_CONFLICT");
        assertThatThrownBy(() -> imports.confirm(fixture.owner(), conflict))
                .isInstanceOf(CommentImportService.ImportRejected.class);
        assertThat(imports.preview(fixture.owner(), conflict).rows().getFirst().status()).isEqualTo("READY");
        var publishedConflict = csv(fixture, ",video,c1,线程池,2026-10-10T09:00:00Z,2026-10-10T10:00:00Z,DEMO\n");
        assertThat(imports.preview(fixture.owner(), publishedConflict).rows().getFirst().errorCode())
                .isEqualTo("COMMENT_CONFLICT");
    }

    @Test
    void fileDuplicatesAreStableAndSourcesAndCommentIdsRemainCaseSensitive() {
        var fixture = fixture();
        var rows = """
                ,video,c1,线程池,2026-10-10T09:00:00Z,2026-10-10T10:00:00Z,DEMO
                ,video,c1,线程池,2026-10-10T17:00:00+08:00,2026-10-11T10:00:00Z,DEMO
                ,video,C1,线程池,2026-10-10T09:00:00Z,2026-10-10T10:00:00Z,DEMO
                ,video,c1,线程池,2026-10-10T09:00:00Z,2026-10-10T10:00:00Z,demo
                """;
        var confirmed = imports.confirm(fixture.owner(), csv(fixture, rows));
        assertThat(confirmed.imported()).isEqualTo(3);
        assertThat(confirmed.duplicates()).isEqualTo(1);
        var conflict = csv(fixture, """
                ,new-video,c1,线程池,,2026-10-10T10:00:00Z,DEMO
                ,new-video,c1,Redis,,2026-10-10T10:00:00Z,DEMO
                """);
        assertThat(imports.preview(fixture.owner(), conflict).rows().getLast().errorCode()).isEqualTo("COMMENT_CONFLICT");
    }

    @Test
    void invalidRowsAndForeignAccountsDoNotReturnCommentDataOrPartiallyImport() {
        var a = fixture();
        var b = fixture();
        var rows = """
                ,v,c1,Java,,2026-10-10T10:00:00Z,DEMO
                ,v,c2,,bad-time,2026-10-10T10:00:00Z,DEMO
                ,v,c3,Java,not-a-time,2026-10-10T10:00:00Z,DEMO
                ,v,c4,Java,,2026-10-10T10:00:00.1234567Z,DEMO
                """;
        var csv = csv(a, rows);
        var preview = imports.preview(a.owner(), csv);
        assertThat(preview.rows()).extracting(CommentImportService.RowResult::rowNumber).containsExactly(2, 3, 4, 5);
        assertThat(preview.rows()).extracting(CommentImportService.RowResult::errorCode)
                .containsExactly(null, "COMMENT_TEXT_INVALID", "PUBLISHED_AT_INVALID", "CAPTURED_AT_INVALID");
        assertThat(preview.rows().getLast().data()).isNull();
        assertThatThrownBy(() -> imports.confirm(a.owner(), csv)).isInstanceOf(CommentImportService.ImportRejected.class);
        assertThat(imports.preview(a.owner(), csv).rows().getFirst().status()).isEqualTo("READY");
        var foreign = imports.preview(b.owner(), csv);
        assertThat(foreign.rows().getFirst().errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(foreign.rows().getFirst().data()).isNull();
        assertThatThrownBy(() -> imports.confirm(b.owner(), csv)).isInstanceOf(CommentImportService.ImportRejected.class);
    }

    @Test
    void concurrentImportsProduceOneStoredSampleAndOneDuplicate() throws Exception {
        var fixture = fixture();
        var csv = csv(fixture, ",video,c1,Java,,2026-10-10T10:00:00Z,DEMO\n");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return imports.confirm(fixture.owner(), csv); });
            var b = pool.submit(() -> { start.await(); return imports.confirm(fixture.owner(), csv); });
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.imported() + second.imported()).isEqualTo(1);
            assertThat(first.duplicates() + second.duplicates()).isEqualTo(1);
        }
    }

    @Test
    void concurrentMetricAndCommentImportsReuseTheSameOwnedWork() throws Exception {
        var fixture = fixture();
        var commentCsv = csv(fixture, ",video,c1,Java,,2026-10-10T10:00:00Z,DEMO\n");
        var metricCsv = ("accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + fixture.account() + ",video,2026-10-10T10:00:00Z,100,0,,DEMO\n").getBytes(StandardCharsets.UTF_8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return imports.confirm(fixture.owner(), commentCsv); });
            var b = pool.submit(() -> { start.await(); return metricsImport.confirm(fixture.owner(), metricCsv); });
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).imported()).isEqualTo(1);
            assertThat(b.get(20, TimeUnit.SECONDS).imported()).isEqualTo(1);
        }
        var works = metrics.works(fixture.owner(), fixture.account());
        assertThat(works).hasSize(1);
        var evidence = reviews.evidence(fixture.owner(), fixture.account(), "Java");
        assertThat(evidence.comments().getFirst().workId()).isEqualTo(works.getFirst().id());
        assertThat(metrics.trend(fixture.owner(), works.getFirst().id(), "DEMO").points()).hasSize(1);
    }

    @Test
    void enforcesUtf8HeadersLimitsAndQuotedMultilineCommentText() {
        var fixture = fixture();
        var quoted = ("\uFEFF" + HEADER + fixture.account()
                + ",v,c1,\"线程池,\n请讲讲拒绝策略\",,2026-10-10T10:00:00Z,DEMO\n").getBytes(StandardCharsets.UTF_8);
        assertThat(imports.preview(fixture.owner(), quoted).rows().getFirst().data().text())
                .isEqualTo("线程池,\n请讲讲拒绝策略");
        assertThat(imports.preview(0, new byte[] {(byte) 0xc3, 0x28}).rows().getFirst().errorCode()).isEqualTo("CSV_UTF8_INVALID");
        assertThat(imports.preview(0, new byte[1_048_577]).rows().getFirst().errorCode()).isEqualTo("CSV_TOO_LARGE");
        assertThat(imports.preview(0, (HEADER + "\"unclosed").getBytes(StandardCharsets.UTF_8)).rows().getFirst().errorCode())
                .isEqualTo("CSV_INVALID");
        assertThat(imports.preview(0, (HEADER.strip() + ",author\n").getBytes(StandardCharsets.UTF_8)).rows().getFirst().errorCode())
                .isEqualTo("CSV_HEADER_INVALID");
        var longText = csv(fixture, ",v,c1," + "字".repeat(2001) + ",,2026-10-10T10:00:00Z,DEMO\n");
        assertThat(imports.preview(fixture.owner(), longText).rows().getFirst().errorCode()).isEqualTo("COMMENT_TEXT_INVALID");
        var tooMany = (HEADER + (fixture.account() + ",v,c1,Java,,2026-10-10T10:00:00Z,DEMO\n").repeat(1001))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(imports.preview(0, tooMany).rows().getFirst().errorCode()).isEqualTo("CSV_TOO_MANY_ROWS");
    }

    private byte[] csv(Fixture fixture, String rows) {
        return (HEADER + rows.lines().map(row -> fixture.account() + row + "\n")
                .reduce("", String::concat)).getBytes(StandardCharsets.UTF_8);
    }
    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '评论测试')", email);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_accounts (id, owner_id, name, audience, positioning, columns_json, weekly_target)
                VALUES (?, ?, '评论测试', '程序员', 'Java', '[]', 3)
                """, account, owner);
        return new Fixture(owner, account);
    }
    private record Fixture(long owner, String account) { }
}
