CREATE TABLE task_parse_result (
  tenant_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  content_text MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, task_id),
  FOREIGN KEY (tenant_id, task_id) REFERENCES async_task(tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
