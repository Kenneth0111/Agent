ALTER TABLE usage_calls ADD COLUMN reserved_cny DECIMAL(18,6) NULL;

CREATE TABLE usage_budget_mutex (
    id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO usage_budget_mutex (id) VALUES (1);
