const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt, createHash } = require('node:crypto');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { spawn, fork } = require('node:child_process');
const { emptyCheckpoint, uuid6 } = require('@langchain/langgraph-checkpoint');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  configurationTestThreadId,
  WorkflowBusyError,
  WorkflowOperationUncertainError,
  WorkflowOperationFailedError,
  WorkflowOperationConflictError,
} = require('../dist/graphs/persistence/mysql-checkpointer');

const localIntegration = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const url =
  process.env.AI_WORKFLOW_DATABASE_URL ??
  'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
const childPath = join(__dirname, 'support/checkpoint-child.cjs');

function integrationPool() {
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  assert.equal(parsed.username, 'lexflow_ai');
  return workflowPool(url);
}
function config(tenant, task) {
  return {
    configurable: {
      tenant_id: tenant,
      thread_id: workflowThreadId(tenant, task),
      checkpoint_ns: '',
    },
  };
}
function checkpoint() {
  const value = emptyCheckpoint();
  value.v = 4;
  value.id = uuid6(-1);
  value.channel_values = { text: '合成材料' };
  return value;
}
const metadata = { source: 'input', step: -1, parents: {} };
async function history(saver, c, options) {
  const rows = [];
  for await (const row of saver.list(c, options)) rows.push(row);
  return rows;
}
function child(mode, tenant, task, counter) {
  return new Promise((resolve, reject) => {
    const process = spawn(global.process.execPath, [childPath, mode, tenant, task, counter], {
      env: { ...global.process.env, AI_WORKFLOW_DATABASE_URL: url },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '',
      errors = '';
    process.stdout.on('data', (b) => (output += b));
    process.stderr.on('data', (b) => (errors += b));
    const timer = setTimeout(() => {
      process.kill('SIGKILL');
      reject(new Error('Child probe timed out'));
    }, 20000);
    process.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    process.once('exit', (code) => {
      clearTimeout(timer);
      try {
        resolve({ code, ...JSON.parse(output.trim()) });
      } catch {
        reject(new Error('Child probe returned no JSON: ' + errors.slice(0, 200)));
      }
    });
  });
}

test('工作流标识绑定租户，错误配置和凭据状态在 SQL 之前被拒绝', async () => {
  const unreachable = { execute: () => assert.fail('invalid input must not access SQL') };
  const saver = new TenantMySqlCheckpointer(unreachable, '7');
  const c = config('7', '123');
  assert.equal(workflowThreadId('7', '123'), 'lf:7:123');
  assert.equal(configurationTestThreadId('7', '123'), 'lft:7:123');
  for (const [tenant, task] of [
    ['0', '1'],
    ['7', '1\" OR true'],
    ['7', '9223372036854775808'],
  ])
    assert.throws(() => workflowThreadId(tenant, task));
  for (const bad of [
    { configurable: { tenant_id: '8', thread_id: 'lf:8:123' } },
    { configurable: { tenant_id: '7', thread_id: 'lf:8:123' } },
    { configurable: { tenant_id: '7', thread_id: 'lft:8:123' } },
    { configurable: { tenant_id: '7', thread_id: 'lfx:7:123' } },
    { configurable: { tenant_id: '7', thread_id: 'lft:7:9223372036854775808' } },
    { configurable: { tenant_id: '7', thread_id: '__proto__' } },
    { configurable: { tenant_id: '7', thread_id: 'lf:7:123', checkpoint_ns: 'constructor' } },
  ]) {
    await assert.rejects(saver.getTuple(bad));
    await assert.rejects(saver.put(bad, checkpoint(), metadata, {}));
  }
  for (const secret of [
    { apiKey: 'fake-key' },
    { config: { password: 'fake-password' } },
    new Map([['api_key', 'fake-key']]),
    { set: new Set([{ authorization: 'fake-header' }]) },
  ]) {
    const cp = checkpoint();
    cp.channel_values = { secret };
    await assert.rejects(saver.put(c, cp, metadata, {}), /凭据/);
  }
  await assert.rejects(saver.put(c, checkpoint(), { ...metadata, apiKey: 'fake-key' }, {}), /凭据/);
  const writesConfig = { configurable: { ...c.configurable, checkpoint_id: checkpoint().id } };
  await assert.rejects(
    saver.putWrites(writesConfig, [['data', { cookie: 'fake-cookie' }]], 'node'),
    /凭据/,
  );
  await assert.rejects(saver.deleteThread('lf:8:123'));
  const hash = createHash('sha256').update('synthetic').digest('hex');
  await assert.rejects(
    saver.runOnce(c, 'analysis-v1', hash, async () => assert.fail('no lock')),
    /执行锁/,
  );
  await assert.rejects(
    saver.runOnce(c, 'INVALID', hash, async () => assert.fail('invalid key')),
    /标识/,
  );
  await assert.rejects(
    saver.runOnce(c, 'analysis-v1', 'not-a-hash', async () => assert.fail('invalid hash')),
    /摘要/,
  );
  for (const invalid of [
    undefined,
    'mysql://root:test@127.0.0.1/lexflow_ai',
    'mysql://lexflow:test@127.0.0.1/lexflow_ai',
    'mysql://lexflow_ai:test@127.0.0.1/lexflow',
    'mysql://lexflow_ai:test@127.0.0.1/lexflow_ai?multipleStatements=true',
  ])
    assert.throws(() => workflowPool(invalid));
});

test(
  '外部操作持久化复用结果，拒绝输入变更与失败自动重试，不保存提供方错误或凭据',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000106',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    const hash = createHash('sha256').update('synthetic input').digest('hex');
    let calls = 0;
    try {
      await setupWorkflowStore(pool);
      const first = await saver.withThreadLock(c, () =>
        saver.runOnce(c, 'analysis-v1', hash, async () => {
          calls++;
          return { text: '合成分析', tags: ['test'] };
        }),
      );
      assert.deepEqual(first, { text: '合成分析', tags: ['test'] });
      const fresh = new TenantMySqlCheckpointer(pool, tenant);
      const cached = await fresh.withThreadLock(c, () =>
        fresh.runOnce(c, 'analysis-v1', hash, async () => {
          calls++;
          assert.fail('must reuse');
        }),
      );
      assert.deepEqual(cached, first);
      assert.equal(calls, 1);
      await assert.rejects(
        saver.withThreadLock(c, () =>
          saver.runOnce(c, 'analysis-v1', 'a'.repeat(64), async () => {
            calls++;
          }),
        ),
        WorkflowOperationConflictError,
      );
      await assert.rejects(
        saver.withThreadLock(c, () =>
          saver.runOnce(c, 'failed-v1', hash, async () => {
            calls++;
            throw new Error('provider fake-key-secret error');
          }),
        ),
        WorkflowOperationFailedError,
      );
      await assert.rejects(
        fresh.withThreadLock(c, () =>
          fresh.runOnce(c, 'failed-v1', hash, async () => {
            calls++;
          }),
        ),
        WorkflowOperationFailedError,
      );
      await assert.rejects(
        saver.withThreadLock(c, () =>
          saver.runOnce(c, 'secret-v1', hash, async () => {
            calls++;
            return { apiKey: 'fake-key-secret' };
          }),
        ),
        WorkflowOperationFailedError,
      );
      assert.equal(calls, 3);
      const [rows] = await pool.execute(
        'SELECT operation_key,status,error_code,result_blob FROM workflow_operation WHERE tenant_id=? AND thread_id=? ORDER BY operation_key',
        [tenant, c.configurable.thread_id],
      );
      assert.deepEqual(
        rows.map((r) => [r.operation_key, r.status, r.error_code]),
        [
          ['analysis-v1', 'SUCCEEDED', null],
          ['failed-v1', 'FAILED', 'CALL_FAILED'],
          ['secret-v1', 'FAILED', 'CALL_FAILED'],
        ],
      );
      assert.equal(
        rows.some((r) => r.result_blob?.includes(Buffer.from('fake-key-secret'))),
        false,
      );
      const other = '910000107',
        otherSaver = new TenantMySqlCheckpointer(pool, other),
        otherC = config(other, task);
      try {
        await assert.rejects(
          otherSaver.withThreadLock(c, () =>
            otherSaver.runOnce(c, 'analysis-v1', hash, async () => assert.fail('tenant mix')),
          ),
        );
        assert.equal(
          await otherSaver.withThreadLock(otherC, () =>
            otherSaver.runOnce(otherC, 'analysis-v1', hash, async () => 'other tenant result'),
          ),
          'other tenant result',
        );
      } finally {
        await otherSaver.deleteThread(otherC.configurable.thread_id);
      }
      await saver.deleteThread(c.configurable.thread_id);
      const [deleted] = await pool.execute(
        'SELECT COUNT(*) AS count FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, c.configurable.thread_id],
      );
      assert.equal(Number(deleted[0].count), 0);
    } finally {
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
      }
    }
  },
);

test(
  '模型请求开始后杀掉进程，恢复返回结果不确定且不再执行外部请求',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000108',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-graph-call-')),
      counter = join(dir, 'effects.txt');
    let holder;
    try {
      await setupWorkflowStore(pool);
      holder = fork(childPath, ['call-crash', tenant, task, counter], {
        env: { ...process.env, AI_WORKFLOW_DATABASE_URL: url },
        stdio: ['ignore', 'ignore', 'ignore', 'ipc'],
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Call child timeout')), 10000);
        holder.once('message', (message) => {
          clearTimeout(timer);
          message.modelStarted ? resolve() : reject(new Error('Call not started'));
        });
        holder.once('error', reject);
      });
      assert.equal(readFileSync(counter, 'utf8'), 'model\n');
      const exit = new Promise((resolve) => holder.once('exit', resolve));
      holder.kill('SIGKILL');
      await exit;
      holder = undefined;
      const restored = await child('call-recover', tenant, task, counter);
      assert.equal(restored.error, 'UNCERTAIN');
      assert.equal(readFileSync(counter, 'utf8'), 'model\n');
      const [rows] = await pool.execute(
        'SELECT status,result_blob,error_code FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, c.configurable.thread_id],
      );
      assert.equal(rows.length, 1);
      assert.equal(rows[0].status, 'STARTED');
      assert.equal(rows[0].result_blob, null);
      assert.equal(rows[0].error_code, null);
    } finally {
      if (holder) {
        const done = new Promise((resolve) => holder.once('exit', resolve));
        holder.kill('SIGKILL');
        await done;
      }
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
        rmSync(dir, { recursive: true, force: true });
      }
    }
  },
);

test(
  '持锁数据库连接断开后，新执行者可以保存，旧执行者迟到写入被拒绝',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000109',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    let release, started;
    const pause = new Promise((resolve) => (release = resolve)),
      ready = new Promise((resolve) => (started = resolve));
    let old;
    try {
      await setupWorkflowStore(pool);
      old = saver
        .withThreadLock(c, async () => {
          started();
          await pause;
          await saver.put(c, checkpoint(), metadata, {});
        })
        .then(
          () => ({ ok: true }),
          (error) => ({ ok: false, error }),
        );
      await ready;
      const lock =
        'lf_graph_' +
        createHash('sha256')
          .update(`${tenant}:${c.configurable.thread_id}`)
          .digest('hex')
          .slice(0, 48);
      const [owner] = await pool.execute('SELECT IS_USED_LOCK(?) AS owner', [lock]);
      const ownerId = String(owner[0].owner);
      assert.match(ownerId, /^[1-9][0-9]*$/);
      // Only this test's own AI-user connection, identified by its unique thread lock.
      await pool.query('KILL CONNECTION ' + ownerId);
      const fresh = new TenantMySqlCheckpointer(pool, tenant),
        cp = checkpoint();
      cp.channel_values = { text: 'fresh owner' };
      await fresh.withThreadLock(c, () => fresh.put(c, cp, metadata, {}));
      release();
      const outcome = await old;
      assert.equal(outcome.ok, false);
      const saved = await saver.getTuple(c);
      assert.equal(saved.checkpoint.id, cp.id);
      assert.equal(saved.checkpoint.channel_values.text, 'fresh owner');
    } finally {
      release?.();
      if (old) await old;
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
      }
    }
  },
);

test(
  '外部结果已保存但无图快照时，独立进程恢复直接复用结果且不再执行请求',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000110',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-graph-result-')),
      counter = join(dir, 'effects.txt');
    try {
      await setupWorkflowStore(pool);
      const first = await child('call-recover', tenant, task, counter);
      assert.equal(first.code, 0);
      assert.deepEqual(first.result, { analysis: 'synthetic analysis' });
      assert.equal(await saver.getTuple(c), undefined);
      const fresh = await child('call-recover', tenant, task, counter);
      assert.equal(fresh.code, 0);
      assert.deepEqual(fresh.result, first.result);
      assert.equal(readFileSync(counter, 'utf8'), 'model\n');
    } finally {
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
        rmSync(dir, { recursive: true, force: true });
      }
    }
  },
);

test(
  '真实 MySQL 保存快照、父版本、待写入及特殊通道；跨租户不可读写',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000101',
      other = '910000102',
      task = String(Date.now()) + randomInt(1000, 9999);
    const saver = new TenantMySqlCheckpointer(pool, tenant),
      otherSaver = new TenantMySqlCheckpointer(pool, other);
    const c = config(tenant, task),
      otherC = config(other, task);
    try {
      await setupWorkflowStore(pool);
      await assert.rejects(
        pool.query('SELECT id FROM lexflow.case_info LIMIT 1'),
        (e) => e.code === 'ER_TABLEACCESS_DENIED_ERROR',
      );
      assert.equal(await saver.getTuple(c), undefined);
      const first = checkpoint();
      const saved = await saver.put(c, first, metadata, {});
      // An explicit runtime key in RunnableConfig is not serialized.
      const second = checkpoint();
      second.channel_values = { text: '另一个版本', binary: new Uint8Array([1, 2, 3]) };
      const latest = await saver.put(
        { ...saved, configurable: { ...saved.configurable, apiKey: 'fake-runtime-key' } },
        second,
        { ...metadata, source: 'loop', step: 1 },
        {},
      );
      await saver.putWrites(
        latest,
        [
          ['value', 'first'],
          ['__error__', { error: 'first error' }],
        ],
        'node-A',
      );
      await saver.putWrites(
        latest,
        [
          ['value', 'must not overwrite'],
          ['__error__', { error: 'updated error' }],
        ],
        'node-A',
      );
      const tuple = await saver.getTuple(c);
      assert.equal(tuple.checkpoint.id, second.id);
      assert.equal(tuple.parentConfig.configurable.checkpoint_id, first.id);
      assert.deepEqual(tuple.checkpoint.channel_values.binary, new Uint8Array([1, 2, 3]));
      assert.deepEqual(
        tuple.pendingWrites.find((w) => w[1] === 'value'),
        ['node-A', 'value', 'first'],
      );
      assert.deepEqual(
        tuple.pendingWrites.find((w) => w[1] === '__error__'),
        ['node-A', '__error__', { error: 'updated error' }],
      );
      assert.equal((await saver.getTuple(saved)).checkpoint.channel_values.text, '合成材料');
      assert.equal(await otherSaver.getTuple(otherC), undefined);
      await assert.rejects(otherSaver.getTuple(c));
      await assert.rejects(otherSaver.put(c, checkpoint(), metadata, {}));
      await assert.rejects(otherSaver.deleteThread(c.configurable.thread_id));
      const [raw] = await pool.execute(
        'SELECT checkpoint_blob,metadata_blob FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
        [tenant, c.configurable.thread_id],
      );
      assert.equal(
        raw.some(
          (row) =>
            row.checkpoint_blob.includes(Buffer.from('fake-runtime-key')) ||
            row.metadata_blob.includes(Buffer.from('fake-runtime-key')),
        ),
        false,
      );
      await otherSaver.put(otherC, checkpoint(), metadata, {});
      assert.equal(
        (await history(saver, { configurable: { tenant_id: tenant } }, { limit: 100 })).every(
          (row) => row.config.configurable.tenant_id === tenant,
        ),
        true,
      );
      await saver.deleteThread(c.configurable.thread_id);
      assert.equal(await saver.getTuple(c), undefined);
      assert.ok(await otherSaver.getTuple(otherC));
    } finally {
      try {
        await saver.deleteThread(c.configurable.thread_id);
        await otherSaver.deleteThread(otherC.configurable.thread_id);
      } finally {
        await pool.end();
      }
    }
  },
);

test(
  '真实历史筛选和游标跨页，namespace 原样参数化，待写入失败不留下部分数据',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000103',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    try {
      await setupWorkflowStore(pool);
      let parent = c;
      const checkpoints = [];
      for (let i = 0; i < 56; i++) {
        const cp = checkpoint();
        checkpoints.push(cp);
        parent = await saver.put(
          parent,
          cp,
          { ...metadata, step: i, source: i === 0 ? 'input' : 'loop', tag: i % 2 ? 'odd' : 'even' },
          {},
        );
      }
      assert.equal((await history(saver, c, { limit: 56 })).length, 56);
      assert.equal((await history(saver, c, { limit: 56, filter: { source: 'loop' } })).length, 55);
      assert.equal((await history(saver, c, { limit: 10, filter: { tag: 'odd' } })).length, 10);
      assert.equal((await history(saver, c, { limit: 56, before: parent })).length, 55);
      const namespace = "node' OR 1=1 --";
      const ns = { configurable: { ...c.configurable, checkpoint_ns: namespace } };
      await saver.put(ns, checkpoint(), metadata, {});
      assert.equal((await history(saver, ns)).length, 1);
      await assert.rejects(
        saver.putWrites(
          parent,
          [
            ['valid', 'value'],
            ['bad', { apiKey: 'fake-secret' }],
          ],
          'atomic',
        ),
        /凭据/,
      );
      assert.deepEqual((await saver.getTuple(parent)).pendingWrites, []);
      await saver.putWrites(parent, [['valid', 'single write']], 'atomic');
      assert.equal((await saver.getTuple(parent)).pendingWrites.length, 1);
    } finally {
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
      }
    }
  },
);

test(
  '独立 Node 进程暂停、退出、恢复；完成后重投不重复节点副作用',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000104',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-graph-probe-')),
      counter = join(dir, 'effects.txt');
    try {
      await setupWorkflowStore(pool);
      const paused = await child('start', tenant, task, counter);
      assert.equal(paused.code, 0);
      assert.deepEqual(paused.result.next, ['humanReview']);
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\n');
      const reloaded = await child('inspect', tenant, task, counter);
      assert.equal(reloaded.code, 0);
      assert.deepEqual(reloaded.result.next, ['humanReview']);
      assert.equal(reloaded.result.values.prepared, true);
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\n');
      const repeatedStart = await child('start', tenant, task, counter);
      assert.equal(repeatedStart.code, 0);
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\n');
      const resumed = await child('resume', tenant, task, counter);
      assert.equal(resumed.code, 0);
      assert.deepEqual(resumed.result.next, []);
      assert.equal(resumed.result.values.report, 'synthetic result reviewed');
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\nreport\n');
      const late = await child('resume', tenant, task, counter);
      assert.equal(late.error, 'NOT_WAITING');
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\nreport\n');
    } finally {
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
        rmSync(dir, { recursive: true, force: true });
      }
    }
  },
);

test(
  '跨 Node 进程执行锁拒绝同时恢复，进程崩溃后锁释放且快照仍可恢复',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool();
    const tenant = '910000105',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      c = config(tenant, task);
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-graph-lock-')),
      counter = join(dir, 'effects.txt');
    let holder;
    try {
      await setupWorkflowStore(pool);
      assert.equal((await child('start', tenant, task, counter)).code, 0);
      holder = fork(childPath, ['hold', tenant, task, counter], {
        env: { ...process.env, AI_WORKFLOW_DATABASE_URL: url },
        stdio: ['ignore', 'ignore', 'ignore', 'ipc'],
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Lock child timeout')), 10000);
        holder.once('message', (message) => {
          clearTimeout(timer);
          message.locked ? resolve() : reject(new Error('Lock not held'));
        });
        holder.once('error', reject);
      });
      const competing = await child('resume', tenant, task, counter);
      assert.equal(competing.error, 'BUSY');
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\n');
      const exit = new Promise((resolve) => holder.once('exit', resolve));
      holder.kill('SIGKILL');
      await exit;
      holder = undefined;
      const resumed = await child('resume', tenant, task, counter);
      assert.equal(resumed.code, 0);
      assert.equal(readFileSync(counter, 'utf8'), 'prepare\nreport\n');
      await saver.withThreadLock(c, async (assertHeld) => {
        await assertHeld();
      });
      await assert.rejects(
        saver.withThreadLock(c, async () => {
          throw new Error('intentional operation failure');
        }),
        /intentional/,
      );
      await saver.withThreadLock(c, async (assertHeld) => {
        await assertHeld();
      });
    } finally {
      if (holder) {
        const done = new Promise((resolve) => holder.once('exit', resolve));
        holder.kill('SIGKILL');
        await done;
      }
      try {
        await saver.deleteThread(c.configurable.thread_id);
      } finally {
        await pool.end();
        rmSync(dir, { recursive: true, force: true });
      }
    }
  },
);
