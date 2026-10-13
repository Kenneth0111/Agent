package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MetricsImportTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportService imports;

    @Test
    void reportsInvalidRowsAndRejectsTheWholeFileWithoutSavingTheValidRow() {
        var fixture = fixture();
        var csv = ("accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + fixture.account() + ",atomic-video,2026-10-10T10:00:00Z,1,0,,DEMO\n"
                + fixture.account() + ",negative-video,2026-10-10T10:00:00Z,-1,0,,DEMO\n"
                + fixture.account() + ",bad-time,not-a-time,1,0,,DEMO\n"
                + fixture.account() + ",missing-metrics,2026-10-10T10:00:00Z,,,,DEMO\n")
                .getBytes(StandardCharsets.UTF_8);
        var preview = imports.preview(fixture.owner(), csv);
        assertThat(preview.canImport()).isFalse();
        assertThat(preview.rows()).extracting(ImportService.RowResult::rowNumber).containsExactly(2, 3, 4, 5);
        assertThat(preview.rows()).extracting(ImportService.RowResult::errorCode)
                .containsExactly(null, "VIEWS_INVALID", "CAPTURED_AT_INVALID", "METRICS_MISSING");
        assertThatThrownBy(() -> imports.confirm(fixture.owner(), csv))
                .isInstanceOf(ImportService.ImportRejected.class);
        assertThat(imports.preview(fixture.owner(), csv).rows().getFirst().status()).isEqualTo("READY");
        assertThat(imports.preview(fixture.other(), csv).rows().getFirst().errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void reportsInFileDuplicatesAndConflictsIncludingZeroVersusUnknown() {
        var fixture = fixture();
        var header = "accountId,videoId,capturedAt,views,likes,commentCount,source\n";
        var row = fixture.account() + ",duplicate-video,2026-10-10T10:00:00Z,1,,,DEMO\n";
        var duplicate = (header + row + row.replace("10:00:00Z", "18:00:00+08:00"))
                .getBytes(StandardCharsets.UTF_8);
        var confirmed = imports.confirm(fixture.owner(), duplicate);
        assertThat(confirmed.imported()).isEqualTo(1);
        assertThat(confirmed.duplicates()).isEqualTo(1);
        var conflict = (header + row + row.replace(",1,,,", ",1,0,,")).getBytes(StandardCharsets.UTF_8);
        var preview = imports.preview(fixture.owner(), conflict);
        assertThat(preview.canImport()).isFalse();
        assertThat(preview.rows().getLast().errorCode()).isEqualTo("SNAPSHOT_CONFLICT");
        assertThatThrownBy(() -> imports.confirm(fixture.owner(), conflict))
                .isInstanceOf(ImportService.ImportRejected.class);
    }

    @Test
    void concurrentConfirmationProducesOneSnapshotAndOneDuplicate() throws Exception {
        var fixture = fixture();
        var csv = ("accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + fixture.account() + ",concurrent-video,2026-10-10T10:00:00Z,1,0,,DEMO\n")
                .getBytes(StandardCharsets.UTF_8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return imports.confirm(fixture.owner(), csv); });
            var second = pool.submit(() -> { start.await(); return imports.confirm(fixture.owner(), csv); });
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.imported() + b.imported()).isEqualTo(1);
            assertThat(a.duplicates() + b.duplicates()).isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidEncodingAndExcessiveFilesInsteadOfTruncatingInput() {
        assertThat(imports.preview(0, new byte[] {(byte) 0xc3, 0x28}).rows().getFirst().errorCode())
                .isEqualTo("CSV_UTF8_INVALID");
        assertThat(imports.preview(0, new byte[1_048_577]).rows().getFirst().errorCode())
                .isEqualTo("CSV_TOO_LARGE");
        var tooMany = ("accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + "00000000-0000-0000-0000-000000000001,v,2026-10-10T10:00:00Z,1,0,,DEMO\n".repeat(1001))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(imports.preview(0, tooMany).rows().getFirst().errorCode()).isEqualTo("CSV_TOO_MANY_ROWS");
    }

    @Test
    void malformedQuotingReturnsAFileErrorInsteadOfThrowingParserDetails() {
        var broken = "accountId,videoId,capturedAt,views,likes,commentCount,source\n\"unclosed";
        var preview = imports.preview(0, broken.getBytes(StandardCharsets.UTF_8));
        assertThat(preview.canImport()).isFalse();
        assertThat(preview.rows().getFirst().errorCode()).isEqualTo("CSV_INVALID");
    }

    @Test
    void confirmsOnceAndReportsDuplicatesOnReimport() {
        var fixture = fixture();
        var csv = ("accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + fixture.account() + ",import-video,2026-10-10T10:00:00Z,100,0,,DEMO_MANUAL\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(imports.confirm(fixture.owner(), csv).imported()).isEqualTo(1);
        var repeated = imports.confirm(fixture.owner(), csv);
        assertThat(repeated.imported()).isZero();
        assertThat(repeated.duplicates()).isEqualTo(1);
        assertThat(imports.preview(fixture.owner(), csv).rows().getFirst().status()).isEqualTo("DUPLICATE");
    }

    @Test
    void previewsQuotedUtf8DataWithoutWritingAndPreservesUnknownAndZero() {
        var fixture = fixture();
        var csv = ("\uFEFFaccountId,videoId,capturedAt,views,likes,commentCount,source\r\n"
                + fixture.account() + ",0000123,2026-10-10T18:00:00+08:00,,0,,\"演示,手工\"\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var preview = imports.preview(fixture.owner(), csv);
        assertThat(preview.canImport()).isTrue();
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().getFirst().status()).isEqualTo("READY");
        assertThat(preview.rows().getFirst().data().videoId()).isEqualTo("0000123");
        assertThat(preview.rows().getFirst().data().views()).isNull();
        assertThat(preview.rows().getFirst().data().likes()).isZero();
        assertThat(preview.rows().getFirst().data().source()).isEqualTo("演示,手工");
        assertThat(preview.rows().getFirst().data().capturedAt().toString()).isEqualTo("2026-10-10T10:00:00Z");
        assertThat(imports.preview(fixture.owner(), csv)).isEqualTo(preview);
    }

    @Test
    void distinguishesMissingFromZeroAndRejectsDuplicateOrInvalidSnapshots() {
        var fixture = fixture();
        var id = UUID.randomUUID().toString();
        snapshot(id, fixture, null, 0L, null);
        var row = jdbc.queryForMap("SELECT views, likes, comment_count FROM video_metric_snapshots WHERE id = ?", id);
        assertThat(row.get("views")).isNull();
        assertThat(row.get("likes")).isEqualTo(0L);
        assertThat(row.get("comment_count")).isNull();
        assertThatThrownBy(() -> snapshot(UUID.randomUUID().toString(), fixture, 5L, 0L, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_metric_snapshots (id, owner_id, work_id, captured_at, views, source)
                VALUES (?, ?, ?, '2026-10-10 11:00:00', -1, 'DEMO')
                """, UUID.randomUUID().toString(), fixture.owner(), fixture.work()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_video_snapshot_counts");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_metric_snapshots (id, owner_id, work_id, captured_at, source)
                VALUES (?, ?, ?, '2026-10-10 12:00:00', 'DEMO')
                """, UUID.randomUUID().toString(), fixture.owner(), fixture.work()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_video_snapshot_known");
    }

    @Test
    void enforcesOwnerReferencesAndStableCommentIdentity() {
        var fixture = fixture();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_works (id, owner_id, account_id, video_id)
                VALUES (?, ?, ?, 'other-video')
                """, UUID.randomUUID().toString(), fixture.other(), fixture.account()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO video_metric_snapshots (id, owner_id, work_id, captured_at, views, source)
                VALUES (?, ?, ?, '2026-10-10 10:00:00', 1, 'DEMO')
                """, UUID.randomUUID().toString(), fixture.other(), fixture.work()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var id = UUID.randomUUID().toString();
        comment(id, fixture.owner(), fixture.work());
        assertThat(jdbc.queryForObject("SELECT body FROM video_comments WHERE id = ?", String.class, id))
                .isEqualTo("演示评论：可以讲一下线程池吗？");
        assertThatThrownBy(() -> comment(UUID.randomUUID().toString(), fixture.owner(), fixture.work()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> comment(UUID.randomUUID().toString(), fixture.other(), fixture.work()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void snapshot(String id, Fixture fixture, Long views, Long likes, Long comments) {
        jdbc.update("""
                INSERT INTO video_metric_snapshots (id, owner_id, work_id, captured_at, views, likes, comment_count, source)
                VALUES (?, ?, ?, '2026-10-10 10:00:00', ?, ?, ?, 'DEMO')
                """, id, fixture.owner(), fixture.work(), views, likes, comments);
    }

    private void comment(String id, long owner, String work) {
        jdbc.update("""
                INSERT INTO video_comments (id, owner_id, work_id, comment_id, body, captured_at, source)
                VALUES (?, ?, ?, 'demo-comment-1', '演示评论：可以讲一下线程池吗？', '2026-10-10 10:00:00', 'DEMO')
                """, id, owner, work);
    }

    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        var otherEmail = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '指标测试')", email);
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '其他用户')", otherEmail);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var other = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, otherEmail);
        var account = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_accounts (id, owner_id, name, audience, positioning, columns_json, weekly_target)
                VALUES (?, ?, '指标演示账号', '程序员', 'Java', '[]', 3)
                """, account, owner);
        var work = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO video_works (id, owner_id, account_id, video_id) VALUES (?, ?, ?, 'demo-video-1')",
                work, owner, account);
        return new Fixture(owner, other, account, work);
    }

    private record Fixture(long owner, long other, String account, String work) { }
}
