-- Evidence describes an immutable original file version; it never changes file bytes.
CREATE TABLE evidence (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 file_version_id BIGINT NOT NULL,
 source VARCHAR(500) NOT NULL,
 -- Historical evidence may predate the Unix epoch; this UTC value is not TIMESTAMP-limited.
 collected_at DATETIME(6) NULL,
 description TEXT NOT NULL,
 sort_order INT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_evidence_version (tenant_id,file_version_id),
 FOREIGN KEY (tenant_id,case_id) REFERENCES case_info(tenant_id,id),
 FOREIGN KEY (tenant_id,file_version_id) REFERENCES file_version(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 FOREIGN KEY (tenant_id,updated_by) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_evidence_order CHECK (sort_order BETWEEN 0 AND 100000),
 CONSTRAINT ck_evidence_version CHECK (version >= 1),
 INDEX ix_evidence_case (tenant_id,case_id,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
