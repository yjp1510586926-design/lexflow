-- 每次向量索引任务绑定一个已准备的知识文档；密钥不进入任务或 outbox。
CREATE TABLE knowledge_index_job (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 knowledge_document_id BIGINT NOT NULL,
 model_identity VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,task_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,knowledge_document_id) REFERENCES knowledge_document(tenant_id,id),
 INDEX ix_kij_document (tenant_id,knowledge_document_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE knowledge_document
 ADD COLUMN collection_name VARCHAR(100) NULL,
 ADD COLUMN embedding_dimension INT NULL,
 ADD COLUMN model_identity VARCHAR(1000) NULL;
