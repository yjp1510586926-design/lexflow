-- 公共指律所授权资料；会话仍为创建者私有，不能把空案件编号当作授权。
ALTER TABLE ai_conversation MODIFY COLUMN case_id BIGINT NULL,
 ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'CASE',
 ADD CONSTRAINT ck_conversation_scope CHECK (
   (scope='CASE' AND case_id IS NOT NULL) OR (scope='TENANT' AND case_id IS NULL)),
 ADD CONSTRAINT fk_conversation_knowledge_scope FOREIGN KEY (tenant_id,knowledge_base_id,scope)
   REFERENCES knowledge_base(tenant_id,id,scope);
