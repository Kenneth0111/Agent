ALTER TABLE generation_runs ADD COLUMN failed_node VARCHAR(32) NULL AFTER error_code;
