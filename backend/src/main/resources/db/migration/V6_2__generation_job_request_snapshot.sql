ALTER TABLE generation_job_triggers
    ADD COLUMN request_json JSON NULL AFTER run_key;
