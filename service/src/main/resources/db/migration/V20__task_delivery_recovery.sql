-- Java owns delivery state and recovery intents. AI operation journals remain in the independent AI database.
CREATE TABLE task_delivery_recovery (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 attempt INT NOT NULL,
 delivery_status VARCHAR(16) NOT NULL,
 retry_count INT NOT NULL,
 failure_kind VARCHAR(16) NOT NULL,
 dead_lettered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 recovered_at TIMESTAMP(6) NULL,
 PRIMARY KEY (tenant_id,task_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 CONSTRAINT ck_delivery_status CHECK (delivery_status IN ('DEAD','RESOLVED')),
 CONSTRAINT ck_delivery_retry CHECK (retry_count BETWEEN 0 AND 3),
 CONSTRAINT ck_delivery_failure CHECK (failure_kind IN ('TRANSIENT','PERMANENT','UNCERTAIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep every intent: an HTTP retry of an earlier recovery must never restart a later dead letter.
CREATE TABLE task_delivery_recovery_request (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 from_state_version INT NOT NULL,
 confirm_model_costs BOOLEAN NOT NULL,
 recovered_attempt INT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,request_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,actor_id) REFERENCES tenant_user(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Historical jobs cannot prove that earlier paid calls were journaled; require a fresh task instead.
ALTER TABLE knowledge_index_job ADD COLUMN execution_protocol INT NOT NULL DEFAULT 1;
