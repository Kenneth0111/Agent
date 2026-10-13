package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MetricsServiceTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportService imports;
    @Autowired private MetricsService metrics;

    @Test
    void calculatesAdjacentObservationsInTimeOrderAndKeepsSourcesSeparate() {
        var fixture = fixture();
        importRows(fixture, """
                ,video,2026-10-10T12:00:00Z,140,9,3,MANUAL
                ,video,2026-10-10T10:00:00Z,100,5,0,MANUAL
                ,video,2026-10-10T11:00:00Z,150,10,2,MANUAL
                ,video,2026-10-10T11:00:00Z,999,20,4,OTHER
                """);
        var works = metrics.works(fixture.owner(), fixture.account());
        assertThat(works).hasSize(1);
        var work = works.getFirst();
        assertThat(work.videoId()).isEqualTo("video");
        assertThat(work.sources()).containsExactly("MANUAL", "OTHER");
        var trend = metrics.trend(fixture.owner(), work.id(), "MANUAL");
        assertThat(trend.points()).hasSize(3);
        var first = trend.points().getFirst();
        assertThat(first.views()).isEqualTo("100");
        assertThat(first.previousCapturedAt()).isNull();
        assertThat(first.viewsDelta()).isNull();
        assertThat(first.likeViewPercent()).isEqualTo("5.000000");
        var second = trend.points().get(1);
        assertThat(second.previousCapturedAt()).isEqualTo(first.capturedAt());
        assertThat(second.viewsDelta()).isEqualTo("50");
        assertThat(second.likesDelta()).isEqualTo("5");
        assertThat(second.commentCountDelta()).isEqualTo("2");
        assertThat(second.likeViewPercent()).isEqualTo("6.666667");
        assertThat(second.correction()).isFalse();
        assertThat(trend.points().getLast().viewsDelta()).isEqualTo("-10");
        assertThat(trend.points().getLast().correction()).isTrue();
        var other = metrics.trend(fixture.owner(), work.id(), "OTHER").points();
        assertThat(other).hasSize(1);
        assertThat(other.getFirst().viewsDelta()).isNull();
    }

    @Test
    void missingMetricsBreakTheComparisonAndZeroViewsDoNotProduceRatios() {
        var fixture = fixture();
        importRows(fixture, """
                ,video,2026-10-10T10:00:00Z,100,,0,MANUAL
                ,video,2026-10-10T11:00:00Z,150,0,2,MANUAL
                ,video,2026-10-10T12:00:00Z,,5,,MANUAL
                ,video,2026-10-10T13:00:00Z,0,10,3,MANUAL
                """);
        var work = metrics.works(fixture.owner(), fixture.account()).getFirst();
        var points = metrics.trend(fixture.owner(), work.id(), "MANUAL").points();
        assertThat(points.get(1).viewsDelta()).isEqualTo("50");
        assertThat(points.get(1).likesDelta()).isNull();
        assertThat(points.get(1).likeViewPercent()).isEqualTo("0.000000");
        assertThat(points.get(2).views()).isNull();
        assertThat(points.get(2).viewsDelta()).isNull();
        assertThat(points.get(2).commentCountDelta()).isNull();
        assertThat(points.getLast().viewsDelta()).isNull();
        assertThat(points.getLast().likesDelta()).isEqualTo("5");
        assertThat(points.getLast().commentCountDelta()).isNull();
        assertThat(points.getLast().likeViewPercent()).isNull();
        assertThat(points.getLast().commentViewPercent()).isNull();
        assertThat(points.getLast().correction()).isFalse();
    }

    @Test
    void preservesBigintPrecisionAndNegativeDifferencesWithoutOverflow() {
        var fixture = fixture();
        importRows(fixture, """
                ,large,2026-10-10T10:00:00.123456Z,9223372036854775807,0,,MANUAL
                ,large,2026-10-10T11:00:00Z,0,9223372036854775807,,MANUAL
                """);
        var work = metrics.works(fixture.owner(), fixture.account()).getFirst();
        var points = metrics.trend(fixture.owner(), work.id(), "MANUAL").points();
        assertThat(points.getFirst().views()).isEqualTo("9223372036854775807");
        assertThat(points.getFirst().capturedAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00.123456Z"));
        assertThat(points.getLast().viewsDelta()).isEqualTo("-9223372036854775807");
        assertThat(points.getLast().likesDelta()).isEqualTo("9223372036854775807");
        assertThat(points.getLast().correction()).isTrue();
    }

    @Test
    void hidesForeignAccountsAndWorksAndTreatsSourcesAsCaseSensitive() {
        var a = fixture();
        var b = fixture();
        assertThat(metrics.works(a.owner(), a.account())).isEmpty();
        importRows(a, ",video,2026-10-10T10:00:00Z,100,0,,MANUAL\n");
        var work = metrics.works(a.owner(), a.account()).getFirst();
        assertThat(metrics.works(b.owner(), b.account())).isEmpty();
        assertThat(metrics.trend(a.owner(), work.id(), "manual").points()).isEmpty();
        assertThatThrownBy(() -> metrics.works(b.owner(), a.account()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        invalid -> assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> metrics.trend(b.owner(), work.id(), "MANUAL"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        invalid -> assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void returnsALabeledBoundedWindowWithoutInventingItsFirstDelta() {
        var fixture = fixture();
        var start = Instant.parse("2026-10-10T00:00:00Z");
        var rows = new StringBuilder();
        for (int i = 0; i < 1000; i++) rows.append(",window,").append(start.plusSeconds(i))
                .append(',').append(i).append(",0,,MANUAL\n");
        importRows(fixture, rows.toString());
        importRows(fixture, ",window," + start.plusSeconds(1000) + ",1000,0,,MANUAL\n");
        var work = metrics.works(fixture.owner(), fixture.account()).getFirst();
        var trend = metrics.trend(fixture.owner(), work.id(), "MANUAL");
        assertThat(trend.truncated()).isTrue();
        assertThat(trend.points()).hasSize(1000);
        assertThat(trend.points().getFirst().views()).isEqualTo("1");
        assertThat(trend.points().getFirst().viewsDelta()).isNull();
        assertThat(trend.points().getLast().viewsDelta()).isEqualTo("1");
    }

    private void importRows(Fixture fixture, String rows) {
        var csv = "accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + rows.lines().map(row -> fixture.account() + row + "\n").reduce("", String::concat);
        imports.confirm(fixture.owner(), csv.getBytes(StandardCharsets.UTF_8));
    }

    private Fixture fixture() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, 'test-only', '趋势测试')", email);
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var account = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO content_accounts (id, owner_id, name, audience, positioning, columns_json, weekly_target)
                VALUES (?, ?, '趋势测试', '程序员', 'Java', '[]', 3)
                """, account, owner);
        return new Fixture(owner, account);
    }
    private record Fixture(long owner, String account) { }
}
