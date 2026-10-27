#!/bin/sh
set -eu
project_directory=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
config_path=${LEXFLOW_MVP_ENV_PATH:-$project_directory/.env.mvp.local}
command -v openssl >/dev/null || { printf '%s\n' '需要 openssl 生成随机部署凭据' >&2; exit 1; }
# Refuse to replace existing configuration, including links; all secrets stay in this file.
if [ -e "$config_path" ] || [ -L "$config_path" ]; then
  printf '%s\n' '配置文件已存在，保留原文件；请手动选择新路径或编辑现有配置' >&2; exit 1
fi
umask 077
set -C
{
  printf '%s\n' 'COMPOSE_PROJECT_NAME=lexflow-mvp' 'WEB_BIND_ADDRESS=127.0.0.1' 'WEB_PORT=8088' 'COOKIE_SECURE=false' 'MYSQL_DATABASE=lexflow' 'MYSQL_USER=lexflow'
  printf 'MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'AI_WORKFLOW_MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf '%s\n' 'RABBITMQ_DEFAULT_USER=lexflow'
  printf 'RABBITMQ_DEFAULT_PASS=%s\n' "$(openssl rand -hex 24)"
  printf '%s\n' 'MINIO_ROOT_USER=lexflow' 'LEXFLOW_STORAGE_BUCKET=lexflow-files'
  printf 'MINIO_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'LEXFLOW_INTERNAL_SECRET=%s\n' "$(openssl rand -hex 32)"
  printf 'LEXFLOW_AI_MASTER_KEY=%s\n' "$(openssl rand -base64 32)"
  printf '%s\n' 'LEXFLOW_AI_ALLOWED_HOSTS=api.deepseek.com' 'EXPORT_RETENTION_DAYS=0' 'CLEANUP_COMPACT_AFTER_DAYS=0'
} > "$config_path"
printf '已生成私有部署配置：%s（不含模型 API Key）\n' "$config_path"

