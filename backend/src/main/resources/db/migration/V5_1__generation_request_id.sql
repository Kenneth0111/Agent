ALTER TABLE generation_runs
    ADD COLUMN request_id CHAR(36) NULL AFTER mode,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_id,
    ADD UNIQUE KEY uq_generation_run_request (owner_id, request_id);
