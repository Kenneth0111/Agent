ALTER TABLE content_schedule_items
    ADD COLUMN publication_url VARCHAR(2048) NULL AFTER script_id,
    ADD COLUMN external_work_id VARCHAR(128) NULL AFTER publication_url,
    ADD COLUMN published_at TIMESTAMP(6) NULL AFTER external_work_id;
