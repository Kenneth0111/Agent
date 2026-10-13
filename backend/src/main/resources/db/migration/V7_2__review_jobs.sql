CREATE TABLE review_jobs (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    day_of_week INT NOT NULL,
    local_time TIME NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    topic VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_review_job_account (owner_id, account_id),
    UNIQUE KEY uq_review_job_owner_account (id, owner_id, account_id),
    CONSTRAINT fk_review_job_account FOREIGN KEY (account_id, owner_id) REFERENCES content_accounts (id, owner_id),
    CONSTRAINT ck_review_job_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_review_job_topic CHECK (CHAR_LENGTH(topic) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE review_job_triggers (
    id CHAR(36) NOT NULL PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    run_key DATE NOT NULL COMMENT 'Monday of the scheduled local week',
    request_id CHAR(36) NOT NULL,
    request_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(80) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_review_job_week (job_id, run_key),
    UNIQUE KEY uq_review_trigger_request (owner_id, request_id),
    KEY idx_review_trigger_owner (owner_id, job_id, created_at),
    CONSTRAINT fk_review_trigger_job FOREIGN KEY (job_id, owner_id, account_id) REFERENCES review_jobs (id, owner_id, account_id),
    CONSTRAINT ck_review_trigger_status CHECK (status IN ('RUNNING', 'GENERATING', 'SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED', 'NEEDS_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
