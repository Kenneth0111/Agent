ALTER TABLE content_accounts ADD UNIQUE KEY uq_content_accounts_owner (id, owner_id);

CREATE TABLE video_works (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    account_id CHAR(36) NOT NULL,
    video_id VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_video_work_external (account_id, video_id),
    UNIQUE KEY uq_video_work_owner (id, owner_id),
    KEY idx_video_work_owner (owner_id, account_id),
    CONSTRAINT fk_video_work_account FOREIGN KEY (account_id, owner_id)
        REFERENCES content_accounts (id, owner_id),
    CONSTRAINT ck_video_work_id CHECK (CHAR_LENGTH(video_id) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE video_metric_snapshots (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    work_id CHAR(36) NOT NULL,
    captured_at DATETIME(6) NOT NULL COMMENT 'UTC observation time',
    views BIGINT NULL,
    likes BIGINT NULL,
    comment_count BIGINT NULL,
    source VARCHAR(160) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_video_snapshot (work_id, captured_at, source),
    KEY idx_video_snapshot_owner (owner_id, work_id, captured_at),
    CONSTRAINT fk_video_snapshot_work FOREIGN KEY (work_id, owner_id)
        REFERENCES video_works (id, owner_id),
    CONSTRAINT ck_video_snapshot_counts CHECK (
        (views IS NULL OR views >= 0) AND (likes IS NULL OR likes >= 0)
        AND (comment_count IS NULL OR comment_count >= 0)),
    CONSTRAINT ck_video_snapshot_known CHECK (views IS NOT NULL OR likes IS NOT NULL OR comment_count IS NOT NULL),
    CONSTRAINT ck_video_snapshot_source CHECK (CHAR_LENGTH(source) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE video_comments (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    work_id CHAR(36) NOT NULL,
    comment_id VARCHAR(128) COLLATE utf8mb4_0900_as_cs NOT NULL,
    body TEXT NOT NULL,
    published_at DATETIME(6) NULL COMMENT 'UTC comment publication time; NULL means unknown',
    captured_at DATETIME(6) NOT NULL COMMENT 'UTC sample capture time',
    source VARCHAR(160) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_video_comment (work_id, comment_id, source),
    KEY idx_video_comment_owner (owner_id, work_id),
    CONSTRAINT fk_video_comment_work FOREIGN KEY (work_id, owner_id)
        REFERENCES video_works (id, owner_id),
    CONSTRAINT ck_video_comment_fields CHECK (CHAR_LENGTH(comment_id) > 0
        AND CHAR_LENGTH(source) > 0 AND CHAR_LENGTH(body) BETWEEN 1 AND 2000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
