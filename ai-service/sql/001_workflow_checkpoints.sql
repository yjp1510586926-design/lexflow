-- 只在 AI 服务独立数据库中执行，不读写 Java 业务库。
-- 本文件由 setupWorkflowStore 显式读取并逐条执行，普通 NestJS 启动不会自动执行。
-- 四张表分别保存线程身份、图快照、节点待写入数据和外部操作结果，作用不能互相替代。
-- tenant_id + thread_id 共同隔离工作流；字符串标识采用二进制排序规则，避免大小写混同。
-- lease_token 与 MySQL 执行锁共同使用，用来拒绝失去执行锁的旧执行者继续写入。
CREATE TABLE IF NOT EXISTS workflow_thread (
  -- 业务租户编号参与所有存储键，防止相同任务标识跨租户读写。
  tenant_id BIGINT NOT NULL,
  -- 普通任务采用 lf:租户:任务，主动连接测试采用 lft:租户:测试。
  thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  -- 当前锁持有者的随机标识；它不是到期时间，释放时按原 token 条件清空。
  lease_token CHAR(36) COLLATE utf8mb4_bin NULL,
  PRIMARY KEY (tenant_id, thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 图检查点保存节点状态和父快照关系；序列化格式由 type 字段标识。
-- 主键把同一线程中的不同命名空间和不同快照分开，恢复时可读取最新或指定快照。
CREATE TABLE IF NOT EXISTS workflow_checkpoint (
  tenant_id BIGINT NOT NULL,
  thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  -- 命名空间用于隔离子图或补材轮次，不能只根据 checkpoint_id 跨线程读取。
  checkpoint_ns VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  -- 父快照用于返回 LangGraph 的 parentConfig，首个快照没有父节点。
  parent_checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  -- type + blob 成对保存，交给 LangGraph 序列化器还原状态，不是直接存 JSON 文本。
  checkpoint_type VARCHAR(32) NOT NULL,
  checkpoint_blob MEDIUMBLOB NOT NULL,
  -- 元数据与状态分开序列化；运行时模型、连接配置和 Key 不应写入快照。
  metadata_type VARCHAR(32) NOT NULL,
  metadata_blob BLOB NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, thread_id, checkpoint_ns, checkpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- LangGraph 的节点待写入数据可能早于对应检查点到达，因此单独存表。
-- 这里的 task_id 是 LangGraph 内部节点执行标识，不是 Java 队列消息中的业务 taskId。
CREATE TABLE IF NOT EXISTS workflow_checkpoint_write (
  tenant_id BIGINT NOT NULL,
  thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  checkpoint_ns VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  checkpoint_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  task_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  -- 通常是节点输出的序号；特殊通道由 LangGraph 映射到固定索引，写入器决定覆盖规则。
  write_idx INT NOT NULL,
  -- 指明这一项要写入哪个状态通道；值仍通过 value_type + value_blob 还原。
  channel_name VARCHAR(255) NOT NULL,
  value_type VARCHAR(32) NOT NULL,
  value_blob MEDIUMBLOB NOT NULL,
  PRIMARY KEY (tenant_id, thread_id, checkpoint_ns, checkpoint_id, task_id, write_idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- runOnce 先提交 STARTED 再发外部请求，成功结果独立于图检查点保存。
-- 若进程崩溃后只留下 STARTED，无法证明远程是否执行，恢复时不得静默重发。
-- 同线程中的 operation_key 标识某个协议版本的外部动作，input_hash 防止输入变化后误用旧结果。
CREATE TABLE IF NOT EXISTS workflow_operation (
  tenant_id BIGINT NOT NULL,
  thread_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  operation_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  -- 摘要包含固定业务输入和模型身份，不包含 API Key 等运行时凭据。
  input_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  -- 存储器使用 STARTED、SUCCEEDED、FAILED；这是操作日志状态，不是 Java 业务任务状态。
  status VARCHAR(16) NOT NULL,
  -- 外部动作完成时仍须匹配开始时的所有者，旧执行者不能覆盖新所有者的操作记录。
  owner_token CHAR(36) COLLATE utf8mb4_bin NOT NULL,
  -- 仅保存可复用的序列化结果；SUCCEEDED 记录可在图快照尚未提交时先被恢复流程复用。
  result_type VARCHAR(32) NULL,
  result_blob MEDIUMBLOB NULL,
  -- 失败只保存固定本地错误码，不保存供应商原始异常或响应体。
  error_code VARCHAR(32) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  finished_at TIMESTAMP(6) NULL,
  PRIMARY KEY (tenant_id, thread_id, operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
