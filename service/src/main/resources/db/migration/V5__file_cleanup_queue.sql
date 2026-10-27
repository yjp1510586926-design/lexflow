CREATE TABLE file_cleanup_job (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 file_id BIGINT NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(100),
 next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at TIMESTAMP(6) NULL,
 UNIQUE (tenant_id, object_key),
 FOREIGN KEY (tenant_id,file_id) REFERENCES file_object(tenant_id,id),
 INDEX ix_cleanup_due (status,next_attempt_at,id),
 CONSTRAINT ck_cleanup_status CHECK (status IN ('PENDING','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
