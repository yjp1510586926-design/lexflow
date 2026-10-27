#!/bin/sh
set -eu
# Executed by the official MySQL entrypoint on an empty volume only.
case "${AI_WORKFLOW_MYSQL_PASSWORD:-}" in
  ''|*[!A-Za-z0-9_-]*) printf '%s\n' '工作流密码必须为16–128位字母、数字、下划线或连字符' >&2; exit 1 ;;
esac
if [ "${#AI_WORKFLOW_MYSQL_PASSWORD}" -lt 16 ] || [ "${#AI_WORKFLOW_MYSQL_PASSWORD}" -gt 128 ]; then
  printf '%s\n' '工作流密码必须为16–128位字母、数字、下划线或连字符' >&2; exit 1
fi
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql --protocol=socket --user=root <<SQL
CREATE DATABASE IF NOT EXISTS lexflow_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'lexflow_ai'@'%' IDENTIFIED BY '$AI_WORKFLOW_MYSQL_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX ON lexflow_ai.* TO 'lexflow_ai'@'%';
SQL
unset MYSQL_PWD

