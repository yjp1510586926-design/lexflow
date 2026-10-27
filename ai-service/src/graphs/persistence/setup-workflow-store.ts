import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Pool } from 'mysql2/promise';
import { workflowPool } from './workflow-pool';

/** Explicit setup only; normal server startup never creates schemas or users. */
export async function setupWorkflowStore(pool: Pool): Promise<void> {
  const migration = await readFile(
    resolve(__dirname, '../../../sql/001_workflow_checkpoints.sql'),
    'utf8',
  );
  for (const statement of migration
    .replace(/^--.*$/gm, '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean))
    await pool.query(statement);
}

if (require.main === module) {
  (async () => {
    const pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
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
