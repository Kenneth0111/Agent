package com.example.creator.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetricsService {
    private final JdbcTemplate jdbc;

    MetricsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<Work> works(long ownerId, String accountId) {
        requireOwned("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ?", ownerId, accountId);
        var works = new LinkedHashMap<String, Work>();
        jdbc.query("""
                SELECT DISTINCT w.id, w.video_id, s.source FROM video_works w
                LEFT JOIN video_metric_snapshots s ON s.work_id = w.id AND s.owner_id = w.owner_id
                WHERE w.owner_id = ? AND w.account_id = ? ORDER BY w.video_id, w.id, s.source
                """, result -> {
            var id = result.getString("id");
            var work = works.get(id);
            if (work == null) {
                work = new Work(id, result.getString("video_id"), new ArrayList<>());
                works.put(id, work);
            }
            var source = result.getString("source");
            if (source != null) work.sources().add(source);
        }, ownerId, accountId);
        return works.values().stream().map(work -> new Work(work.id(), work.videoId(), List.copyOf(work.sources()))).toList();
    }

    @Transactional(readOnly = true)
    public Trend trend(long ownerId, String workId, String source) {
        requireOwned("SELECT id FROM video_works WHERE owner_id = ? AND id = ?", ownerId, workId);
        var snapshots = new ArrayList<>(jdbc.query("""
                SELECT captured_at, views, likes, comment_count FROM video_metric_snapshots
                WHERE owner_id = ? AND work_id = ? AND source = ? ORDER BY captured_at DESC LIMIT 1001
                """, (result, ignored) -> new Snapshot(result.getObject("captured_at", LocalDateTime.class)
                        .toInstant(ZoneOffset.UTC), (Long) result.getObject("views"),
                        (Long) result.getObject("likes"), (Long) result.getObject("comment_count")), ownerId, workId, source));
        boolean truncated = snapshots.size() > 1000;
        if (truncated) snapshots.removeLast();
        var points = new ArrayList<Point>();
        Snapshot previous = null;
        for (var snapshot : snapshots.reversed()) {
            var viewsDelta = difference(snapshot.views(), previous == null ? null : previous.views());
            var likesDelta = difference(snapshot.likes(), previous == null ? null : previous.likes());
            var commentsDelta = difference(snapshot.comments(), previous == null ? null : previous.comments());
            points.add(new Point(snapshot.time(), previous == null ? null : previous.time(),
                    value(snapshot.views()), value(snapshot.likes()), value(snapshot.comments()),
                    value(viewsDelta), value(likesDelta), value(commentsDelta),
                    percent(snapshot.likes(), snapshot.views()), percent(snapshot.comments(), snapshot.views()),
                    negative(viewsDelta) || negative(likesDelta) || negative(commentsDelta)));
            previous = snapshot;
        }
        return new Trend(workId, source, truncated, List.copyOf(points));
    }

    private void requireOwned(String query, long ownerId, String id) {
        if (jdbc.queryForList(query, String.class, ownerId, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private static Long difference(Long current, Long previous) {
        // Non-negative signed BIGINT operands keep the difference within signed BIGINT range.
        return current == null || previous == null ? null : current - previous;
    }
    private static String value(Long value) { return value == null ? null : value.toString(); }
    private static boolean negative(Long value) { return value != null && value < 0; }
    private static String percent(Long numerator, Long views) {
        return numerator == null || views == null || views == 0 ? null : BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(views), 6, RoundingMode.HALF_UP).toPlainString();
    }
    private record Snapshot(Instant time, Long views, Long likes, Long comments) { }
    public record Work(String id, String videoId, List<String> sources) { }
    public record Trend(String workId, String source, boolean truncated, List<Point> points) { }
    public record Point(Instant capturedAt, Instant previousCapturedAt, String views, String likes,
                        String commentCount, String viewsDelta, String likesDelta, String commentCountDelta,
                        String likeViewPercent, String commentViewPercent, boolean correction) { }
}
