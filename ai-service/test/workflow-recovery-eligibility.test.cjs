require('reflect-metadata');
require('@nestjs/core');
const { test } = require('node:test'),
  assert = require('node:assert/strict');
const { createHash, randomInt } = require('node:crypto');
const {
  WorkflowRecoveryEligibilityController,
} = require('../dist/graphs/workflow-recovery-eligibility.controller');
const {
  WorkflowRecoveryEligibilityService,
  validateWorkflowRecoveryRequest,
  inspectWorkflowRecovery,
} = require('../dist/graphs/workflow-recovery-eligibility.service');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const {
  runContractReview,
  contractIdentity,
  contractOperationHash,
} = require('../dist/graphs/contract-review.graph');
const {
  runDocumentGeneration,
  generationIdentity,
} = require('../dist/graphs/document-generation.graph');
const {
  runCaseAnalysis,
  analysisIdentity,
  analysisOperationHash,
} = require('../dist/graphs/case-analysis.graph');
const data = require('./support/case-analysis-fixture.cjs');
const { configureInternalJsonBody } = require('../dist/http-body');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const tenant = '910000302',
  evidence = [{ id: '17', text: '合成付款指引。' }];
function value(type, task = '123') {
  let input;
  if (type === 'CONTRACT_REVIEW')
    input = {
      tenantId: tenant,
      taskId: task,
      fileVersionId: '13',
      sha256: 'f'.repeat(64),
      mimeType: 'text/plain',
      attempt: 2,
      stateVersion: 6,
      stance: 'PARTY_A',
      focus: '付款',
      knowledgeBaseId: '8',
      provider: 'test',
      baseUrl: 'https://models.example.test/v1',
      model: 'workflow-recovery-test',
      decisionId: null,
      round: 0,
      instructions: '',
      supplements: [],
      previousReport: null,
    };
  else if (type === 'DOCUMENT_GENERATE')
    input = {
      tenantId: tenant,
      taskId: task,
      caseId: '11',
      documentId: '12',
      sourceVersionId: '13',
      attempt: 2,
      stateVersion: 6,
      title: '催款函',
      baseContent: '甲欠乙100元。',
      instructions: '起草催款函',
      caseContext: JSON.stringify({ title: '合成案件' }),
      modelRevision: '00000000-0000-0000-0000-000000000018',
      provider: 'test',
      baseUrl: 'https://models.example.test/v1',
      model: 'workflow-recovery-test',
    };
  else {
    input = data.input(tenant, task);
    delete input.apiKey;
    input.attempt = 2;
    input.stateVersion = 6;
  }
  return { type, taskId: task, executionProtocol: 2, input };
}
const types = ['CONTRACT_REVIEW', 'DOCUMENT_GENERATE', 'CASE_ANALYSIS'];
function sqlMock({
  operations = [],
  snapshots = [],
  writes = [],
  results = {},
  busy = false,
  release = false,
  fail = false,
} = {}) {
  const calls = [];
  let destroyed = 0,
    released = 0;
  const connection = {
    execute: async (options, args) => {
      const sql = typeof options === 'string' ? options : options.sql;
      calls.push(sql);
      assert.match(sql, /^\s*SELECT\b/i);
      assert.doesNotMatch(sql, /FOR UPDATE|INSERT|UPDATE|DELETE|CREATE|ALTER/i);
      if (fail) throw Error('database offline');
      if (sql.includes('GET_LOCK')) return [[{ acquired: busy ? 0 : 1 }]];
      if (sql.includes('RELEASE_LOCK')) return [[{ released: release ? 0 : 1 }]];
      if (sql.includes('SELECT operation_key,input_hash,status')) return [operations];
      if (sql.includes('DISTINCT checkpoint_ns'))
        return [
          [...new Set(snapshots.map((r) => r.checkpoint_ns))].map((checkpoint_ns) => ({
            checkpoint_ns,
          })),
        ];
      if (sql.includes('FROM workflow_checkpoint_write')) return [writes];
      if (sql.includes('FROM workflow_checkpoint')) return [snapshots];
      if (sql.includes('FROM workflow_operation')) return [[results[args[2]]].filter(Boolean)];
      assert.fail('unexpected query');
    },
    release: () => released++,
    destroy: () => destroyed++,
  };
  return { getConnection: async () => connection, calls, counts: () => ({ destroyed, released }) };
}
test('探针严格固定领域输入并在SQL前拒绝旧协议、未知字段、身份冲突及递归凭据', async () => {
  for (const type of types) {
    const v = value(type);
    assert.equal(validateWorkflowRecoveryRequest(v), v);
    for (const patch of [
      { executionProtocol: 1 },
      { executionProtocol: '2' },
      { taskId: '124' },
      { unknown: true },
      { input: { ...v.input, tenantId: '9223372036854775808' } },
      { input: { ...v.input, apiKey: 'secret' } },
      { input: { ...v.input, provider: 'test', baseUrl: 'https://user:secret@example.test/v1' } },
      { input: { ...v.input, extra: { authorization: 'secret' } } },
      { input: { ...v.input, attempt: 2147483648 } },
      type === 'CONTRACT_REVIEW'
        ? { input: { ...v.input, fileVersionId: '9223372036854775808' } }
        : type === 'DOCUMENT_GENERATE'
          ? { input: { ...v.input, sourceVersionId: '9223372036854775808' } }
          : {
              input: {
                ...v.input,
                sources: [{ ...v.input.sources[0], fileVersionId: '9223372036854775808' }],
              },
            },
    ])
      assert.throws(() => validateWorkflowRecoveryRequest({ ...v, ...patch }));
  }
  let calls = 0;
  const controller = new WorkflowRecoveryEligibilityController({
    check: async () => {
      calls++;
      return { status: 'SAFE' };
    },
  });
  await assert.rejects(controller.check('wrong', value(types[0])), (e) => e.getStatus() === 401);
  await assert.rejects(controller.check(undefined, value(types[0])), (e) => e.getStatus() === 401);
  await assert.rejects(
    controller.check(process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod', {
      ...value(types[0]),
      input: { ...value(types[0]).input, previousReport: { encrypted_key: 'secret' } },
    }),
    (e) => e.getStatus() === 400,
  );
  assert.equal(calls, 0);
});
test('空协议2日志只读SAFE，锁忙、不确定、FAILED、释放锁故障及超限failclosed', async () => {
  for (const type of types) {
    const db = sqlMock();
    assert.deepEqual(await inspectWorkflowRecovery(db, value(type)), { status: 'SAFE' });
    assert.equal(db.counts().released, 1);
    assert.equal(db.counts().destroyed, 0);
    assert(db.calls.some((s) => s.includes('GET_LOCK')));
  }
  const v = value('DOCUMENT_GENERATE'),
    hash = generationIdentity(v.input);
  for (const status of ['STARTED', 'UNKNOWN', 'FAILED']) {
    const db = sqlMock({
      operations: [{ operation_key: 'document-draft-v1', input_hash: hash, status }],
    });
    assert.deepEqual(await inspectWorkflowRecovery(db, v), {
      status: status === 'FAILED' ? 'FAILED' : 'UNCERTAIN',
    });
    assert.equal(
      db.calls.some((s) => s.includes('result_blob')),
      false,
    );
  }
  for (const [opts, status] of [
    [{ busy: true }, 'BUSY'],
    [{ release: true }, 'UNAVAILABLE'],
    [{ fail: true }, 'UNAVAILABLE'],
    [
      { operations: [{ operation_key: 'unknown-v1', input_hash: hash, status: 'SUCCEEDED' }] },
      'INPUT_CHANGED',
    ],
    [
      {
        operations: Array.from({ length: 2 }, () => ({
          operation_key: 'document-draft-v1',
          input_hash: hash,
          status: 'SUCCEEDED',
        })),
      },
      'UNAVAILABLE',
    ],
  ]) {
    const db = sqlMock(opts);
    assert.deepEqual(await inspectWorkflowRecovery(db, v), { status });
  }
});
test('新内部HTTP路径支持大于256KiB合法中文，仅本路径限8MiB且错误secret/夹Key不进入检查', async () => {
  const { Module, Controller, Post, Body } = require('@nestjs/common'),
    { NestFactory } = require('@nestjs/core');
  class Ordinary {
    accept(body) {
      return body;
    }
  }
  Controller('/ordinary')(Ordinary);
  Post()(
    Ordinary.prototype,
    'accept',
    Object.getOwnPropertyDescriptor(Ordinary.prototype, 'accept'),
  );
  Body()(Ordinary.prototype, 'accept', 0);
  let calls = 0;
  class ProbeModule {}
  Module({
    controllers: [WorkflowRecoveryEligibilityController, Ordinary],
    providers: [
      {
        provide: WorkflowRecoveryEligibilityService,
        useValue: {
          check: async () => {
            calls++;
            return { status: 'SAFE' };
          },
        },
      },
    ],
  })(ProbeModule);
  const app = await NestFactory.create(ProbeModule, { bodyParser: false, logger: false });
  configureInternalJsonBody(app);
  await app.listen(0, '127.0.0.1');
  const address = app.getHttpServer().address(),
    url = `http://127.0.0.1:${address.port}`;
  const v = value('CASE_ANALYSIS');
  v.input.sources = [
    {
      ...v.input.sources[0],
      text: '中'.repeat(40000),
      segments: Array.from({ length: 5000 }, (_, n) => ({
        kind: 'PARAGRAPH',
        index: n + 1,
        startOffset: n,
        endOffset: n + 1,
      })),
    },
  ];
  const body = JSON.stringify(v);
  assert(Buffer.byteLength(body) > 256 * 1024);
  validateWorkflowRecoveryRequest(v);
  const send = (
    path,
    data,
    secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
  ) =>
    fetch(url + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Secret': secret },
      body: data,
    });
  try {
    const ok = await send('/internal/v1/workflow-recovery/eligibility', body);
    assert.equal(ok.status, 200);
    assert.deepEqual(await ok.json(), { status: 'SAFE' });
    assert.equal(calls, 1);
    assert.equal((await send('/ordinary', body)).status, 413);
    assert.equal(
      (await send('/internal/v1/workflow-recovery/eligibility', body, 'wrong')).status,
      401,
    );
    assert.equal(
      (
        await send(
          '/internal/v1/workflow-recovery/eligibility',
          JSON.stringify({ ...v, input: { ...v.input, apiKey: 'secret' } }),
        )
      ).status,
      400,
    );
    assert.equal(
      (
        await send(
          '/internal/v1/workflow-recovery/eligibility',
          JSON.stringify({ ...v, padding: '中'.repeat(3 * 1024 * 1024) }),
        )
      ).status,
      413,
    );
    assert.equal(calls, 1);
  } finally {
    await app.close();
  }
});
function fixture(type) {
  const url =
      process.env.AI_WORKFLOW_DATABASE_URL ??
      'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai',
    parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  assert.equal(parsed.username, 'lexflow_ai');
  const pool = workflowPool(url),
    request = value(type, String(Date.now()) + randomInt(1000, 9999)),
    saver = new TenantMySqlCheckpointer(pool, tenant),
    thread = workflowThreadId(tenant, request.taskId);
  return {
    pool,
    request,
    saver,
    thread,
    cleanup: async () => {
      try {
        await saver.deleteThread(thread);
        for (const table of [
          'workflow_thread',
          'workflow_operation',
          'workflow_checkpoint',
          'workflow_checkpoint_write',
        ]) {
          const [rows] = await pool.execute(
            `SELECT COUNT(*) AS n FROM ${table} WHERE tenant_id=? AND thread_id=?`,
            [tenant, thread],
          );
          assert.equal(Number(rows[0].n), 0);
        }
      } finally {
        await pool.end();
      }
    },
  };
}
async function fingerprint(f) {
  const all = [];
  for (const table of [
    'workflow_thread',
    'workflow_checkpoint',
    'workflow_checkpoint_write',
    'workflow_operation',
  ]) {
    const [rows] = await f.pool.execute(
      `SELECT * FROM ${table} WHERE tenant_id=? AND thread_id=?`,
      [tenant, f.thread],
    );
    all.push(rows);
  }
  return createHash('sha256').update(JSON.stringify(all)).digest('hex');
}
async function probe(f, request = f.request) {
  const before = await fingerprint(f);
  const result = await inspectWorkflowRecovery(f.pool, request);
  assert.deepEqual(Object.keys(result), ['status']);
  assert.equal(await fingerprint(f), before, 'probe must not write workflow SQL');
  return result;
}
test(
  '真实AI SQL生成缓存恢复保持原thread、复用实际identity且不写日志',
  { skip: !enabled },
  async () => {
    const f = fixture('DOCUMENT_GENERATE');
    let calls = 0;
    try {
      await setupWorkflowStore(f.pool);
      await runDocumentGeneration({ ...f.request.input, apiKey: 'synthetic-only' }, f.saver, {
        assertAuthorized: async () => {},
        model: {
          invoke: async () => {
            calls++;
            return { content: JSON.stringify({ content: '待复核草稿', missingInformation: [] }) };
          },
        },
      });
      assert.deepEqual(await probe(f), { status: 'SAFE' });
      assert.deepEqual(
        await probe(f, {
          ...f.request,
          input: { ...f.request.input, attempt: 3, stateVersion: 8 },
        }),
        { status: 'SAFE' },
      );
      assert.deepEqual(
        await probe(f, { ...f.request, input: { ...f.request.input, instructions: '改变要求' } }),
        { status: 'INPUT_CHANGED' },
      );
      await f.pool.execute(
        'DELETE FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=?',
        [tenant, f.thread],
      );
      await f.pool.execute('DELETE FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?', [
        tenant,
        f.thread,
      ]);
      assert.deepEqual(await probe(f), { status: 'SAFE' });
      await runDocumentGeneration(
        { ...f.request.input, apiKey: 'synthetic-only', attempt: 3, stateVersion: 8 },
        new TenantMySqlCheckpointer(f.pool, tenant),
        {
          assertAuthorized: async () => {},
          model: { invoke: () => assert.fail('cached result must be reused') },
        },
      );
      assert.equal(calls, 1);
      await f.pool.execute(
        "UPDATE workflow_operation SET status='STARTED' WHERE tenant_id=? AND thread_id=?",
        [tenant, f.thread],
      );
      assert.deepEqual(await probe(f), { status: 'UNCERTAIN' });
      await f.pool.execute(
        "UPDATE workflow_operation SET status='FAILED' WHERE tenant_id=? AND thread_id=?",
        [tenant, f.thread],
      );
      assert.deepEqual(await probe(f), { status: 'FAILED' });
    } finally {
      await f.cleanup();
    }
  },
);
test(
  '真实AI SQL案件三节点核对实际dependency hash，决定和快照不能丢失或换材料',
  { skip: !enabled },
  async () => {
    const f = fixture('CASE_ANALYSIS');
    let calls = 0;
    try {
      await setupWorkflowStore(f.pool);
      await runCaseAnalysis({ ...f.request.input, apiKey: 'synthetic-only' }, f.saver, {
        assertAuthorized: async () => {},
        model: {
          invoke: async () => ({
            content: JSON.stringify(++calls === 1 ? data.extraction() : data.assessment(true)),
          }),
        },
      });
      assert.deepEqual(await probe(f), { status: 'SAFE' });
      assert.equal(calls, 3);
      const request = {
        ...f.request,
        input: { ...f.request.input, decisionId: '12345678-1234-1234-1234-123456789abc' },
      };
      assert.deepEqual(await probe(f, request), { status: 'SAFE' });
      await f.pool.execute(
        'UPDATE workflow_operation SET input_hash=? WHERE tenant_id=? AND thread_id=? AND operation_key=?',
        [analysisIdentity(f.request.input), tenant, f.thread, 'case-disputes-v1'],
      );
      assert.deepEqual(await probe(f), { status: 'INPUT_CHANGED' });
      await f.pool.execute(
        'UPDATE workflow_operation SET input_hash=? WHERE tenant_id=? AND thread_id=? AND operation_key=?',
        [
          analysisOperationHash(
            analysisIdentity(f.request.input),
            (await f.saver.getTuple({ configurable: { tenant_id: tenant, thread_id: f.thread } }))
              .checkpoint.channel_values.extracted,
          ),
          tenant,
          f.thread,
          'case-disputes-v1',
        ],
      );
      await f.pool.execute(
        'DELETE FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=?',
        [tenant, f.thread],
      );
      await f.pool.execute('DELETE FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?', [
        tenant,
        f.thread,
      ]);
      assert.deepEqual(await probe(f, request), { status: 'UNAVAILABLE' });
      assert.deepEqual(await probe(f), { status: 'SAFE' });
    } finally {
      await f.cleanup();
    }
  },
);
test(
  '真实AI SQL合同原轮及补材轮核对每节点、缓存引用活性，旧轮STARTED/FAILED依然禁恢复',
  { skip: !enabled },
  async () => {
    const f = fixture('CONTRACT_REVIEW');
    let calls = 0;
    const risk = {
      risks: [
        {
          clauseId: '1',
          level: 'HIGH',
          reason: '核实验收',
          suggestion: '补验收材料',
          evidenceIds: ['17'],
        },
      ],
      missingMaterials: ['验收凭证'],
      insufficientEvidence: false,
      basisConflicts: [],
    };
    const runtime = {
      assertAuthorized: async () => {},
      parse: async () => '验收后付款。',
      retrieve: async () => evidence,
      model: {
        invoke: async () => ({
          content: JSON.stringify(
            ++calls === 1
              ? {
                  contractType: '服务合同',
                  clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }],
                }
              : risk,
          ),
        }),
      },
    };
    try {
      await setupWorkflowStore(f.pool);
      const first = await runContractReview(
        { ...f.request.input, apiKey: 'synthetic-only' },
        f.saver,
        runtime,
      );
      assert.deepEqual(await probe(f), { status: 'UNAVAILABLE' });
      f.request.verifiedEvidence = evidence;
      assert.deepEqual(await probe(f), { status: 'SAFE' });
      assert.deepEqual(
        await probe(f, { ...f.request, verifiedEvidence: [{ id: '17', text: '旧引用' }] }),
        { status: 'UNAVAILABLE' },
      );
      const source = {
        parseTaskId: '201',
        fileVersionId: '202',
        sha256: 'a'.repeat(64),
        fileName: '验收说明.txt',
        text: '验收已完成。',
        segments: [{ kind: 'PARAGRAPH', index: 1, startOffset: 0, endOffset: 6 }],
      };
      let request = {
        ...f.request,
        input: {
          ...f.request.input,
          round: 1,
          instructions: '补验收材料',
          supplements: [source],
          previousReport: first.report,
        },
      };
      const second = await runContractReview(
        { ...request.input, apiKey: 'synthetic-only' },
        f.saver,
        runtime,
      );
      assert.deepEqual(await probe(f, request), { status: 'SAFE' });
      request = {
        ...request,
        input: { ...request.input, round: 2, previousReport: second.report },
      };
      await runContractReview({ ...request.input, apiKey: 'synthetic-only' }, f.saver, runtime);
      assert.deepEqual(await probe(f, request), { status: 'SAFE' });
      assert.equal(calls, 7);
      const [namespaces] = await f.pool.query(
        'SELECT DISTINCT checkpoint_ns FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
        [tenant, f.thread],
      );
      assert.equal(namespaces.length, 3);
      await f.pool.execute(
        "UPDATE workflow_operation SET status='STARTED' WHERE tenant_id=? AND thread_id=? AND operation_key='extract-clauses-v1'",
        [tenant, f.thread],
      );
      assert.deepEqual(await probe(f, request), { status: 'UNCERTAIN' });
      await f.pool.execute(
        "UPDATE workflow_operation SET status='FAILED' WHERE tenant_id=? AND thread_id=? AND operation_key='extract-clauses-v1'",
        [tenant, f.thread],
      );
      assert.deepEqual(await probe(f, request), { status: 'FAILED' });
      await f.pool.execute(
        "UPDATE workflow_operation SET status='SUCCEEDED' WHERE tenant_id=? AND thread_id=? AND operation_key='extract-clauses-v1'",
        [tenant, f.thread],
      );
      await f.pool.execute(
        'UPDATE workflow_operation SET input_hash=? WHERE tenant_id=? AND thread_id=? AND operation_key=?',
        [contractIdentity(request.input), tenant, f.thread, 'analyze-risk-v1-round-2'],
      );
      assert.deepEqual(await probe(f, request), { status: 'INPUT_CHANGED' });
    } finally {
      await f.cleanup();
    }
  },
);
test(
  '真实AI SQL同线程锁忙、非法存储分区和超大结果拒绝且释放探针自己的锁',
  { skip: !enabled },
  async () => {
    const f = fixture('DOCUMENT_GENERATE');
    let holder;
    try {
      await setupWorkflowStore(f.pool);
      const lock =
        'lf_graph_' +
        createHash('sha256').update(`${tenant}:${f.thread}`).digest('hex').slice(0, 48);
      holder = await f.pool.getConnection();
      await holder.execute('SELECT GET_LOCK(?,0)', [lock]);
      assert.deepEqual(await probe(f), { status: 'BUSY' });
      await holder.execute('SELECT RELEASE_LOCK(?)', [lock]);
      holder.release();
      holder = undefined;
      await runDocumentGeneration({ ...f.request.input, apiKey: 'synthetic-only' }, f.saver, {
        assertAuthorized: async () => {},
        model: {
          invoke: async () => ({
            content: JSON.stringify({ content: '草稿', missingInformation: [] }),
          }),
        },
      });
      await f.pool.execute(
        'UPDATE workflow_checkpoint SET checkpoint_ns=? WHERE tenant_id=? AND thread_id=?',
        ['forged|', tenant, f.thread],
      );
      assert.deepEqual(await probe(f), { status: 'INPUT_CHANGED' });
      await f.pool.execute(
        'UPDATE workflow_checkpoint SET checkpoint_ns=? WHERE tenant_id=? AND thread_id=?',
        ['', tenant, f.thread],
      );
      await f.pool.execute(
        'UPDATE workflow_operation SET result_blob=? WHERE tenant_id=? AND thread_id=?',
        [Buffer.alloc(2 * 1024 * 1024 + 1, 32), tenant, f.thread],
      );
      assert.deepEqual(await probe(f), { status: 'UNAVAILABLE' });
      const connection = await f.pool.getConnection();
      try {
        const [rows] = await connection.execute('SELECT GET_LOCK(?,0) AS acquired', [lock]);
        assert.equal(Number(rows[0].acquired), 1);
        await connection.execute('SELECT RELEASE_LOCK(?)', [lock]);
      } finally {
        connection.release();
      }
    } finally {
      if (holder) {
        holder.destroy();
        holder.release();
      }
      await f.cleanup();
    }
  },
);
