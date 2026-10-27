ALTER TABLE contract_review ADD COLUMN review_round INT NOT NULL DEFAULT 0;

CREATE TABLE contract_review_round (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 round_no INT NOT NULL,
 request_id CHAR(36) NOT NULL,
 from_state_version INT NOT NULL,
 created_by BIGINT NOT NULL,
 instructions VARCHAR(1000) NOT NULL,
 report_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (tenant_id,task_id,round_no),
 UNIQUE (tenant_id,request_id),
 FOREIGN KEY (tenant_id,task_id) REFERENCES contract_review(tenant_id,task_id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 CHECK (round_no BETWEEN 1 AND 10),
 CHECK (from_state_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_review_supplement (
 tenant_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 round_no INT NOT NULL,
 parse_task_id BIGINT NOT NULL,
 file_version_id BIGINT NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 display_name VARCHAR(255) NOT NULL,
 source_text MEDIUMTEXT NOT NULL,
 segments_json JSON NOT NULL,
 PRIMARY KEY (tenant_id,task_id,round_no,parse_task_id),
 FOREIGN KEY (tenant_id,task_id,round_no) REFERENCES contract_review_round(tenant_id,task_id,round_no),
 FOREIGN KEY (tenant_id,parse_task_id) REFERENCES async_task(tenant_id,id),
 FOREIGN KEY (tenant_id,file_version_id) REFERENCES file_version(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
