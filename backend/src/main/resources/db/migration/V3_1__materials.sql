CREATE TABLE materials (
    id CHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    purpose VARCHAR(80) NOT NULL,
    source_url VARCHAR(2048) NULL,
    file_name VARCHAR(255) NULL,
    content MEDIUMTEXT NOT NULL,
    segment_count SMALLINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX ix_materials_owner_created (owner_id, created_at),
    CONSTRAINT fk_materials_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;

CREATE TABLE material_segments (
    material_id CHAR(36) NOT NULL,
    segment_index SMALLINT UNSIGNED NOT NULL,
    body TEXT NOT NULL,
    PRIMARY KEY (material_id, segment_index),
    CONSTRAINT fk_material_segments_material FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
