-- Owned by the AI service's separate database/account; never run in the Java business schema.
CREATE TABLE IF NOT EXISTS workflow_thread (
 tenant_id BIGINT NOT NULL,
 thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 lease_token CHAR(36) COLLATE utf8mb4_bin NULL,
 PRIMARY KEY (tenant_id,thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_checkpoint (
 tenant_id BIGINT NOT NULL,
 thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 checkpoint_ns VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 parent_checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
 checkpoint_type VARCHAR(32) NOT NULL,
 checkpoint_blob MEDIUMBLOB NOT NULL,
 metadata_type VARCHAR(32) NOT NULL,
 metadata_blob BLOB NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,thread_id,checkpoint_ns,checkpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pending writes may arrive before the associated checkpoint in async execution.
CREATE TABLE IF NOT EXISTS workflow_checkpoint_write (
 tenant_id BIGINT NOT NULL,
 thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 checkpoint_ns VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 task_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 write_idx INT NOT NULL,
 channel_name VARCHAR(255) NOT NULL,
 value_type VARCHAR(32) NOT NULL,
 value_blob MEDIUMBLOB NOT NULL,
 PRIMARY KEY (tenant_id,thread_id,checkpoint_ns,checkpoint_id,task_id,write_idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Commit the reservation before issuing an external request. A stranded STARTED
-- operation is deliberately uncertain; recovery must never silently issue it again.
CREATE TABLE IF NOT EXISTS workflow_operation (
 tenant_id BIGINT NOT NULL,
 thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 operation_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 input_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 status VARCHAR(16) NOT NULL,
 owner_token CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 result_type VARCHAR(32) NULL,
 result_blob MEDIUMBLOB NULL,
 error_code VARCHAR(32) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at TIMESTAMP(6) NULL,
 PRIMARY KEY (tenant_id,thread_id,operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
