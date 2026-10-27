-- V4: 文书版本、异步任务、outbox 事件表
-- 文档 §3.4 文书：归属案件，版本不可覆盖，恢复产生新版本
CREATE TABLE document (
 id           BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id    BIGINT       NOT NULL,
 case_id      BIGINT       NOT NULL,
 title        VARCHAR(255) NOT NULL,
 -- 指向最新已发布版本（NULL 表示只有草稿尚未发布）
 current_version_id BIGINT NULL,
 status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
 created_by   BIGINT       NOT NULL,
 version      BIGINT       NOT NULL DEFAULT 1,
 created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id, id),
 UNIQUE (tenant_id, case_id, id),
 FOREIGN KEY (tenant_id, case_id) REFERENCES case_info(tenant_id, id),
 FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user(tenant_id, user_id),
 CONSTRAINT ck_doc_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 INDEX ix_doc_list (tenant_id, case_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文书内容版本（不可覆盖，AI 结果以 review_status=PENDING_REVIEW 入库）
CREATE TABLE document_version (
 id              BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id       BIGINT       NOT NULL,
 document_id     BIGINT       NOT NULL,
 version_no      INT          NOT NULL,
 -- 内容存 MinIO 对象，短文档也可存 TEXT（首期用 content_text）
 content_text    MEDIUMTEXT   NULL,
 content_object_key VARCHAR(512) NULL,
 -- 来源：MANUAL / AI_DRAFT
 source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
 -- AI 草稿待复核状态：PENDING_REVIEW / APPROVED / REJECTED / NULL
 review_status   VARCHAR(16)  NULL,
 source_task_id  BIGINT       NULL,
 created_by      BIGINT       NOT NULL,
 created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id, id),
 UNIQUE (tenant_id, document_id, version_no),
 FOREIGN KEY (tenant_id, document_id) REFERENCES document(tenant_id, id),
 FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user(tenant_id, user_id),
 CONSTRAINT ck_doc_ver_source CHECK (source IN ('MANUAL','AI_DRAFT')),
 CONSTRAINT ck_doc_ver_review CHECK (review_status IS NULL OR review_status IN ('PENDING_REVIEW','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文书自引用外键（建表后添加，避免循环依赖）
ALTER TABLE document
 ADD FOREIGN KEY (tenant_id, current_version_id) REFERENCES document_version(tenant_id, id);

-- 异步任务（§3.5 任务中心）
-- state_version 用于乐观锁防止迟到消息覆盖终态
CREATE TABLE async_task (
 id              BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id       BIGINT       NOT NULL,
 case_id         BIGINT       NULL,
 -- 任务类型：DOCUMENT_PARSE / KNOWLEDGE_INGEST / CONTRACT_REVIEW / EXPORT
 type            VARCHAR(32)  NOT NULL,
 -- 关联资源版本 ID（如 file_version_id），用于结果回传校验
 resource_version_id BIGINT   NULL,
 status          VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
 -- Idempotency-Key 防重复提交（同键不同输入需拒绝）
 idempotency_key VARCHAR(128) NOT NULL,
 attempt         INT          NOT NULL DEFAULT 0,
 state_version   INT          NOT NULL DEFAULT 1,
 -- 可理解的错误信息（面向操作者）
 error_message   TEXT         NULL,
 -- 结果对象键（如生成文书的 MinIO key）
 result_object_key VARCHAR(512) NULL,
 created_by      BIGINT       NOT NULL,
 started_at      TIMESTAMP(6) NULL,
 finished_at     TIMESTAMP(6) NULL,
 created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id, id),
 UNIQUE (tenant_id, idempotency_key),
 FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user(tenant_id, user_id),
 CONSTRAINT ck_task_status CHECK (status IN (
  'QUEUED','RUNNING','WAITING_HUMAN','SUCCEEDED','FAILED','CANCEL_REQUESTED','CANCELLED'
 )),
 CONSTRAINT ck_task_type CHECK (type IN (
  'DOCUMENT_PARSE','KNOWLEDGE_INGEST','CONTRACT_REVIEW','EXPORT'
 )),
 INDEX ix_task_list (tenant_id, created_by, created_at, id),
 INDEX ix_task_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox 事件（事务内写入，发布器异步发送至 RabbitMQ）
CREATE TABLE outbox_event (
 event_id      VARCHAR(64)  PRIMARY KEY,
 tenant_id     BIGINT       NOT NULL,
 task_id       BIGINT       NOT NULL,
 routing_key   VARCHAR(128) NOT NULL,
 payload       JSON         NOT NULL,
 -- PENDING / SENT / FAILED
 status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
 attempt       INT          NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 sent_at       TIMESTAMP(6) NULL,
 FOREIGN KEY (tenant_id, task_id) REFERENCES async_task(tenant_id, id),
 CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT','FAILED')),
 INDEX ix_outbox_pending (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
