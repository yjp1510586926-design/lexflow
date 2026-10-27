CREATE TABLE ai_configuration (
 tenant_id BIGINT NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 provider VARCHAR(80) NOT NULL,
 base_url VARCHAR(500) NOT NULL,
 model_name VARCHAR(120) NOT NULL,
 encrypted_key TEXT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,purpose),
 FOREIGN KEY (tenant_id) REFERENCES tenant(id),
 CONSTRAINT ck_ai_configuration_purpose CHECK (purpose IN ('LLM','EMBEDDING','RERANK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
