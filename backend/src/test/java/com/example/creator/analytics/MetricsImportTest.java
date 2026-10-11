package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import java.util.UUID;
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
