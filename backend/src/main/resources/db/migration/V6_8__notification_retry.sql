ALTER TABLE notification_deliveries
    ADD COLUMN attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at TIMESTAMP(6) NULL AFTER error_code,
    ADD KEY idx_notification_due (status, next_attempt_at);
