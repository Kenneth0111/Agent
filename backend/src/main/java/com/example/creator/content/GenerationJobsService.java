package com.example.creator.content;

import com.example.creator.agent.AccountProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists weekly generation configuration and source-labelled trigger runs. */
@Service
public class GenerationJobsService {
    private final JdbcTemplate jdbc;
    private final AccountProfiles accounts;
    private final GenerationService generation;
    private final ObjectMapper json;
    private final RedissonClient redisson;

    GenerationJobsService(JdbcTemplate jdbc, AccountProfiles accounts, GenerationService generation,
                          ObjectMapper json, RedissonClient redisson) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.generation = generation;
        this.json = json;
        this.redisson = redisson;
    }

    @Transactional
    public Job save(long ownerId, String accountId, JobInput input) {
        if (accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        validate(input);
        String slots;
        try {
            slots = json.writeValueAsString(input.slots());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
        jdbc.update("""
                INSERT INTO generation_jobs (id, owner_id, account_id, day_of_week, local_time,
                                             time_zone, enabled, instruction, slots_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE day_of_week = ?, local_time = ?, time_zone = ?,
                                        enabled = ?, instruction = ?, slots_json = ?
                """, UUID.randomUUID().toString(), ownerId, accountId, input.dayOfWeek(),
                input.localTime(), input.timeZone(), input.enabled(), input.instruction().strip(), slots,
                input.dayOfWeek(), input.localTime(), input.timeZone(), input.enabled(),
                input.instruction().strip(), slots);
        return findByAccount(ownerId, accountId).orElseThrow();
    }

    public Optional<Job> findByAccount(long ownerId, String accountId) {
        if (accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        return jdbc.query("""
                SELECT id, account_id, day_of_week, local_time, time_zone, enabled, instruction, slots_json
                FROM generation_jobs WHERE owner_id = ? AND account_id = ?
                """, this::mapJob, ownerId, accountId).stream().findFirst();
    }

    public Optional<Job> find(long ownerId, String jobId) {
        return jdbc.query("""
                SELECT id, account_id, day_of_week, local_time, time_zone, enabled, instruction, slots_json
                FROM generation_jobs WHERE owner_id = ? AND id = ?
                """, this::mapJob, ownerId, jobId).stream().findFirst();
    }

    public TriggerRun trigger(long ownerId, String jobId, TriggerSource source) {
        return trigger(ownerId, jobId, source, null).orElseThrow();
    }

    Optional<TriggerRun> triggerScheduled(long ownerId, String jobId, String runKey) {
        try {
            return trigger(ownerId, jobId, TriggerSource.SCHEDULED, runKey);
        } catch (ContentValidator.ContentInvalid invalid) {
            if ("JOB_DISABLED".equals(invalid.getMessage())) return Optional.empty();
            throw invalid;
        }
    }

    private Optional<TriggerRun> trigger(long ownerId, String jobId, TriggerSource source, String runKey) {
        var job = find(ownerId, jobId)
                .orElseThrow(() -> new ContentValidator.ContentInvalid("JOB_NOT_FOUND"));
        if (source == TriggerSource.SCHEDULED && !job.enabled())
            throw new ContentValidator.ContentInvalid("JOB_DISABLED");
        var lock = redisson.getLock("generation:paid");
        if (!lock.tryLock()) {
            if (source == TriggerSource.MANUAL)
                throw new ContentValidator.ContentInvalid("GENERATION_BUSY");
            return Optional.empty();
        }
        try {
            return execute(ownerId, job, source, runKey);
        } finally {
            lock.unlock();
        }
    }

    private Optional<TriggerRun> execute(long ownerId, Job job, TriggerSource source, String runKey) {
        var id = UUID.randomUUID().toString();
        var requestId = UUID.randomUUID().toString();
        var request = new GenerationService.Request(job.accountId(), "WEEK_PLAN", null,
                job.instruction(), null, null, job.slots(), requestId);
        String requestJson;
        try {
            requestJson = json.writeValueAsString(request);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
        try {
            jdbc.update("""
                INSERT INTO generation_job_triggers (id, job_id, owner_id, account_id,
                                                     trigger_source, status, request_id, run_key, request_json)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?)
                """, id, job.id(), ownerId, job.accountId(), source.name(), requestId, runKey,
                    requestJson);
        } catch (DuplicateKeyException duplicate) {
            if (runKey == null) throw duplicate;
            return findScheduledTrigger(ownerId, job.id(), runKey);
        }
        try {
            var run = generation.generate(ownerId, request);
            jdbc.update("""
                    UPDATE generation_job_triggers
                    SET status = ?, generation_run_id = ?, error_code = ? WHERE id = ? AND owner_id = ?
                    """, run.status(), run.id(), run.errorCode(), id, ownerId);
        } catch (RuntimeException failed) {
            jdbc.update("""
                    UPDATE generation_job_triggers SET status = 'FAILED', error_code = ?
                    WHERE id = ? AND owner_id = ?
                    """, failed instanceof ContentValidator.ContentInvalid ? failed.getMessage() :
                    "TRIGGER_FAILED", id, ownerId);
            throw failed;
        }
        return findTrigger(ownerId, id);
    }

    private Optional<TriggerRun> findScheduledTrigger(long ownerId, String jobId, String runKey) {
        return jdbc.query("""
                SELECT id, job_id, account_id, trigger_source, status, request_id,
                       generation_run_id, error_code
                FROM generation_job_triggers WHERE owner_id = ? AND job_id = ? AND run_key = ?
                """, this::mapTrigger, ownerId, jobId, runKey).stream().findFirst();
    }

    public List<TriggerRun> triggers(long ownerId, String jobId) {
        if (find(ownerId, jobId).isEmpty()) throw new ContentValidator.ContentInvalid("JOB_NOT_FOUND");
        return jdbc.query("""
                SELECT id, job_id, account_id, trigger_source, status, request_id,
                       generation_run_id, error_code
                FROM generation_job_triggers WHERE owner_id = ? AND job_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 50
                """, this::mapTrigger, ownerId, jobId);
    }

    private Optional<TriggerRun> findTrigger(long ownerId, String id) {
        return jdbc.query("""
                SELECT id, job_id, account_id, trigger_source, status, request_id,
                       generation_run_id, error_code
                FROM generation_job_triggers WHERE owner_id = ? AND id = ?
                """, this::mapTrigger, ownerId, id).stream().findFirst();
    }

    private void validate(JobInput input) {
        if (input == null || input.dayOfWeek() < 1 || input.dayOfWeek() > 7
                || input.localTime() == null || input.timeZone() == null || input.timeZone().length() > 64
                || input.enabled() == null
                || input.instruction() == null || input.instruction().isBlank()
                || input.instruction().strip().length() > 500 || input.slots() == null
                || input.slots().size() != 3
                || input.slots().stream().anyMatch(slot -> slot == null || slot.materialIds() == null
                        || slot.materialIds().isEmpty() || slot.materialIds().size() > 3
                        || slot.materialIds().stream().anyMatch(id -> id == null || id.isBlank())
                        || slot.instruction() != null && slot.instruction().length() > 500)
                || !"Java 面试".equals(input.slots().get(0).column())
                || !"Java 面试".equals(input.slots().get(1).column())
                || !"英语跟读".equals(input.slots().get(2).column()))
            throw new ContentValidator.ContentInvalid("INVALID_JOB");
        try {
            ZoneId.of(input.timeZone());
        } catch (DateTimeException invalid) {
            throw new ContentValidator.ContentInvalid("INVALID_JOB");
        }
    }

    private Job mapJob(ResultSet row, int ignored) throws SQLException {
        try {
            var slots = json.readValue(row.getString("slots_json"),
                    new TypeReference<List<GenerationService.WeekSlot>>() { });
            return new Job(row.getString("id"), row.getString("account_id"), row.getInt("day_of_week"),
                    row.getTime("local_time").toLocalTime(), row.getString("time_zone"),
                    row.getBoolean("enabled"), row.getString("instruction"), slots);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid saved job slots", invalid);
        }
    }

    private TriggerRun mapTrigger(ResultSet row, int ignored) throws SQLException {
        return new TriggerRun(row.getString("id"), row.getString("job_id"), row.getString("account_id"),
                row.getString("trigger_source"), row.getString("status"), row.getString("request_id"),
                row.getString("generation_run_id"), row.getString("error_code"));
    }

    public record JobInput(int dayOfWeek, LocalTime localTime, String timeZone, Boolean enabled,
                           String instruction, List<GenerationService.WeekSlot> slots) { }
    public record Job(String id, String accountId, int dayOfWeek, LocalTime localTime, String timeZone,
                      boolean enabled, String instruction, List<GenerationService.WeekSlot> slots) { }
    public record TriggerRun(String id, String jobId, String accountId, String triggerSource,
                             String status, String requestId, String generationRunId, String errorCode) { }
    public enum TriggerSource { MANUAL, SCHEDULED }
}
