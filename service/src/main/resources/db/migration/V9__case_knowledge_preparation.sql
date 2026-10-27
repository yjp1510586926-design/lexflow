-- 案件私有知识的入库准备。公共知识库须使用独立授权的来源，不能直接发布案件文件。
CREATE TABLE knowledge_base (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 name VARCHAR(200) NOT NULL,
 scope VARCHAR(16) NOT NULL DEFAULT 'CASE',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,case_id,name),
 FOREIGN KEY (tenant_id,case_id) REFERENCES case_info(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_kb_scope CHECK (scope='CASE'),
 CONSTRAINT ck_kb_status CHECK (status IN ('ACTIVE','ARCHIVED')),
 INDEX ix_kb_case (tenant_id,case_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_document (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 knowledge_base_id BIGINT NOT NULL,
 file_version_id BIGINT NOT NULL,
 parse_task_id BIGINT NOT NULL,
 index_version INT NOT NULL DEFAULT 1,
 source_sha256 CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
 chunk_count INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,knowledge_base_id,file_version_id),
 FOREIGN KEY (tenant_id,knowledge_base_id) REFERENCES knowledge_base(tenant_id,id),
 FOREIGN KEY (tenant_id,file_version_id) REFERENCES file_version(tenant_id,id),
 FOREIGN KEY (tenant_id,parse_task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_kd_status CHECK (status IN ('PREPARED','INDEXING','ACTIVE','FAILED','SUPERSEDED')),
 CONSTRAINT ck_kd_index_version CHECK (index_version > 0),
 INDEX ix_kd_base_status (tenant_id,knowledge_base_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_chunk (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 knowledge_document_id BIGINT NOT NULL,
 index_version INT NOT NULL,
 chunk_no INT NOT NULL,
 locator_kind VARCHAR(16) NOT NULL,
 locator_index INT NOT NULL,
 start_offset INT NOT NULL,
 end_offset INT NOT NULL,
 content_text TEXT NOT NULL,
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,knowledge_document_id,index_version,chunk_no),
 FOREIGN KEY (tenant_id,knowledge_document_id) REFERENCES knowledge_document(tenant_id,id),
 CONSTRAINT ck_kc_locator CHECK (locator_kind IN ('PAGE','PARAGRAPH')),
 CONSTRAINT ck_kc_offsets CHECK (start_offset >= 0 AND end_offset > start_offset),
 INDEX ix_kc_document (tenant_id,knowledge_document_id,index_version,chunk_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
