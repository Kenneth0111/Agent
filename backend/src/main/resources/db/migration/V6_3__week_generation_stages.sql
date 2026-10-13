CREATE TABLE generation_week_stages (
    run_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    stage_index TINYINT UNSIGNED NOT NULL,
    stage_name VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_json JSON NOT NULL,
    output_id CHAR(36) NULL,
    attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id, stage_index),
    CONSTRAINT fk_generation_week_stage_run FOREIGN KEY (run_id) REFERENCES generation_runs (id),
    CONSTRAINT fk_generation_week_stage_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
