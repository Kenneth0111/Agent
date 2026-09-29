CREATE TABLE generation_jobs (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    day_of_week TINYINT UNSIGNED NOT NULL,
    local_time TIME NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    instruction VARCHAR(500) NOT NULL,
    slots_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_generation_job_account (owner_id, account_id),
    CONSTRAINT fk_generation_job_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_generation_job_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE generation_job_triggers (
    id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    trigger_source VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id CHAR(36) NOT NULL,
    generation_run_id CHAR(36) NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_generation_job_trigger_request (owner_id, request_id),
    INDEX ix_generation_job_triggers_job_created (job_id, created_at),
    CONSTRAINT fk_generation_job_trigger_job FOREIGN KEY (job_id) REFERENCES generation_jobs (id),
    CONSTRAINT fk_generation_job_trigger_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_generation_job_trigger_account FOREIGN KEY (account_id) REFERENCES content_accounts (id),
    CONSTRAINT fk_generation_job_trigger_run FOREIGN KEY (generation_run_id) REFERENCES generation_runs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
