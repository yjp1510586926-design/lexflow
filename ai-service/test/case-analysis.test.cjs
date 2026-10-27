const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt, createHash } = require('node:crypto');
const { spawn } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  WorkflowOperationUncertainError,
  WorkflowBusyError,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const {
  runCaseAnalysis,
  validateAnalysisInput,
  validateExtracted,
  analysisIdentity,
} = require('../dist/graphs/case-analysis.graph');
const { CaseAnalysisConsumer } = require('../dist/consumers/case-analysis.consumer');
const data = require('./support/case-analysis-fixture.cjs');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const url =
  process.env.AI_WORKFLOW_DATABASE_URL ??
  'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
function fixture() {
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  const tenant = '910000279',
    task = String(Date.now()) + randomInt(1000, 9999),
    pool = workflowPool(url),
    saver = new TenantMySqlCheckpointer(pool, tenant),
    input = data.input(tenant, task);
  return {
    tenant,
    task,
    pool,
    saver,
    input,
    cleanup: async () => {
      try {
        await saver.deleteThread(workflowThreadId(tenant, task));
      } finally {
        await pool.end();
      }
    },
  };
}
function child(mode, f, counter) {
  return new Promise((resolve, reject) => {
    const process = spawn(
      global.process.execPath,
      [join(__dirname, 'support/case-analysis-child.cjs'), mode, f.tenant, f.task, counter],
      {
        env: { ...global.process.env, AI_WORKFLOW_DATABASE_URL: url },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    let output = '';
    process.stdout.on('data', (b) => (output += b));
    process.stderr.resume();
    const timer = setTimeout(() => {
      process.kill('SIGKILL');
      reject(new Error('Case analysis probe timed out'));
    }, 20000);
    process.once('error', reject);
    process.once('exit', (code) => {
      clearTimeout(timer);
      try {
        resolve({ code, ...JSON.parse(output.trim()) });
      } catch {
        reject(new Error('No analysis probe result'));
      }
    });
  });
}
test('案件材料验证固定摘要、页段和总长度，引用只从真实页段计算偏移', () => {
  const i = data.input('1', '2');
  validateAnalysisInput(i);
  const extracted = validateExtracted(data.extraction(), i.sources);
  assert.equal(extracted.citations[0].startOffset, 0);
  assert.equal(extracted.citations[0].fileVersionId, '21');
  const pageText = '第一页。\n\n第二页证据。',
    offset = pageText.indexOf('第二页');
  const pageSource = {
    ...i.sources[0],
    text: pageText,
    segments: [
      { kind: 'PAGE', index: 1, startOffset: 0, endOffset: 4 },
      { kind: 'PAGE', index: 2, startOffset: offset, endOffset: pageText.length },
    ],
  };
  validateAnalysisInput({ ...i, sources: [pageSource] });
  const page = validateExtracted(
    {
      facts: [{ statement: '第二页材料记载', citationIds: ['C2'] }],
      pendingVerification: [],
      citations: [{ id: 'C2', parseTaskId: '31', index: 2, quote: '第二页证据。' }],
    },
    [pageSource],
  ).citations[0];
  assert.equal(page.kind, 'PAGE');
  assert.equal(page.index, 2);
  assert.equal(page.startOffset, offset);
  assert.equal(page.endOffset, pageText.length);
  for (const patch of [
    { sources: [] },
    { sources: [...i.sources, ...i.sources] },
    { sources: [{ ...i.sources[0], sha256: 'bad' }] },
    {
      sources: [
        {
          ...i.sources[0],
          segments: [{ kind: 'PARAGRAPH', index: 1, startOffset: 0, endOffset: 999 }],
        },
      ],
    },
  ])
    assert.throws(() => validateAnalysisInput({ ...i, ...patch }));
  for (const patch of [
    { citations: [{ id: 'C1', parseTaskId: '999', index: 1, quote: data.source.text }] },
    { citations: [{ id: 'C1', parseTaskId: '31', index: 2, quote: data.source.text }] },
    { citations: [{ id: 'C1', parseTaskId: '31', index: 1, quote: '伪造原文' }] },
    { facts: [{ statement: '无来源事实', citationIds: ['fake'] }] },
  ])
    assert.throws(() => validateExtracted({ ...data.extraction(), ...patch }, i.sources));
});
test(
  '真实案件分析图在人工暂停后跨进程恢复、重复消息不重复模型，固定输入变化拒绝',
  { skip: !enabled },
  async () => {
    const f = fixture(),
      dir = mkdtempSync(join(tmpdir(), 'lexflow-analysis-')),
      counter = join(dir, 'calls.txt');
    try {
      await setupWorkflowStore(f.pool);
      const first = await child('start', f, counter);
      assert.equal(first.code, 0, first.error);
      assert.equal(first.result.outcome, 'WAITING_HUMAN');
      assert.equal(first.result.report.needsHuman, true);
      assert.equal(first.result.report.issues[0].level, 'HIGH');
      const repeat = await child('start', f, counter);
      assert.equal(repeat.result.outcome, 'WAITING_HUMAN');
      const resume = await child('resume', f, counter);
      assert.equal(resume.code, 0, resume.error);
      assert.equal(resume.result.outcome, 'SUCCEEDED');
      const late = await child('resume', f, counter);
      assert.equal(late.result.outcome, 'SUCCEEDED');
      assert.equal(readFileSync(counter, 'utf8'), 'model\nmodel\nmodel\n');
      const [rows] = await f.pool.query(
        'SELECT checkpoint_blob,metadata_blob FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
        [f.tenant, workflowThreadId(f.tenant, f.task)],
      );
      assert(rows.length > 0);
      assert.equal(
        rows.some(
          (r) =>
            r.checkpoint_blob.includes(Buffer.from(f.input.apiKey)) ||
            r.metadata_blob.includes(Buffer.from(f.input.apiKey)),
        ),
        false,
      );
      await assert.rejects(
        runCaseAnalysis({ ...f.input, background: '变更背景' }, f.saver, {
          assertAuthorized: async () => {},
          model: { invoke: () => assert.fail('must not call') },
        }),
        /已保存工作流/,
      );
    } finally {
      await f.cleanup();
      rmSync(dir, { recursive: true, force: true });
    }
  },
);
test(
  '真实案件图低风险完成，待核实、缺资料、空事实或证据不足均强制人工复核',
  { skip: !enabled },
  async () => {
    for (const scenario of ['low', 'pending', 'missing', 'insufficient', 'empty']) {
      const f = fixture();
      let calls = 0;
      try {
        await setupWorkflowStore(f.pool);
        const extract = data.extraction(),
          assessment = data.assessment();
        if (scenario === 'pending') extract.pendingVerification = ['收货真实性待核实'];
        if (scenario === 'missing') assessment.missingMaterials = ['收货凭证'];
        if (scenario === 'insufficient') assessment.insufficientEvidence = true;
        if (scenario === 'empty') {
          extract.facts = [];
          extract.citations = [];
          assessment.issues = [];
        }
        const result = await runCaseAnalysis(f.input, f.saver, {
          assertAuthorized: async () => {},
          model: {
            invoke: async () => ({ content: JSON.stringify(++calls === 1 ? extract : assessment) }),
          },
        });
        assert.equal(result.outcome, scenario === 'low' ? 'SUCCEEDED' : 'WAITING_HUMAN');
        assert.equal(calls, 2);
      } finally {
        await f.cleanup();
      }
    }
  },
);
test('真实案件图每次付费步骤前及发布前重查权限，撤销后不继续调用', { skip: !enabled }, async () => {
  for (const at of [1, 2]) {
    const f = fixture();
    let calls = 0,
      allowed = true;
    try {
      await setupWorkflowStore(f.pool);
      await assert.rejects(
        runCaseAnalysis(f.input, f.saver, {
          assertAuthorized: async () => {
            if (!allowed) throw Error('revoked');
          },
          model: {
            invoke: async () => {
              const response = ++calls === 1 ? data.extraction() : data.assessment();
              if (calls === at) allowed = false;
              return { content: JSON.stringify(response) };
            },
          },
        }),
        /权限|操作已失败/,
      );
      assert.equal(calls, at);
      await assert.rejects(
        runCaseAnalysis(f.input, f.saver, {
          assertAuthorized: async () => {
            throw Error('cancelled');
          },
          model: { invoke: () => assert.fail('must not call') },
        }),
        /权限/,
      );
      assert.equal(calls, at);
    } finally {
      await f.cleanup();
    }
  }
});
test(
  '真实案件图遇到已开始但结果不确定的模型操作不重试，执行锁排除并发',
  { skip: !enabled },
  async () => {
    const f = fixture(),
      thread = workflowThreadId(f.tenant, f.task),
      config = { configurable: { tenant_id: f.tenant, thread_id: thread } };
    try {
      await setupWorkflowStore(f.pool);
      const digest = createHash('sha256')
        .update(JSON.stringify([analysisIdentity(f.input), f.input.sources]))
        .digest('hex');
      await f.saver.withThreadLock(config, async () => {
        await f.pool.execute(
          "INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token) VALUES(?,?,'case-facts-v1',?,'STARTED',?)",
          [f.tenant, thread, digest, '12345678-1234-1234-1234-123456789abc'],
        );
        await assert.rejects(
          runCaseAnalysis(f.input, new TenantMySqlCheckpointer(f.pool, f.tenant), {
            assertAuthorized: async () => {},
            model: { invoke: () => assert.fail('busy must not call') },
          }),
          WorkflowBusyError,
        );
      });
      await assert.rejects(
        runCaseAnalysis(f.input, f.saver, {
          assertAuthorized: async () => {},
          model: { invoke: () => assert.fail('uncertain must not call') },
        }),
        WorkflowOperationUncertainError,
      );
      const [rows] = await f.pool.query(
        'SELECT status FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [f.tenant, thread],
      );
      assert.equal(rows[0].status, 'STARTED');
    } finally {
      await f.cleanup();
    }
  },
);
function delivery(status = 'QUEUED') {
  const events = [],
    context = {
      tenantId: '1',
      taskId: '2',
      caseId: '3',
      resourceVersionId: '21',
      type: 'CASE_ANALYSIS',
      status,
      attempt: 0,
      stateVersion: 1,
    };
  const message = {},
    channel = {
      ack: (m) => events.push('ack'),
      nack: (m, a, requeue) => events.push(['nack', requeue]),
    },
    ctx = { getChannelRef: () => channel, getMessage: () => message };
  const payload = {
    schemaVersion: '1.0',
    type: 'CASE_ANALYSIS',
    tenantId: '1',
    taskId: '2',
    caseId: '3',
    resourceVersionId: '21',
    attempt: 0,
    stateVersion: 1,
  };
  return { events, context, ctx, payload };
}
test('案件消费者终态、跨租户、旧状态不进入工作流，不确定执行进入人工核对', async () => {
  for (const status of ['CANCELLED', 'SUCCEEDED', 'WAITING_HUMAN']) {
    const d = delivery(status);
    await new CaseAnalysisConsumer(
      { getContext: async () => d.context },
      { run: () => assert.fail('terminal') },
    ).handle(d.payload, d.ctx);
    assert.deepEqual(d.events, ['ack']);
  }
  const d = delivery();
  let published;
  const consumer = new CaseAnalysisConsumer(
    {
      getContext: async () => d.context,
      analysisResult: async (task, outcome, report) => {
        published = { task, outcome, report };
      },
    },
    {
      run: async (task, claim) => {
        claim({ ...task, status: 'RUNNING', stateVersion: 2 });
        throw new WorkflowOperationUncertainError();
      },
    },
    {
      defer: async (channel, message, queue, kind) => {
        assert.equal(kind, 'UNCERTAIN');
        channel.ack(message);
      },
    },
  );
  await consumer.handle({ ...d.payload, tenantId: '9' }, d.ctx);
  assert.deepEqual(d.events, ['ack']);
  d.events.length = 0;
  await consumer.handle(d.payload, d.ctx);
  assert.equal(published, undefined);
  assert.deepEqual(d.events, ['ack']);
});
test('案件消费者领取前失败或回调失败进入死信，繁忙重复消息只确认', async () => {
  for (const scenario of ['before', 'callback', 'busy']) {
    const d = delivery();
    await new CaseAnalysisConsumer(
      {
        getContext: async () => d.context,
        analysisResult: async () => {
          throw Error('callback');
        },
      },
      {
        run: async (t, claim) => {
          if (scenario === 'busy') throw new WorkflowBusyError();
          if (scenario === 'before') throw Error('begin denied');
          claim({ ...t, stateVersion: 2 });
          return { outcome: 'SUCCEEDED', report: {} };
        },
      },
      {
        defer: async (channel, message, queue, kind) => {
          assert.equal(kind, scenario === 'before' ? 'PERMANENT' : 'UNCERTAIN');
          channel.nack(message, false, false);
        },
      },
    ).handle(d.payload, d.ctx);
    assert.deepEqual(d.events, scenario === 'busy' ? ['ack'] : [['nack', false]]);
  }
});
