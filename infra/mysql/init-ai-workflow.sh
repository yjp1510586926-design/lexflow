#!/bin/sh
set -eu

# Local Compose bootstrap only. Existing accounts are not reset.
project_directory=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
compose_file=${LEXFLOW_COMPOSE_FILE:-$project_directory/docker-compose.dev.yml}
set -- docker compose -f "$compose_file"
if [ -n "${LEXFLOW_COMPOSE_ENV_FILE:-}" ]; then
  set -- "$@" --env-file "$LEXFLOW_COMPOSE_ENV_FILE"
fi
if [ -n "${LEXFLOW_COMPOSE_PROJECT_NAME:-}" ]; then
  set -- "$@" --project-name "$LEXFLOW_COMPOSE_PROJECT_NAME"
fi
AI_WORKFLOW_MYSQL_PASSWORD=${AI_WORKFLOW_MYSQL_PASSWORD:-lexflow_ai_dev_only}
AI_WORKFLOW_MYSQL_DATABASE=${AI_WORKFLOW_MYSQL_DATABASE:-lexflow_ai}
AI_WORKFLOW_MYSQL_USER=${AI_WORKFLOW_MYSQL_USER:-lexflow_ai}
for workflow_identifier in "$AI_WORKFLOW_MYSQL_DATABASE" "$AI_WORKFLOW_MYSQL_USER"; do
  case "$workflow_identifier" in
    lexflow_ai|lexflow_ai_?*) ;;
    *) printf '%s\n' '工作流库与账号名称必须为 lexflow_ai 或带独立后缀的名称' >&2; exit 1 ;;
  esac
  case "$workflow_identifier" in
    *[!a-z0-9_]*) printf '%s\n' '工作流库与账号名称仅允许小写字母、数字和下划线' >&2; exit 1 ;;
  esac
done
if [ "${#AI_WORKFLOW_MYSQL_DATABASE}" -gt 64 ] || [ "${#AI_WORKFLOW_MYSQL_USER}" -gt 32 ]; then
  printf '%s\n' '工作流库名最多64字符，账号名最多32字符' >&2; exit 1
fi
case "$AI_WORKFLOW_MYSQL_PASSWORD" in
  *[!A-Za-z0-9_@#%+=:.,-]*|'')
    printf '%s\n' '本地工作流密码请使用至少 16 位字母、数字或 _@#%+=:.,- 字符' >&2; exit 1 ;;
esac
if [ "${#AI_WORKFLOW_MYSQL_PASSWORD}" -lt 16 ] || [ "${#AI_WORKFLOW_MYSQL_PASSWORD}" -gt 128 ]; then
  printf '%s\n' '本地工作流密码长度应为 16–128 位' >&2; exit 1
fi

"$@" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root' <<SQL
CREATE DATABASE IF NOT EXISTS \`$AI_WORKFLOW_MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS '$AI_WORKFLOW_MYSQL_USER'@'%' IDENTIFIED BY '$AI_WORKFLOW_MYSQL_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX ON \`$AI_WORKFLOW_MYSQL_DATABASE\`.* TO '$AI_WORKFLOW_MYSQL_USER'@'%';
SQL

# If an existing account has another password, report failure rather than silently resetting it.
"$@" exec -T mysql sh -c 'IFS= read -r workflow_password; MYSQL_PWD="$workflow_password" exec mysql --user="$1" "$2" --execute="SELECT 1"' sh "$AI_WORKFLOW_MYSQL_USER" "$AI_WORKFLOW_MYSQL_DATABASE" >/dev/null <<PASSWORD
$AI_WORKFLOW_MYSQL_PASSWORD
PASSWORD
printf '%s\n' '独立工作流数据库与账号已准备'
