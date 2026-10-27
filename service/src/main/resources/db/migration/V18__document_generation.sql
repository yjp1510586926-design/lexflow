ALTER TABLE async_task DROP CHECK ck_task_type;
ALTER TABLE async_task ADD CONSTRAINT ck_task_type CHECK (type IN (
 'DOCUMENT_PARSE','KNOWLEDGE_INGEST','CONTRACT_REVIEW','EXPORT','DOCUMENT_GENERATE'
));

INSERT INTO permission(code,name) VALUES('ai:generate','AI 文书生成');
INSERT INTO role_permission(tenant_id,role_id,permission_code)
 SELECT tenant_id,id,'ai:generate' FROM sys_role WHERE code IN ('TENANT_ADMIN','LAWYER');

CREATE TABLE document_generation (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 document_id BIGINT NOT NULL,
 source_version_id BIGINT NOT NULL,
 instructions VARCHAR(2000) NOT NULL,
 case_context_json JSON NOT NULL,
 model_revision CHAR(36) NOT NULL,
 result_version_id BIGINT NULL,
 missing_information_json JSON NULL,
 PRIMARY KEY(tenant_id,task_id),
 UNIQUE(tenant_id,result_version_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES document(tenant_id,id),
 FOREIGN KEY(tenant_id,source_version_id) REFERENCES document_version(tenant_id,id),
 FOREIGN KEY(tenant_id,result_version_id) REFERENCES document_version(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
