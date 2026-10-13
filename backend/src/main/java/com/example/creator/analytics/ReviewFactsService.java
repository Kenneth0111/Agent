package com.example.creator.analytics;

import com.example.creator.agent.AccountProfiles;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewFactsService {
    private final AccountProfiles accounts;
    private final ReviewService evidence;
    private final MetricsService metrics;
    private final JdbcTemplate jdbc;

    ReviewFactsService(AccountProfiles accounts, ReviewService evidence, MetricsService metrics, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.evidence = evidence;
        this.metrics = metrics;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OperationReview.Facts collect(long ownerId, OperationReview.Request request) {
        var profile = accounts.find(ownerId, request.accountId())
                .orElseThrow(() -> new OperationReviewService.ReviewFailure("ACCOUNT_NOT_FOUND"));
        var sources = jdbc.query("""
                SELECT DISTINCT w.id, w.video_id, s.source FROM video_works w
                JOIN video_metric_snapshots s ON s.work_id = w.id AND s.owner_id = w.owner_id
                WHERE w.owner_id = ? AND w.account_id = ? AND s.captured_at >= ? AND s.captured_at < ?
                ORDER BY w.id, s.source LIMIT 21
                """, (row, ignored) -> new Source(row.getString("id"), row.getString("video_id"), row.getString("source")),
                ownerId, request.accountId(), utc(request.from()), utc(request.to()));
        if (sources.size() > 20) throw new OperationReviewService.ReviewFailure("REVIEW_SCOPE_TOO_LARGE");
        var observations = new ArrayList<OperationReview.MetricEvidence>();
        for (var source : sources) {
            var trend = metrics.trend(ownerId, source.workId(), source.source(), request.from(), request.to());
            var points = trend.points();
            observations.add(new OperationReview.MetricEvidence("metric:" + (observations.size() + 1),
                    source.workId(), source.videoId(), source.source(), points.size(), trend.truncated(),
                    points.getFirst().capturedAt(), points.size() < 2 ? null : points.get(points.size() - 2), points.getLast()));
        }
        var samples = evidence.evidence(ownerId, request.accountId(), request.topic(), request.from(), request.to());
        var workIds = Stream.concat(observations.stream().map(OperationReview.MetricEvidence::workId),
                samples.comments().stream().map(ReviewService.CommentEvidence::workId)).distinct().toList();
        var publications = publications(ownerId, request.accountId(), workIds);
        var limitations = new ArrayList<String>();
        limitations.add("仅基于导入快照和关键词匹配评论；相关性不代表因果，不能代表完整平台监控。变化量只比较最近相邻观测，不是整个窗口或自然日新增量。");
        if (observations.isEmpty()) limitations.add("窗口内没有指标快照，不能评价播放或互动表现。");
        if (samples.comments().isEmpty()) limitations.add("窗口内该主题缺少评论依据；不能据此认定观众没有需求。");
        if (observations.isEmpty() && samples.comments().isEmpty()) limitations.add("当前复盘范围缺少运营数据依据（指标或主题匹配评论），先积累观测样本。");
        if (observations.stream().anyMatch(metric -> metric.observations() < 2)) limitations.add("部分作品来源只有一个快照，不能计算增长。");
        if (observations.stream().anyMatch(OperationReview.MetricEvidence::truncated)) limitations.add("部分来源最多使用窗口内最近 1000 条观测，观测数量只是返回窗口的数量。");
        if (observations.stream().anyMatch(metric -> metric.latest().correction())) limitations.add("最近累计值存在下降，应核对数据口径，不解释为新增。");
        if (observations.stream().anyMatch(metric -> metric.latest().views() == null || metric.latest().likes() == null
                || metric.latest().commentCount() == null)) limitations.add("存在未知累计指标，保持未知，不按零补齐。");
        if (samples.commentsTruncated()) limitations.add("评论只展示最近 20 条关键词匹配样本，不代表所有评论。");
        if (workIds.stream().anyMatch(id -> publications.stream().noneMatch(item -> item.workId().equals(id))))
            limitations.add("部分作品未匹配到原计划的发布记录，不推测关联。发布登记时间并非平台核实的实际发布时间。");
        var updated = Stream.concat(observations.stream().map(metric -> metric.latest().capturedAt()),
                samples.comments().stream().map(ReviewService.CommentEvidence::capturedAt)).max(Instant::compareTo).orElse(null);
        return new OperationReview.Facts(new OperationReview.Account(profile.name(), profile.audience(),
                profile.positioning(), profile.columns(), profile.weeklyTarget()), request.from(), request.to(), updated,
                List.copyOf(observations), samples, publications, List.copyOf(limitations));
    }

    private List<OperationReview.Publication> publications(long ownerId, String accountId, List<String> works) {
        if (works.isEmpty()) return List.of();
        var parameters = new ArrayList<Object>(List.of(ownerId, accountId));
        parameters.addAll(works);
        var results = jdbc.query("""
                SELECT w.id AS work_id, p.id AS plan_id, i.id AS item_id, i.topic_id, i.script_id,
                    i.publication_url, i.published_at, i.scheduled_date FROM video_works w
                JOIN content_schedule_weeks p ON p.account_id = w.account_id AND p.owner_id = w.owner_id
                JOIN content_schedule_items i ON i.week_id = p.id AND i.published_at IS NOT NULL
                    AND i.external_work_id COLLATE utf8mb4_0900_as_cs = w.video_id
                WHERE w.owner_id = ? AND w.account_id = ? AND w.id IN (
                """ + String.join(",", Collections.nCopies(works.size(), "?")) + ") ORDER BY i.id LIMIT 101",
                (row, ignored) -> new OperationReview.Publication(row.getString("work_id"), row.getString("plan_id"),
                        row.getString("item_id"), row.getString("topic_id"), row.getString("script_id"),
                        row.getString("publication_url"), row.getTimestamp("published_at").toInstant(),
                        row.getDate("scheduled_date").toLocalDate()), parameters.toArray());
        if (results.size() > 100) throw new OperationReviewService.ReviewFailure("REVIEW_SCOPE_TOO_LARGE");
        return results;
    }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private record Source(String workId, String videoId, String source) { }
}
