package com.example.creator.content;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks enabled weekly jobs; the database key prevents a second run for the same local week. */
@Component
public class GenerationJobScanner {
    private static final Logger log = LoggerFactory.getLogger(GenerationJobScanner.class);
    private final JdbcTemplate jdbc;
    private final GenerationJobsService jobs;
    private final boolean enabled;

    GenerationJobScanner(JdbcTemplate jdbc, GenerationJobsService jobs,
                         @Value("${creator.jobs.scheduler-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledScan() {
        if (enabled) scanDue(Instant.now());
    }

    void scanDue(Instant now) {
        var targets = jdbc.query("SELECT id, owner_id FROM generation_jobs WHERE enabled = TRUE",
                (row, ignored) -> new JobRef(row.getString("id"), row.getLong("owner_id")));
        for (var target : targets) {
            var current = jobs.find(target.ownerId(), target.id());
            if (current.isEmpty()) continue;
            var job = current.get();
            var local = now.atZone(ZoneId.of(job.timeZone()));
            if (!job.enabled() || local.getDayOfWeek().getValue() != job.dayOfWeek()
                    || local.toLocalTime().isBefore(job.localTime())) continue;
            var runKey = local.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .toString();
            try {
                jobs.triggerScheduled(target.ownerId(), target.id(), runKey);
            } catch (RuntimeException failed) {
                log.warn("Scheduled generation job {} failed", target.id(), failed);
            }
        }
    }

    private record JobRef(String id, long ownerId) { }
}
