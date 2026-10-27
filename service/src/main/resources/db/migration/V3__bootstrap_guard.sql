CREATE TABLE installation_guard (
 id INT PRIMARY KEY,
 initialized BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT ck_installation_singleton CHECK (id=1)
) ENGINE=InnoDB;
INSERT INTO installation_guard (id, initialized) VALUES (1,FALSE);
