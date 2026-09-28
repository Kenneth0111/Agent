package com.example.creator.content;

import java.util.List;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles completed runs and surfaces interrupted, ambiguous paid calls for review. */
@Component
public class GenerationJobRecovery {
    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;

    GenerationJobRecovery(JdbcTemplate jdbc, RedissonClient redisson) {
        this.jdbc = jdbc;
        this.redisson = redisson;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reconcile();
    }

    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        var lock = redisson.getLock("generation:paid");
        if (!lock.tryLock()) return;
        try {
            List<Interrupted> interrupted = jdbc.query("""
                    SELECT t.id, t.owner_id, g.id AS run_id, g.status AS run_status, g.error_code
                    FROM generation_job_triggers t
                    LEFT JOIN generation_runs g ON g.owner_id = t.owner_id AND g.request_id = t.request_id
                    WHERE t.status = 'RUNNING'
                    """, (row, ignored) -> new Interrupted(row.getString("id"), row.getLong("owner_id"),
                    row.getString("run_id"), row.getString("run_status"), row.getString("error_code")));
            for (var item : interrupted) {
                if ("SUCCEEDED".equals(item.runStatus()) || "FAILED".equals(item.runStatus())) {
                    jdbc.update("""
                            UPDATE generation_job_triggers
                            SET status = ?, generation_run_id = ?, error_code = ?
                            WHERE id = ? AND owner_id = ? AND status = 'RUNNING'
                            """, item.runStatus(), item.runId(), item.errorCode(), item.id(), item.ownerId());
                } else {
                    jdbc.update("""
                            UPDATE generation_job_triggers SET status = 'NEEDS_REVIEW',
                                generation_run_id = ?, error_code = 'INTERRUPTED_UNKNOWN'
                            WHERE id = ? AND owner_id = ? AND status = 'RUNNING'
                            """, item.runId(), item.id(), item.ownerId());
                    if (item.runId() != null) jdbc.update("""
                            UPDATE generation_runs SET status = 'NEEDS_REVIEW',
                                error_code = 'INTERRUPTED_UNKNOWN'
                            WHERE id = ? AND owner_id = ? AND status = 'RUNNING'
                            """, item.runId(), item.ownerId());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private record Interrupted(String id, long ownerId, String runId, String runStatus,
                               String errorCode) { }
}
