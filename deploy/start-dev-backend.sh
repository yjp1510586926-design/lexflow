#!/bin/sh
set -eu

project_directory=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_directory"

if [ ! -f .env ]; then
  printf '%s\n' '缺少本机配置 .env，请先按 README 配置开发环境。' >&2
  exit 1
fi

jar_path=api-core/target/api-core-0.1.0-SNAPSHOT.jar
if [ ! -f "$jar_path" ]; then
  printf '%s\n' '尚未构建后端，请先在项目根目录运行 mvn package。' >&2
  exit 1
fi

# 显式加载本机配置，让系统加密主密钥在重启后保持一致；不把密钥放入命令参数。
exec java -jar "$jar_path" '--spring.config.import=file:.env[.properties]' "$@"
