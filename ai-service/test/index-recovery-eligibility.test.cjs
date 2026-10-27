require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash, randomInt } = require('node:crypto');
const { Module } = require('@nestjs/common');
const { NestFactory } = require('@nestjs/core');
const {
  IndexRecoveryEligibilityController,
} = require('../dist/rag/index-recovery-eligibility.controller');
const {
  IndexRecoveryEligibilityService,
  inspectIndexRecovery,
  validateIndexRecoveryRequest,
} = require('../dist/rag/index-recovery-eligibility.service');
const { knowledgeIndexIdentity } = require('../dist/rag/knowledge-index-workflow.service');
const { workflowThreadId } = require('../dist/graphs/persistence/mysql-checkpointer');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { configureInternalJsonBody } = require('../dist/http-body');
const { fixture } = require('./support/index-child.cjs');
const secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';

function request(tenant = '7', task = '123') {
  const { input } = fixture(tenant, task);
  const { apiKey, ...withoutKey } = input;
  return { taskId: task, input: withoutKey };
}
function row(value, batch, status = 'SUCCEEDED') {
  const texts = value.input.chunks.slice(batch * 50, (batch + 1) * 50).map((chunk) => chunk.text);
  return {
    operation_key: `index-embedding-v1-${batch}`,
    status,
    input_hash: createHash('sha256')
      .update(JSON.stringify([knowledgeIndexIdentity(value.input), texts]))
      .digest('hex'),
  };
}
function database(rows = [], acquired = 1, failure = null) {
  const calls = [];
  return {
    calls,
    getConnection: async () => ({
      execute: async (statement, values) => {
        assert.equal(statement.timeout, 5000);
        const sql = statement.sql;
        calls.push([sql, values]);
        assert.match(sql, /^SELECT /);
        assert.doesNotMatch(sql, /result_blob|result_type|INSERT|UPDATE|DELETE|CREATE/);
        if (sql.includes('GET_LOCK')) return [[{ acquired }]];
        if (sql.includes('RELEASE_LOCK')) return [[{ released: 1 }]];
        if (failure) throw failure;
        assert.match(sql, /tenant_id=\? AND thread_id=\?/);
        return [rows];
      },
      release: () => calls.push(['release']),
      destroy: () => calls.push(['destroy']),
    }),
  };
}
function safe(result) {
  assert.deepEqual(Object.keys(result), ['status']);
  assert.ok(!JSON.stringify(result).includes('合成条款'));
}

test('恢复探针先鉴权，非法材料及运行时凭据不进入SQL；200批上限兼容索引任务', async () => {
  let calls = 0;
  const controller = new IndexRecoveryEligibilityController({
    check: async () => {
      calls++;
      return { status: 'SAFE' };
    },
  });
  for (const supplied of [undefined, [], secret + 'extra'])
    await assert.rejects(
      controller.check(supplied, request()),
      (error) => error.getStatus() === 401,
    );
  const valid = request();
  const invalid = [
    null,
    [],
    { ...valid, taskId: '0' },
    { ...valid, taskId: '9223372036854775808' },
    ...[
      { tenantId: '-1' },
      { scope: 'TENANT', caseId: '8' },
      { scope: 'CASE', caseId: null },
      { scope: 'OTHER' },
      { configurationRevision: undefined },
      { configurationRevision: ' ' },
      { configurationRevision: 'x'.repeat(81) },
      { apiKey: 'must-not-enter' },
      { provider: '' },
      { baseUrl: 'https://user:secret@example.test/v1' },
      { baseUrl: 'http://example.test/v1' },
      { baseUrl: 'https://example.test/v1?secret=hidden' },
      { indexVersion: 2147483648 },
      { chunks: [] },
      {
        chunks: [
          { id: '1', text: 'x' },
          { id: '1', text: 'y' },
        ],
      },
      { chunks: [{ id: '1', text: 'x'.repeat(1001) }] },
      { chunks: Array.from({ length: 10001 }, (_, i) => ({ id: String(i + 1), text: 'x' })) },
    ].map((change) => ({ ...valid, input: { ...valid.input, ...change } })),
  ];
  for (const input of invalid) {
    assert.throws(() => validateIndexRecoveryRequest(input));
    await assert.rejects(controller.check(secret, input), (error) => {
      assert.equal(error.getStatus(), 400);
      safe({ status: 'INVALID' });
      assert.ok(!JSON.stringify(error.getResponse()).includes('hidden'));
      return true;
    });
  }
  const maximum = {
    ...valid,
    input: {
      ...valid.input,
      chunks: Array.from({ length: 10000 }, (_, i) => ({ id: String(i + 1), text: 'x' })),
    },
  };
  assert.equal(validateIndexRecoveryRequest(maximum).input.chunks.length, 10000);
  assert.deepEqual(await controller.check(secret, valid), { status: 'SAFE' });
  assert.equal(calls, 1);
});

test('探针按原索引批次摘要复用成功结果，未知状态/失败/输入变化不能报告安全', async () => {
  const value = request();
  for (const [rows, status] of [
    [[], 'SAFE'],
    [[row(value, 0), row(value, 1)], 'SAFE'],
    [[row(value, 0, 'STARTED')], 'UNCERTAIN'],
    [[row(value, 0, 'FAILED')], 'FAILED'],
    [[row(value, 0, 'FAILED'), row(value, 1, 'STARTED')], 'UNCERTAIN'],
    [[row(value, 0, 'UNKNOWN')], 'UNCERTAIN'],
    [[{ ...row(value, 0), input_hash: '0'.repeat(64) }], 'INPUT_CHANGED'],
    [[row(value, 2)], 'INPUT_CHANGED'],
    [[{ ...row(value, 0), operation_key: 'index-embedding-v1-00' }], 'INPUT_CHANGED'],
  ]) {
    const db = database(rows);
    const result = await inspectIndexRecovery(db, value);
    safe(result);
    assert.equal(result.status, status);
    assert.equal(db.calls.filter((call) => call[0].includes('RELEASE_LOCK')).length, 1);
    assert.equal(db.calls.at(-1)[0], 'release');
    const read = db.calls.find((call) => call[0].includes('FROM workflow_operation'));
    assert.deepEqual(read[1], ['7', workflowThreadId('7', '123')]);
  }
  const saved = row(value, 0),
    db = database([saved]);
  const changed = { ...value, input: { ...value.input, configurationRevision: 'different' } };
  assert.equal((await inspectIndexRecovery(db, changed)).status, 'INPUT_CHANGED');
  assert.equal(
    knowledgeIndexIdentity(value.input),
    knowledgeIndexIdentity({ ...value.input, executionProtocol: 2, attempt: 12, stateVersion: 99 }),
  );
  const maximum = {
    ...value,
    input: {
      ...value.input,
      chunks: Array.from({ length: 10000 }, (_, i) => ({ id: String(i + 1), text: 'x' })),
    },
  };
  const allBatches = Array.from({ length: 200 }, (_, batch) => row(maximum, batch));
  assert.deepEqual(await inspectIndexRecovery(database(allBatches), maximum), { status: 'SAFE' });
  assert.deepEqual(
    await inspectIndexRecovery(database([...allBatches, row(maximum, 200)]), maximum),
    { status: 'INPUT_CHANGED' },
  );
});

test('正在执行时只返回BUSY，数据库错误与失去锁均不可放行并释放连接', async () => {
  const value = request(),
    busy = database([], 0);
  assert.deepEqual(await inspectIndexRecovery(busy, value), { status: 'BUSY' });
  assert.equal(busy.calls.length, 2);
  assert.equal(busy.calls.at(-1)[0], 'release');
  assert.equal(
    busy.calls[0][1][0],
    'lf_graph_' +
      createHash('sha256')
        .update(`7:${workflowThreadId('7', '123')}`)
        .digest('hex')
        .slice(0, 48),
  );
  const failed = database([], 1, new Error('private-db-error'));
  assert.deepEqual(await inspectIndexRecovery(failed, value), { status: 'UNAVAILABLE' });
  assert.equal(failed.calls.filter((call) => call[0].includes('RELEASE_LOCK')).length, 1);
  assert.equal(failed.calls.filter((call) => call[0] === 'destroy').length, 1);
  assert.deepEqual(
    await inspectIndexRecovery(
      {
        getConnection: async () => {
          throw new Error('unavailable');
        },
      },
      value,
    ),
    { status: 'UNAVAILABLE' },
  );
  const lost = database();
  const get = lost.getConnection;
  lost.getConnection = async () => {
    const connection = await get();
    const execute = connection.execute;
    connection.execute = async (statement, values) =>
      statement.sql.includes('RELEASE_LOCK') ? [[{ released: null }]] : execute(statement, values);
    return connection;
  };
  assert.deepEqual(await inspectIndexRecovery(lost, value), { status: 'UNAVAILABLE' });
  assert.equal(lost.calls.at(-1)[0], 'release');
  assert.equal(lost.calls.filter((call) => call[0] === 'destroy').length, 1);
});

test('实际HTTP探针接受大于256KB的合法材料，其他接口与16MB上限仍拒绝超量', async () => {
  let calls = 0;
  class TestModule {}
  Module({
    controllers: [IndexRecoveryEligibilityController],
    providers: [
      {
        provide: IndexRecoveryEligibilityService,
        useValue: {
          check: async () => {
            calls++;
            return { status: 'SAFE' };
          },
        },
      },
    ],
  })(TestModule);
  const app = await NestFactory.create(TestModule, { bodyParser: false, logger: false });
  configureInternalJsonBody(app);
  try {
    await app.listen(0, '127.0.0.1');
    const url = await app.getUrl();
    const value = request();
    value.input.chunks = Array.from({ length: 600 }, (_, i) => ({
      id: String(i + 1),
      text: '中'.repeat(1000),
    }));
    const body = JSON.stringify(value);
    assert.ok(Buffer.byteLength(body) > 256 * 1024);
    const response = await fetch(url + '/internal/v1/index-recovery/eligibility', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-internal-secret': secret },
      body,
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'SAFE' });
    assert.equal(calls, 1);
    const denied = await fetch(url + '/internal/v1/index-recovery/eligibility', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    assert.equal(denied.status, 401);
    await denied.body?.cancel();
    assert.equal(calls, 1);
    const other = await fetch(url + '/internal/v1/knowledge/rerank', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    assert.equal(other.status, 413);
    await other.body?.cancel();
    const oversized = await fetch(url + '/internal/v1/index-recovery/eligibility', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ padding: 'x'.repeat(16 * 1024 * 1024) }),
    });
    assert.equal(oversized.status, 413);
    await oversized.body?.cancel();
    assert.equal(calls, 1);
  } finally {
    await app.close();
  }
});

test(
  '真实AI SQL：探针不写journal，执行锁占用时拒绝，同task新attempt保持原批次资格',
  { skip: process.env.LEXFLOW_WORKFLOW_INTEGRATION !== 'true' },
  async () => {
    const databaseUrl =
      process.env.AI_WORKFLOW_DATABASE_URL ??
      'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
    const url = new URL(databaseUrl);
    assert.equal(url.hostname, '127.0.0.1');
    assert.equal(url.port, '13306');
    assert.equal(url.pathname, '/lexflow_ai');
    assert.equal(url.username, 'lexflow_ai');
    const pool = workflowPool(databaseUrl),
      tenant = '910000225',
      task = String(Date.now()) + randomInt(1000, 9999),
      value = request(tenant, task),
      thread = workflowThreadId(tenant, task);
    let owned = false;
    try {
      const [before] = await pool.execute(
        'SELECT COUNT(*) count FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, thread],
      );
      assert.equal(Number(before[0].count), 0);
      const [beforeThreads] = await pool.execute(
        'SELECT COUNT(*) count FROM workflow_thread WHERE tenant_id=? AND thread_id=?',
        [tenant, thread],
      );
      assert.equal(Number(beforeThreads[0].count), 0);
      owned = true;
      assert.deepEqual(await inspectIndexRecovery(pool, value), { status: 'SAFE' });
      const [after] = await pool.execute(
        'SELECT COUNT(*) count FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, thread],
      );
      assert.equal(Number(after[0].count), 0);
      const [threads] = await pool.execute(
        'SELECT COUNT(*) count FROM workflow_thread WHERE tenant_id=? AND thread_id=?',
        [tenant, thread],
      );
      assert.equal(Number(threads[0].count), 0);
      for (const saved of [row(value, 0), row(value, 1)])
        await pool.execute(
          "INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token) VALUES(?,?,?,?,?,'eligibility-test-only')",
          [tenant, thread, saved.operation_key, saved.input_hash, saved.status],
        );
      assert.deepEqual(
        await inspectIndexRecovery(pool, {
          ...value,
          input: { ...value.input, executionProtocol: 2, attempt: 1 },
        }),
        { status: 'SAFE' },
      );
      const changed = { ...value, input: { ...value.input, configurationRevision: 'changed' } };
      assert.deepEqual(await inspectIndexRecovery(pool, changed), { status: 'INPUT_CHANGED' });
      await pool.execute(
        "UPDATE workflow_operation SET status='STARTED' WHERE tenant_id=? AND thread_id=? AND operation_key='index-embedding-v1-1'",
        [tenant, thread],
      );
      assert.deepEqual(await inspectIndexRecovery(pool, value), { status: 'UNCERTAIN' });
      const holder = await pool.getConnection();
      const name =
        'lf_graph_' + createHash('sha256').update(`${tenant}:${thread}`).digest('hex').slice(0, 48);
      try {
        await holder.execute('SELECT GET_LOCK(?,0)', [name]);
        assert.deepEqual(await inspectIndexRecovery(pool, value), { status: 'BUSY' });
      } finally {
        await holder.execute('SELECT RELEASE_LOCK(?)', [name]);
        holder.release();
      }
      const [saved] = await pool.execute(
        'SELECT operation_key,input_hash,status FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, thread],
      );
      assert.equal(saved.length, 2);
      assert.ok(saved.some((item) => item.status === 'STARTED'));
      assert.ok(saved.some((item) => item.status === 'SUCCEEDED'));
    } finally {
      if (owned) {
        await pool.execute('DELETE FROM workflow_operation WHERE tenant_id=? AND thread_id=?', [
          tenant,
          thread,
        ]);
        const [remaining] = await pool.execute(
          'SELECT COUNT(*) count FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
          [tenant, thread],
        );
        assert.equal(Number(remaining[0].count), 0);
      }
      await pool.end();
    }
  },
);
