CREATE TABLE content_week_plans (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    items_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_content_week_plans_owner_account (owner_id, account_id, created_at),
    CONSTRAINT fk_content_week_plans_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_content_week_plans_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
