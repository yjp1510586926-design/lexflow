-- Vector locations are registered before writes. Keep retired locations as durable tombstones:
-- a worker which was already in flight may write after the first deletion.
CREATE TABLE knowledge_vector_artifact (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 knowledge_base_id BIGINT NOT NULL,
 case_id BIGINT NULL,
 scope VARCHAR(16) NOT NULL,
 knowledge_document_id BIGINT NOT NULL,
 file_version_id BIGINT NOT NULL,
 index_version INT NOT NULL,
 task_id BIGINT NULL,
 collection_name VARCHAR(100) NOT NULL,
 embedding_dimension INT NOT NULL,
 model_identity VARCHAR(1000) NOT NULL,
 retired_at TIMESTAMP(6) NULL,
 next_check_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token VARCHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 cleanup_attempt INT NOT NULL DEFAULT 0,
 last_cleaned_at TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_vector_location (tenant_id,knowledge_document_id,index_version,collection_name),
 INDEX ix_vector_cleanup (next_check_at,lease_until,id),
 CHECK (tenant_id>0 AND knowledge_base_id>0 AND knowledge_document_id>0 AND file_version_id>0 AND index_version>0),
 CHECK ((scope='CASE' AND case_id>0) OR (scope='TENANT' AND case_id IS NULL)),
 CHECK (embedding_dimension BETWEEN 2 AND 4096),
 CHECK ((lease_token IS NULL)=(lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only known exact published locations are imported. Old abandoned jobs did not persist
-- their dimension/collection; guessing those locations could delete another live index.
INSERT INTO knowledge_vector_artifact(tenant_id,knowledge_base_id,case_id,scope,
 knowledge_document_id,file_version_id,index_version,task_id,collection_name,embedding_dimension,model_identity)
SELECT d.tenant_id,d.knowledge_base_id,b.case_id,b.scope,d.id,d.file_version_id,d.index_version,
 (SELECT MAX(j.task_id) FROM knowledge_index_job j WHERE j.tenant_id=d.tenant_id
   AND j.knowledge_document_id=d.id AND j.index_version=d.index_version AND j.model_identity=d.model_identity),
 d.collection_name,d.embedding_dimension,d.model_identity
FROM knowledge_document d JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id
WHERE d.collection_name IS NOT NULL AND d.model_identity IS NOT NULL
 AND d.embedding_dimension BETWEEN 2 AND 4096 AND d.index_version>0
 AND ((b.scope='CASE' AND b.case_id>0) OR (b.scope='TENANT' AND b.case_id IS NULL));
