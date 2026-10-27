-- 用户私有的案件资料问答。模型密钥不进入会话或问答记录。
CREATE TABLE ai_conversation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 knowledge_base_id BIGINT NOT NULL,
 created_by BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id,id),
 FOREIGN KEY (tenant_id,case_id) REFERENCES case_info(tenant_id,id),
 FOREIGN KEY (tenant_id,knowledge_base_id) REFERENCES knowledge_base(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 INDEX ix_conversation_owner (tenant_id,created_by,knowledge_base_id,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_question (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 conversation_id BIGINT NOT NULL,
 request_id CHAR(36) NOT NULL,
 question VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
 answer_text TEXT NULL,
 insufficient_evidence BOOLEAN NOT NULL DEFAULT FALSE,
 -- 保存所有进入模型的片段，读取时任一来源失效就隐藏整段回答。
 evidence_json JSON NULL,
 citation_ids_json JSON NULL,
 error_message VARCHAR(200) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at TIMESTAMP(6) NULL,
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,conversation_id,request_id),
 FOREIGN KEY (tenant_id,conversation_id) REFERENCES ai_conversation(tenant_id,id),
 CONSTRAINT ck_question_status CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED')),
 INDEX ix_question_history (tenant_id,conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
