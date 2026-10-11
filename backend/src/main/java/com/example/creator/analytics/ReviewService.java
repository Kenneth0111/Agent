package com.example.creator.analytics;

import com.example.creator.material.MaterialSearchService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
    private final JdbcTemplate jdbc;
    private final MaterialSearchService materials;

    ReviewService(JdbcTemplate jdbc, MaterialSearchService materials) {
        this.jdbc = jdbc;
        this.materials = materials;
    }

    @Transactional(readOnly = true)
    public Evidence evidence(long ownerId, String accountId, String topic) {
        return evidence(ownerId, accountId, topic, null, null);
    }

    @Transactional(readOnly = true)
    public Evidence evidence(long ownerId, String accountId, String topic, Instant from, Instant to) {
        // Existing material search validates account ownership and the bounded query first.
        var references = materials.search(ownerId, accountId, topic);
        var account = accountId.strip();
        var query = topic.strip();
        boolean hasSamples = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM video_comments c
                    JOIN video_works w ON w.id = c.work_id AND w.owner_id = c.owner_id
                    WHERE w.owner_id = ? AND w.account_id = ?
                        AND (? IS NULL OR c.captured_at >= ?) AND (? IS NULL OR c.captured_at < ?))
                """, Integer.class, ownerId, account, utc(from), utc(from), utc(to), utc(to)) == 1;
        var term = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var matches = jdbc.query("""
                SELECT c.id, c.work_id, w.video_id, c.body, c.published_at, c.captured_at, c.source
                FROM video_comments c JOIN video_works w ON w.id = c.work_id AND w.owner_id = c.owner_id
                WHERE w.owner_id = ? AND w.account_id = ? AND c.body LIKE ? ESCAPE '!'
                    AND (? IS NULL OR c.captured_at >= ?) AND (? IS NULL OR c.captured_at < ?)
                ORDER BY c.captured_at DESC, c.id DESC LIMIT 21
                """, (row, ignored) -> new CommentEvidence("comment:" + row.getString("id"), row.getString("work_id"),
                row.getString("video_id"), CommentText.redact(row.getString("body")),
                instant(row.getObject("published_at", LocalDateTime.class)),
                instant(row.getObject("captured_at", LocalDateTime.class)), row.getString("source")), ownerId, account, term,
                utc(from), utc(from), utc(to), utc(to));
        var selected = matches.stream().limit(20).toList();
        String status = !selected.isEmpty() ? "MATCHED" : hasSamples ? "NO_TOPIC_MATCH" : "NO_COMMENT_SAMPLES";
        String basis = switch (status) {
            case "MATCHED" -> "检索到 " + selected.size() + " 条提及该关键词的导入样本，可作为候选选题依据，不代表全部观众需求。引用："
                    + String.join("、", selected.stream().map(sample -> "[" + sample.citationId() + "]").toList());
            case "NO_TOPIC_MATCH" -> "已有评论样本，但没有主题匹配，缺少评论依据；不能据此断言观众没有该需求。";
            default -> "尚未导入评论样本，缺少评论依据。";
        };
        return new Evidence(account, query, status, basis, matches.size() > 20, selected, references);
    }

    private static Instant instant(LocalDateTime time) { return time == null ? null : time.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime utc(Instant time) { return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    public record CommentEvidence(String citationId, String workId, String videoId, String text,
                                  Instant publishedAt, Instant capturedAt, String source) implements java.io.Serializable { }
    public record Evidence(String accountId, String topic, String commentStatus, String commentBasis,
                           boolean commentsTruncated, List<CommentEvidence> comments,
                           MaterialSearchService.SearchResponse referenceMaterials) implements java.io.Serializable { }
}
