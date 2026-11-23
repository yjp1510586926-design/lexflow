#!/bin/sh
set -eu

project_directory=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
config_path=${LEXFLOW_AI_ENV_PATH:-$project_directory/.env.ai.local}
if [ ! -f "$config_path" ]; then
  printf '%s\n' '缺少本机 AI 配置 .env.ai.local，请按 ai-service/README.md 配置。' >&2
  exit 1
fi

node_binary=${LEXFLOW_NODE_BINARY:-node}
if ! "$node_binary" -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' 2>/dev/null; then
  if [ -n "${LEXFLOW_NODE_BINARY:-}" ]; then
    printf '%s\n' '指定的 Node.js 不可用或版本低于 24。' >&2
    exit 1
  fi
  for candidate in /opt/homebrew/opt/node@24/bin/node /usr/local/opt/node@24/bin/node; do
    if [ -x "$candidate" ]; then
      node_binary=$candidate
      break
    fi
  done
fi
if ! "$node_binary" -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' 2>/dev/null; then
  printf '%s\n' '需要 Node.js 24 或更新版本；可通过 LEXFLOW_NODE_BINARY 指定路径。' >&2
  exit 1
fi

cd "$project_directory/ai-service"
if [ ! -f dist/main.js ] || [ ! -f dist/infrastructure/persistence/workflow-pool.js ]; then
  printf '%s\n' '尚未构建 AI 服务，请先在 ai-service 下运行 npm run build。' >&2
  exit 1
fi

# 单独加载 AI 配置；不向 AI 进程传入 Java 的凭据加密主密钥。
"$node_binary" --env-file="$config_path" -e '
  const { workflowPool } = require("./dist/infrastructure/persistence/workflow-pool");
  const { allowedModelHosts, workflowDatabaseUrl } = require("./dist/config/settings");
  (async () => {
    let pool;
    try {
      if (!allowedModelHosts().trim()) throw new Error();
      pool = workflowPool(workflowDatabaseUrl());
      await pool.query("SELECT 1 FROM workflow_thread LIMIT 0");
      await pool.query("SELECT 1 FROM workflow_checkpoint LIMIT 0");
      await pool.query("SELECT 1 FROM workflow_checkpoint_write LIMIT 0");
      await pool.query("SELECT 1 FROM workflow_operation LIMIT 0");
    } catch {
      console.error("AI 配置检查失败：请检查模型域名白名单、独立工作流数据库、账号及工作流表。");
      process.exitCode = 1;
    } finally {
      if (pool) await pool.end();
    }
  })();
'
exec "$node_binary" --env-file="$config_path" dist/main.js "$@"
