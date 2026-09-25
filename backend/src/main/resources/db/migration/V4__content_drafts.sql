CREATE TABLE content_topics (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    column_name VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    audience VARCHAR(160) NOT NULL,
    angle VARCHAR(500) NOT NULL,
    hook VARCHAR(500) NOT NULL,
    outline TEXT NOT NULL,
    rationale VARCHAR(1000) NOT NULL,
    source_ids_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_content_topics_owner_account (owner_id, account_id, created_at),
    CONSTRAINT fk_content_topics_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_content_topics_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE content_scripts (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    topic_id CHAR(36) NOT NULL,
    spoken_text TEXT NOT NULL,
    shooting_notes TEXT NOT NULL,
    source_ids_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_content_scripts_owner_topic (owner_id, topic_id),
    CONSTRAINT fk_content_scripts_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_content_scripts_topic FOREIGN KEY (topic_id) REFERENCES content_topics (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE generation_runs (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    result_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_generation_runs_owner_created (owner_id, created_at),
    CONSTRAINT fk_generation_runs_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_generation_runs_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
