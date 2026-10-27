require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt } = require('node:crypto');
const { mkdtempSync, writeFileSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { spawn } = require('node:child_process');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  WorkflowOperationUncertainError,
  WorkflowOperationConflictError,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const {
  runPersistedKnowledgeIndex,
  knowledgeIndexIdentity,
} = require('../dist/rag/knowledge-index-workflow.service');
const { fixture } = require('./support/index-child.cjs');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const database =
  process.env.AI_WORKFLOW_DATABASE_URL ??
  'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
function pool() {
  const u = new URL(database);
  assert.equal(u.hostname, '127.0.0.1');
  assert.equal(u.port, '13306');
  assert.equal(u.pathname, '/lexflow_ai');
  assert.equal(u.username, 'lexflow_ai');
  return workflowPool(database);
}
function child(phase, tenant, task, counter) {
  return new Promise((resolve, reject) => {
    const p = spawn(
      process.execPath,
      [join(__dirname, 'support/index-child.cjs'), phase, tenant, task, counter],
      {
        env: { ...process.env, AI_WORKFLOW_DATABASE_URL: database },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    let out = '';
    p.stdout.on('data', (b) => (out += b));
    const timeout = setTimeout(() => {
      p.kill('SIGKILL');
      reject(new Error('index child timed out'));
    }, 20000);
    p.on('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    p.on('exit', (code) => {
      clearTimeout(timeout);
      try {
        assert.equal(code, 0);
        resolve(JSON.parse(out));
      } catch (error) {
        reject(error);
      }
    });
  });
}
test('索引摘要绑定材料和配置版本，Key 不进入操作摘要', () => {
  const { input } = fixture('7', '123');
  const initial = knowledgeIndexIdentity(input);
  assert.equal(knowledgeIndexIdentity({ ...input, apiKey: 'changed-only' }), initial);
  for (const changed of [
    { configurationRevision: 'another' },
    { indexVersion: 2 },
    { fileVersionId: '12' },
    { chunks: [{ id: '1', text: 'changed' }] },
  ])
    assert.notEqual(knowledgeIndexIdentity({ ...input, ...changed }), initial);
});
test('跨租户或跨知识范围在执行锁及模型调用之前被拒绝', async () => {
  const data = fixture('7', '123'),
    saver = { withThreadLock: () => assert.fail('must fail before SQL') };
  for (const changed of [
    { tenantId: '8' },
    { caseId: '9' },
    { scope: 'TENANT', caseId: null },
    { knowledgeBaseId: '10' },
  ])
    await assert.rejects(
      runPersistedKnowledgeIndex(
        data.task,
        { ...data.input, ...changed },
        saver,
        {},
        {},
        async () => {},
      ),
      /不属于/,
    );
  await assert.rejects(
    runPersistedKnowledgeIndex(
      data.task,
      { ...data.input, configurationRevision: undefined },
      saver,
      {},
      {},
      async () => {},
    ),
    /配置版本/,
  );
});
test(
  '真实 AI SQL：存储失败后的独立进程恢复复用批次，完成后再次投递不新增模型调用',
  { skip: !enabled },
  async () => {
    const db = pool(),
      tenant = '910000205',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(db, tenant);
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-index-recovery-')),
      counter = join(dir, 'counter');
    writeFileSync(counter, '0');
    try {
      await setupWorkflowStore(db);
      assert.equal(
        (await child('fail', tenant, task, counter)).outcome,
        'IndexStoreUnavailableError',
      );
      assert.equal(readFileSync(counter, 'utf8'), '1');
      const resumed = await child('resume', tenant, task, counter);
      assert.equal(resumed.outcome, 'SUCCEEDED');
      assert.equal(resumed.receipt.indexedChunks, 60);
      assert.equal(readFileSync(counter, 'utf8'), '2');
      assert.equal((await child('resume', tenant, task, counter)).outcome, 'SUCCEEDED');
      assert.equal(readFileSync(counter, 'utf8'), '2');
      const [rows] = await db.execute(
        'SELECT status,result_blob FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, workflowThreadId(tenant, task)],
      );
      assert.equal(rows.length, 2);
      assert.ok(rows.every((r) => r.status === 'SUCCEEDED'));
      assert.ok(rows.every((r) => !r.result_blob.toString().includes('synthetic-key-only')));
      const data = fixture(tenant, task);
      data.input.configurationRevision = 'changed';
      await assert.rejects(
        runPersistedKnowledgeIndex(
          data.task,
          data.input,
          saver,
          { embedDocuments: async () => assert.fail('changed input') },
          {},
          async () => {},
        ),
        WorkflowOperationConflictError,
      );
    } finally {
      await saver.deleteThread(workflowThreadId(tenant, task));
      await db.end();
      rmSync(dir, { recursive: true, force: true });
    }
  },
);
test(
  '真实 AI SQL：遗留 STARTED 保持不确定，恢复不得调用模型；权限拒绝发生在模型之前',
  { skip: !enabled },
  async () => {
    const db = pool(),
      tenant = '910000205',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(db, tenant),
      data = fixture(tenant, task);
    try {
      await setupWorkflowStore(db);
      let calls = 0;
      await assert.rejects(
        runPersistedKnowledgeIndex(
          data.task,
          data.input,
          saver,
          {
            embedDocuments: async () => {
              calls++;
              return [];
            },
          },
          {},
          async () => {
            throw new Error('revoked');
          },
        ),
        /revoked/,
      );
      assert.equal(calls, 0);
      // Reserve the exact first batch as a crash would, without sending any real request.
      const { createHash } = require('node:crypto');
      const texts = data.input.chunks.slice(0, 50).map((c) => c.text);
      const hash = createHash('sha256')
        .update(JSON.stringify([knowledgeIndexIdentity(data.input), texts]))
        .digest('hex');
      await db.execute(
        "INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token) VALUES(?,?,'index-embedding-v1-0',?,'STARTED','synthetic-crash')",
        [tenant, workflowThreadId(tenant, task), hash],
      );
      await assert.rejects(
        runPersistedKnowledgeIndex(
          data.task,
          data.input,
          saver,
          {
            embedDocuments: async () => {
              calls++;
              return [];
            },
          },
          {},
          async () => {},
        ),
        WorkflowOperationUncertainError,
      );
      assert.equal(calls, 0);
      const [rows] = await db.execute(
        'SELECT status FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, workflowThreadId(tenant, task)],
      );
      assert.equal(rows[0].status, 'STARTED');
    } finally {
      await saver.deleteThread(workflowThreadId(tenant, task));
      await db.end();
    }
  },
);
