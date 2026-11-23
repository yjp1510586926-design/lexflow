import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Pool } from 'mysql2/promise';
import { workflowDatabaseUrl } from '../../config/settings';
import { workflowPool } from './workflow-pool';

/**
 * 显式执行独立 AI 工作流表初始化，供 npm run setup:workflow 调用。
 * 从项目 sql 目录读取固定迁移，移除整行注释后按分号拆分，依次执行 CREATE TABLE IF NOT EXISTS。
 * 这是当前简单建表脚本的执行器，不是能解析存储过程/任意 SQL 的迁移框架，也不提供多语句事务回滚保证。
 * 正常 NestJS 启动不调用本函数，数据库/账号须由部署流程提前准备。
 *
 * @param pool 已按独立 AI 库配置创建的连接池，初始化只在该库创建工作流表。
 * @returns 所有语句成功执行后完成；池仍由调用方负责关闭。
 * @throws 文件读取或 SQL 执行失败会向命令入口传播，入口只输出通用错误。
 */
export async function setupWorkflowStore(pool: Pool): Promise<void> {
  const migration = await readFile(
    resolve(__dirname, '../../../sql/001_workflow_checkpoints.sql'),
    'utf8',
  );
  // 此迁移只含简单 SQL；逐条执行以保持连接池禁止 multipleStatements 的约束。
  for (const statement of migration
    .replace(/^--.*$/gm, '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean)) {
    await pool.query(statement);
  }
}

if (require.main === module) {
  (async () => {
    const pool = workflowPool(workflowDatabaseUrl());
    try {
      await setupWorkflowStore(pool);
      process.stdout.write('AI 工作流存储已初始化\n');
    } finally {
      await pool.end();
    }
  })().catch(() => {
    process.stderr.write('AI 工作流存储初始化失败，请检查独立数据库及账号权限\n');
    process.exitCode = 1;
  });
}
