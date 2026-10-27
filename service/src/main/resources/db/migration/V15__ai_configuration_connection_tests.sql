-- 每次保存产生独立版本；不以 Key 的摘要作为公开标识。
ALTER TABLE ai_configuration ADD COLUMN revision CHAR(36) NULL;
UPDATE ai_configuration SET revision=UUID();
ALTER TABLE ai_configuration MODIFY COLUMN revision CHAR(36) NOT NULL DEFAULT (UUID());

CREATE TABLE ai_configuration_test (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 creator_id BIGINT NOT NULL,
 session_version BIGINT NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 config_revision CHAR(36) NOT NULL,
 request_id CHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PREPARED',
 elapsed_ms INT NULL,
 dimension INT NULL,
 error_code VARCHAR(40) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 started_at TIMESTAMP(6) NULL,
 finished_at TIMESTAMP(6) NULL,
 UNIQUE KEY uk_ai_test_request (tenant_id,request_id),
 KEY ix_ai_test_latest (tenant_id,purpose,config_revision,id),
 KEY ix_ai_test_active (tenant_id,purpose,status,created_at),
 FOREIGN KEY (tenant_id,creator_id) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_ai_test_purpose CHECK (purpose IN ('LLM','EMBEDDING','RERANK')),
 CONSTRAINT ck_ai_test_state CHECK (status IN ('PREPARED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN','STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
