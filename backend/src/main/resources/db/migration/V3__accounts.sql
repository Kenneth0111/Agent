CREATE TABLE content_accounts (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    audience VARCHAR(160) NOT NULL,
    positioning VARCHAR(500) NOT NULL,
    columns_json JSON NOT NULL,
    weekly_target TINYINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_content_accounts_owner (owner_id),
    CONSTRAINT fk_content_accounts_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
