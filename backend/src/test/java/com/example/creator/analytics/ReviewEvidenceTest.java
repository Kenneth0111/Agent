package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.material.MaterialService;
import com.example.creator.material.MaterialSearchService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReviewEvidenceTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReviewService reviews;
    @Autowired private MaterialService materials;
    @Autowired private CommentImportService imports;

    @Test
    void noCommentsIsExplicitWhileImportedPeerReferencesKeepTheirSource() {
        var fixture = fixture();
        var material = materials.create(fixture.owner(), new MaterialService.MaterialInput(
                "同行线程池选题参考", "同行参考", "https://example.test/peer-video", null,
                "演示参考：讲解线程池的拒绝策略。播放量未知。", "LINK", List.of(fixture.account())));
        var evidence = reviews.evidence(fixture.owner(), fixture.account(), "线程池");
        assertThat(evidence.commentStatus()).isEqualTo("NO_COMMENT_SAMPLES");
        assertThat(evidence.commentBasis()).contains("缺少评论依据");
        assertThat(evidence.comments()).isEmpty();
        assertThat(evidence.referenceMaterials().status()).isEqualTo("MATCHED");
        var reference = evidence.referenceMaterials().results().getFirst();
        assertThat(reference.materialId()).isEqualTo(material.id());
        assertThat(reference.sourceUrl()).isEqualTo("https://example.test/peer-video");
        assertThat(reference.snippet()).contains("播放量未知");
    }

    @Test
    void returnsCitableRedactedTopicSamplesAcrossWorksWithoutLeakingOtherAccounts() {
        var a = fixture();
        var b = fixture();
        importRows(a, """
                ,video-1,c1,线程池拒绝策略如何选？联系13800138000,,2026-10-10T10:00:00Z,DEMO_A
                ,video-1,c2,可以介绍 Redis 吗？,,2026-10-10T11:00:00Z,DEMO_A
                ,video-2,c3,100%线程池问题,,2026-10-10T12:00:00Z,DEMO_B
                """);
        importRows(b, ",foreign,c1,线程池其他账号内容,,2026-10-10T13:00:00Z,DEMO_A\n");
        var evidence = reviews.evidence(a.owner(), a.account(), "线程池");
        assertThat(evidence.commentStatus()).isEqualTo("MATCHED");
        assertThat(evidence.commentBasis()).contains("导入样本", "不代表全部");
        assertThat(evidence.comments()).hasSize(2);
        assertThat(evidence.comments()).extracting(ReviewService.CommentEvidence::videoId)
                .containsExactly("video-2", "video-1");
        assertThat(evidence.comments()).extracting(ReviewService.CommentEvidence::source)
                .containsExactly("DEMO_B", "DEMO_A");
        var sample = evidence.comments().getLast();
        assertThat(sample.citationId()).startsWith("comment:");
        assertThat(evidence.commentBasis()).contains("[" + sample.citationId() + "]");
        assertThat(sample.workId()).isNotBlank();
        assertThat(sample.text()).isEqualTo("线程池拒绝策略如何选？联系[手机号]");
        assertThat(sample.publishedAt()).isNull();
        assertThat(sample.capturedAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00Z"));
        assertThat(reviews.evidence(a.owner(), a.account(), "%").comments()).hasSize(1);
        assertThat(reviews.evidence(a.owner(), a.account(), "锁").commentStatus()).isEqualTo("NO_TOPIC_MATCH");
        assertThatThrownBy(() -> reviews.evidence(b.owner(), a.account(), "线程池"))
                .isInstanceOf(MaterialSearchService.SearchFailure.class).hasMessage("ACCOUNT_NOT_FOUND");
    }

    @Test
    void labelsTheLatestTwentyMatchingSamplesAndRetainsOriginalCaptureOnReimport() {
        var fixture = fixture();
        var start = Instant.parse("2026-10-10T10:00:00Z");
        var rows = new StringBuilder();
        for (int i = 0; i < 21; i++) rows.append(",v,c").append(i).append(",线程池问题,,")
                .append(start.plusSeconds(i)).append(",DEMO\n");
        importRows(fixture, rows.toString());
        importRows(fixture, ",v,c20,线程池问题,,2026-10-11T10:00:00Z,DEMO\n");
        var result = reviews.evidence(fixture.owner(), fixture.account(), "线程池");
        assertThat(result.commentsTruncated()).isTrue();
        assertThat(result.comments()).hasSize(20);
        assertThat(result.comments().getFirst().capturedAt()).isEqualTo(start.plusSeconds(20));
        assertThat(result.comments().getLast().capturedAt()).isEqualTo(start.plusSeconds(1));
        assertThat(result.commentBasis()).contains("20 条");
    }

    @Test
    void peerMaterialsFollowExistingUserAndAccountVisibilityAndQueriesAreBounded() {
        var a = fixture();
        var b = fixture();
        materials.create(b.owner(), new MaterialService.MaterialInput("线程池私人参考", "同行参考",
                "https://example.test/private", null, "线程池其他用户资料", "LINK", List.of(b.account())));
        var evidence = reviews.evidence(a.owner(), a.account(), "线程池");
        assertThat(evidence.referenceMaterials().results()).isEmpty();
        assertThat(evidence.referenceMaterials().status()).isEqualTo("INSUFFICIENT_MATERIAL");
        assertThatThrownBy(() -> reviews.evidence(a.owner(), a.account(), " "))
                .isInstanceOf(MaterialSearchService.SearchFailure.class).hasMessage("INVALID_QUERY");
        assertThatThrownBy(() -> reviews.evidence(a.owner(), a.account(), "字".repeat(101)))
                .isInstanceOf(MaterialSearchService.SearchFailure.class).hasMessage("INVALID_QUERY");
    }

    private void importRows(Fixture fixture, String rows) {
        var csv = "accountId,videoId,commentId,text,publishedAt,capturedAt,source\n"
                + rows.lines().map(row -> fixture.account() + row + "\n").reduce("", String::concat);
        imports.confirm(fixture.owner(), csv.getBytes(StandardCharsets.UTF_8));
    }

    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '需求证据测试')", email);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_accounts (id, owner_id, name, audience, positioning, columns_json, weekly_target)
                VALUES (?, ?, '需求证据测试', '程序员', 'Java', '[]', 3)
                """, account, owner);
        return new Fixture(owner, account);
    }
    private record Fixture(long owner, String account) { }
}
