CREATE TABLE content_schedule_weeks (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    week_start DATE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_content_schedule_week (owner_id, account_id, week_start),
    CONSTRAINT fk_content_schedule_week_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_content_schedule_week_account FOREIGN KEY (account_id) REFERENCES content_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE content_schedule_items (
    id CHAR(36) NOT NULL,
    week_id CHAR(36) NOT NULL,
    position TINYINT UNSIGNED NOT NULL,
    column_name VARCHAR(80) NOT NULL,
    scheduled_date DATE NOT NULL,
    topic_id CHAR(36) NULL,
    script_id CHAR(36) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_content_schedule_item_position (week_id, position),
    CONSTRAINT fk_content_schedule_item_week FOREIGN KEY (week_id) REFERENCES content_schedule_weeks (id),
    CONSTRAINT fk_content_schedule_item_topic FOREIGN KEY (topic_id) REFERENCES content_topics (id),
    CONSTRAINT fk_content_schedule_item_script FOREIGN KEY (script_id) REFERENCES content_scripts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
