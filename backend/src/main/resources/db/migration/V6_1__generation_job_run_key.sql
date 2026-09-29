ALTER TABLE generation_job_triggers
    ADD COLUMN run_key CHAR(10) NULL AFTER request_id,
    ADD UNIQUE KEY uq_generation_job_week (job_id, run_key);
