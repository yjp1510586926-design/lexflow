-- Preserve historical exports. Only new explicitly configured requests receive a deadline.
ALTER TABLE document_export_job
 ADD COLUMN retention_days INT NOT NULL DEFAULT 0,
 ADD COLUMN expires_at TIMESTAMP(6) NULL,
 ADD COLUMN expired_at TIMESTAMP(6) NULL,
 ADD CONSTRAINT ck_export_retention_days CHECK (retention_days BETWEEN 0 AND 3650),
 ADD INDEX ix_export_result_expiry (expired_at,expires_at,task_id);

-- Completed detailed bookkeeping can be compacted, never forgetting a late-writable object key.
CREATE TABLE file_cleanup_tombstone (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 file_id BIGINT NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 next_review_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_review_at TIMESTAMP(6) NULL,
 last_error VARCHAR(100) NULL,
 UNIQUE (tenant_id,object_key),
 FOREIGN KEY (tenant_id,file_id) REFERENCES file_object(tenant_id,id),
 INDEX ix_file_tombstone_review (next_review_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE export_cleanup_tombstone (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 claim_token CHAR(36) NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 next_review_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_review_at TIMESTAMP(6) NULL,
 last_error VARCHAR(100) NULL,
 UNIQUE (tenant_id,object_key),
 UNIQUE (claim_token),
 FOREIGN KEY (tenant_id,task_id) REFERENCES document_export_job(tenant_id,task_id),
 INDEX ix_export_tombstone_review (next_review_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
