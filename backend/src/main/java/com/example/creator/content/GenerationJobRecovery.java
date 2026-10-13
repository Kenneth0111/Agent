package com.example.creator.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles completed runs and surfaces interrupted, ambiguous paid calls for review. */
@Component
public class GenerationJobRecovery {
    private static final Logger log = LoggerFactory.getLogger(GenerationJobRecovery.class);
    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;
    private final GenerationService generation;
    private final ObjectMapper json;
    private final boolean autoResumeEnabled;

    GenerationJobRecovery(JdbcTemplate jdbc, RedissonClient redisson,
                          GenerationService generation, ObjectMapper json,
                          @Value("${creator.jobs.scheduler-enabled:false}") boolean autoResumeEnabled) {
        this.jdbc = jdbc;
        this.redisson = redisson;
        this.generation = generation;
        this.json = json;
        this.autoResumeEnabled = autoResumeEnabled;
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
                    SELECT t.id, t.owner_id, t.request_json, g.id AS run_id,
                           g.status AS run_status, g.error_code
                    FROM generation_job_triggers t
                    LEFT JOIN generation_runs g ON g.owner_id = t.owner_id AND g.request_id = t.request_id
                    WHERE t.status = 'RUNNING'
                    """, (row, ignored) -> new Interrupted(row.getString("id"), row.getLong("owner_id"),
                    row.getString("run_id"), row.getString("run_status"), row.getString("error_code"),
                    row.getString("request_json")));
            for (var item : interrupted) {
                ContentService.GenerationRun resumed = null;
                if (autoResumeEnabled && "RUNNING".equals(item.runStatus()) && item.requestJson() != null) {
                    try {
                        var request = json.readValue(item.requestJson(), GenerationService.Request.class);
                        resumed = generation.resumeWeek(item.ownerId(), item.runId(), request).orElse(null);
                    } catch (JsonProcessingException | RuntimeException invalid) {
                        log.warn("Generation trigger {} cannot resume safely: {}", item.id(),
                                invalid.getClass().getSimpleName());
                    }
                }
                if (resumed != null || "SUCCEEDED".equals(item.runStatus()) || "FAILED".equals(item.runStatus())) {
                    var status = resumed == null ? item.runStatus() : resumed.status();
                    var errorCode = resumed == null ? item.errorCode() : resumed.errorCode();
                    jdbc.update("""
                            UPDATE generation_job_triggers
                            SET status = ?, generation_run_id = ?, error_code = ?
                            WHERE id = ? AND owner_id = ? AND status = 'RUNNING'
                            """, status, item.runId(), errorCode, item.id(), item.ownerId());
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
                               String errorCode, String requestJson) { }
}
