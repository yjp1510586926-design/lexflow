CREATE TABLE case_analysis (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 background_text VARCHAR(3000) NOT NULL,
 requirements_text VARCHAR(2000) NOT NULL,
 model_identity VARCHAR(1200) NOT NULL,
 sources_json JSON NOT NULL,
 report_json JSON NULL,
 waiting_reason VARCHAR(24) NULL,
 decision_id CHAR(36) NULL,
 decided_by BIGINT NULL,
 decision_note VARCHAR(500) NULL,
 decided_at TIMESTAMP(6) NULL,
 PRIMARY KEY (tenant_id,task_id),
 UNIQUE (tenant_id,decision_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,decided_by) REFERENCES tenant_user(tenant_id,user_id),
 CHECK (waiting_reason IS NULL OR waiting_reason IN ('REVIEW','UNCERTAIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
