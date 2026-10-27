CREATE TABLE document_export_job (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 document_id BIGINT NOT NULL,
 document_version_id BIGINT NOT NULL,
 title VARCHAR(255) NOT NULL,
 version_no INT NOT NULL,
 document_status VARCHAR(16) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL,
 claim_token CHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 result_object_key VARCHAR(512) NULL,
 PRIMARY KEY (tenant_id,task_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,document_id) REFERENCES document(tenant_id,id),
 FOREIGN KEY (tenant_id,document_version_id) REFERENCES document_version(tenant_id,id),
 INDEX ix_export_expiry (lease_until,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Register each private object before upload. Keep tombstones for late writes after cancellation/crash.
CREATE TABLE document_export_artifact (
 token CHAR(36) PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 object_key VARCHAR(512) NOT NULL UNIQUE,
 next_cleanup_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 cleanup_attempts INT NOT NULL DEFAULT 0,
 last_cleaned_at TIMESTAMP(6) NULL,
 cleanup_error VARCHAR(100) NULL,
 FOREIGN KEY (tenant_id,task_id) REFERENCES document_export_job(tenant_id,task_id),
 INDEX ix_export_cleanup (next_cleanup_at,tenant_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
