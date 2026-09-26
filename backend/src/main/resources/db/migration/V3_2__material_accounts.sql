ALTER TABLE materials ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'TEXT';

CREATE TABLE material_accounts (
    material_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    PRIMARY KEY (material_id, account_id),
    INDEX ix_material_accounts_account (account_id),
    CONSTRAINT fk_material_accounts_material FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE,
    CONSTRAINT fk_material_accounts_account FOREIGN KEY (account_id) REFERENCES content_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
