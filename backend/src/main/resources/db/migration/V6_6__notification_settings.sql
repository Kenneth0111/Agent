CREATE TABLE notification_settings (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    email_recipient VARCHAR(254) NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    smtp_host VARCHAR(255) NULL,
    smtp_port SMALLINT UNSIGNED NULL,
    smtp_username_enc TEXT NULL,
    smtp_password_enc TEXT NULL,
    feishu_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    feishu_webhook_enc TEXT NULL,
    feishu_secret_enc TEXT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notification_settings_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE notification_deliveries (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    trigger_id CHAR(36) NOT NULL,
    generation_run_id CHAR(36) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_notification_delivery (trigger_id, channel),
    KEY idx_notification_delivery_owner (owner_id, created_at),
    CONSTRAINT fk_notification_delivery_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_notification_delivery_trigger FOREIGN KEY (trigger_id) REFERENCES generation_job_triggers (id),
    CONSTRAINT fk_notification_delivery_run FOREIGN KEY (generation_run_id) REFERENCES generation_runs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
