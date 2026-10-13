CREATE TABLE operation_reviews (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_json JSON NOT NULL,
    facts_json JSON NULL,
    advice_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_operation_review_request (owner_id, request_id),
    KEY idx_operation_review_owner (owner_id, account_id, created_at),
    CONSTRAINT fk_operation_review_account FOREIGN KEY (account_id, owner_id)
        REFERENCES content_accounts (id, owner_id),
    CONSTRAINT ck_operation_review_status CHECK (status IN
        ('RUNNING', 'GENERATING', 'SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED', 'NEEDS_REVIEW')),
    CONSTRAINT ck_operation_review_attempts CHECK (attempts BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
