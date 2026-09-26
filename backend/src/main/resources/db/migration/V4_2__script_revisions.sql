CREATE TABLE generation_conversations (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_generation_conversations_owner (owner_id, account_id),
    CONSTRAINT fk_generation_conversations_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_generation_conversations_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

ALTER TABLE content_scripts
    ADD COLUMN conversation_id CHAR(36) NULL AFTER version,
    ADD CONSTRAINT fk_content_scripts_conversation FOREIGN KEY (conversation_id) REFERENCES generation_conversations (id);

CREATE TABLE content_script_versions (
    script_id CHAR(36) NOT NULL,
    version INT UNSIGNED NOT NULL,
    spoken_text TEXT NOT NULL,
    shooting_notes TEXT NOT NULL,
    source_ids_json JSON NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    instruction VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (script_id, version),
    INDEX ix_content_script_versions_conversation (conversation_id, created_at),
    CONSTRAINT fk_content_script_versions_script FOREIGN KEY (script_id) REFERENCES content_scripts (id),
    CONSTRAINT fk_content_script_versions_conversation FOREIGN KEY (conversation_id) REFERENCES generation_conversations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
