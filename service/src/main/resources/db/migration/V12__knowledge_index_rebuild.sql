-- Published index pointer and in-flight job are separate. Never reuse an abandoned version.
ALTER TABLE knowledge_index_job ADD COLUMN index_version INT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_document
 ADD COLUMN last_index_version INT NOT NULL DEFAULT 0,
 ADD COLUMN pending_index_task_id BIGINT NULL,
 ADD CONSTRAINT fk_knowledge_pending_task FOREIGN KEY (tenant_id,pending_index_task_id)
   REFERENCES async_task(tenant_id,id);
UPDATE knowledge_document d SET last_index_version=d.index_version
 WHERE EXISTS (SELECT 1 FROM knowledge_index_job j WHERE j.tenant_id=d.tenant_id AND j.knowledge_document_id=d.id);
UPDATE knowledge_index_job j JOIN knowledge_document d
 ON d.tenant_id=j.tenant_id AND d.id=j.knowledge_document_id SET j.index_version=d.index_version;
UPDATE knowledge_document d SET pending_index_task_id=(
 SELECT MAX(j.task_id) FROM knowledge_index_job j JOIN async_task t ON t.tenant_id=j.tenant_id AND t.id=j.task_id
 WHERE j.tenant_id=d.tenant_id AND j.knowledge_document_id=d.id AND t.status IN ('QUEUED','RUNNING')
) WHERE d.status='INDEXING';
UPDATE knowledge_document SET status='FAILED' WHERE status='INDEXING' AND pending_index_task_id IS NULL;
