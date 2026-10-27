-- 公共仅指同一律所成员可读；来源单独上传，案件资料不能直接变成公共文件。
ALTER TABLE knowledge_base DROP CHECK ck_kb_scope,
 MODIFY COLUMN case_id BIGINT NULL,
 ADD COLUMN tenant_name VARCHAR(200) GENERATED ALWAYS AS (IF(scope='TENANT',name,NULL)) STORED,
 ADD UNIQUE KEY uk_kb_tenant_name (tenant_id,tenant_name),
 ADD UNIQUE KEY uk_kb_owned_scope (tenant_id,id,scope),
 ADD CONSTRAINT ck_kb_scope CHECK (
   (scope='CASE' AND case_id IS NOT NULL) OR (scope='TENANT' AND case_id IS NULL));

ALTER TABLE file_object MODIFY COLUMN case_id BIGINT NULL,
 ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'CASE',
 ADD COLUMN knowledge_base_id BIGINT NULL,
 ADD CONSTRAINT ck_file_scope CHECK (
   (scope='CASE' AND case_id IS NOT NULL AND knowledge_base_id IS NULL)
   OR (scope='TENANT' AND case_id IS NULL AND knowledge_base_id IS NOT NULL)),
 ADD CONSTRAINT fk_file_knowledge_scope FOREIGN KEY (tenant_id,knowledge_base_id,scope)
   REFERENCES knowledge_base(tenant_id,id,scope),
 ADD INDEX ix_file_knowledge (tenant_id,knowledge_base_id,status,id);

INSERT INTO permission(code,name) VALUES ('knowledge:read','查看律所公共知识库');
INSERT INTO role_permission(tenant_id,role_id,permission_code)
 SELECT tenant_id,id,'knowledge:read' FROM sys_role WHERE code IN ('TENANT_ADMIN','LAWYER');
