const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt } = require('node:crypto');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const { runContractReview } = require('../dist/graphs/contract-review.graph');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
function fixture(tenant) {
  const url =
      process.env.AI_WORKFLOW_DATABASE_URL ??
      'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai',
    parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  const pool = workflowPool(url),
    task = String(Date.now()) + randomInt(1000, 9999),
    saver = new TenantMySqlCheckpointer(pool, tenant);
  const input = {
    tenantId: tenant,
    taskId: task,
    fileVersionId: '123',
    sha256: 'f'.repeat(64),
    mimeType: 'text/plain',
    attempt: 0,
    stateVersion: 2,
    stance: 'PARTY_A',
    focus: '付款',
    knowledgeBaseId: '8',
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'test',
    apiKey: 'synthetic-only',
    decisionId: null,
  };
  return {
    pool,
    saver,
    input,
    thread: workflowThreadId(tenant, task),
    cleanup: async () => {
      try {
        await saver.deleteThread(workflowThreadId(tenant, task));
      } finally {
        await pool.end();
      }
    },
  };
}
const supplement = {
  parseTaskId: '201',
  fileVersionId: '202',
  sha256: 'a'.repeat(64),
  fileName: '验收说明.txt',
  text: '验收于十月一日完成。',
  segments: [{ kind: 'PARAGRAPH', index: 1, startOffset: 0, endOffset: 11 }],
};
supplement.segments[0].endOffset = supplement.text.length;
const evidence = [
  { id: '17', text: '付款指引A。' },
  { id: '18', text: '付款指引B。' },
];
function analysis(missing = [], conflicts = []) {
  return {
    risks: [
      {
        clauseId: '1',
        level: 'LOW',
        reason: '应明确约定',
        suggestion: '明确付款时间',
        evidenceIds: ['17'],
      },
    ],
    missingMaterials: missing,
    insufficientEvidence: false,
    basisConflicts: conflicts,
  };
}
test('补材轮次拒绝缺快照、伪造定位和超过上限，执行前不调用模型', async () => {
  const input = {
    tenantId: '7',
    taskId: '123',
    fileVersionId: '123',
    sha256: 'f'.repeat(64),
    stance: 'NEUTRAL',
    focus: '',
    knowledgeBaseId: null,
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'test',
    decisionId: null,
    round: 1,
    instructions: '',
    supplements: [supplement],
    previousReport: null,
  };
  await assert.rejects(runContractReview(input, {}, {}), /补材审查输入无效/);
  const previous = {
    sourceSha256: input.sha256,
    sourceText: '验收后付款。',
    contractType: '合同',
    clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }],
  };
  for (const bad of [
    { ...input, round: 11 },
    {
      ...input,
      previousReport: previous,
      supplements: [
        { ...supplement, segments: [{ kind: 'PAGE', index: 1, startOffset: -1, endOffset: 1 }] },
      ],
    },
    {
      ...input,
      previousReport: previous,
      supplements: Array.from({ length: 51 }, () => supplement),
    },
  ])
    await assert.rejects(runContractReview(bad, {}, {}));
});
test(
  '真实SQL补材新轮次重新分析、保留原合同；重复恢复复用结果，三轮快照及付费记录隔离且共用执行锁',
  { skip: !enabled },
  async () => {
    const f = fixture('910000281');
    let parse = 0,
      calls = 0,
      retrieval = 0,
      paidData = [];
    const runtime = {
      assertAuthorized: async () => {},
      parse: async () => {
        parse++;
        return '验收后付款。';
      },
      retrieve: async () => {
        retrieval++;
        return evidence;
      },
      model: {
        invoke: async (messages) => {
          calls++;
          paidData.push(messages[1].content);
          return {
            content: JSON.stringify(
              calls === 1
                ? {
                    contractType: '服务合同',
                    clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }],
                  }
                : analysis(calls === 2 ? ['需提供验收说明'] : ['需核对资料']),
            ),
          };
        },
      },
    };
    try {
      await setupWorkflowStore(f.pool);
      const first = await runContractReview(f.input, f.saver, runtime);
      assert.equal(first.outcome, 'WAITING_HUMAN');
      assert.equal(calls, 2);
      let input = {
        ...f.input,
        round: 1,
        instructions: '已补验收说明',
        supplements: [supplement],
        previousReport: first.report,
        stateVersion: 5,
      };
      const second = await runContractReview(input, f.saver, runtime);
      assert.equal(second.outcome, 'WAITING_HUMAN');
      assert.equal(calls, 3);
      assert.equal(parse, 1);
      assert.equal(retrieval, 2);
      assert.ok(paidData[2].includes(supplement.text));
      assert.ok(paidData[2].includes(input.instructions));
      const duplicate = await runContractReview(
        input,
        new TenantMySqlCheckpointer(f.pool, f.input.tenantId),
        runtime,
      );
      assert.deepEqual(duplicate, second);
      assert.equal(calls, 3);
      const resumed = await runContractReview(
        { ...input, decisionId: '12345678-1234-1234-1234-123456789abc' },
        f.saver,
        runtime,
      );
      assert.equal(resumed.outcome, 'SUCCEEDED');
      assert.equal(calls, 3);
      await assert.rejects(
        runContractReview({ ...input, instructions: '换说明' }, f.saver, runtime),
        /已保存工作流/,
      );
      assert.equal(calls, 3);
      input = {
        ...input,
        round: 2,
        decisionId: null,
        previousReport: second.report,
        supplements: [supplement, { ...supplement, parseTaskId: '203', fileVersionId: '204' }],
      };
      await runContractReview(input, f.saver, runtime);
      assert.equal(calls, 4);
      assert.equal(parse, 1);
      const [namespaces] = await f.pool.query(
        'SELECT DISTINCT checkpoint_ns FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=? ORDER BY checkpoint_ns',
        [f.input.tenantId, f.thread],
      );
      assert.deepEqual(
        namespaces.map((r) => r.checkpoint_ns),
        ['', 'contract-round-1|', 'contract-round-2|'],
      );
      const [ops] = await f.pool.query(
        'SELECT operation_key,status FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [f.input.tenantId, f.thread],
      );
      assert.ok(ops.some((r) => r.operation_key === 'analyze-risk-v1-round-1'));
      assert.ok(ops.some((r) => r.operation_key === 'analyze-risk-v1-round-2'));
      assert.ok(ops.every((r) => r.status === 'SUCCEEDED'));
      const [rows] = await f.pool.query(
        'SELECT checkpoint_blob FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
        [f.input.tenantId, f.thread],
      );
      assert.equal(
        rows.some((r) => r.checkpoint_blob.includes(Buffer.from(f.input.apiKey))),
        false,
      );
      let release;
      const gate = new Promise((r) => (release = r)),
        held = f.saver.withThreadLock(
          { configurable: { tenant_id: f.input.tenantId, thread_id: f.thread } },
          () => gate,
        );
      await new Promise((r) => setTimeout(r, 100));
      await assert.rejects(runContractReview(input, f.saver, runtime), /正在执行/);
      release();
      await held;
    } finally {
      await f.cleanup();
    }
  },
);
test(
  '真实SQL低风险但依据冲突进入二次分析和人工复核；二次模型不能静默删除冲突',
  { skip: !enabled },
  async () => {
    const f = fixture('910000282');
    let calls = 0;
    try {
      await setupWorkflowStore(f.pool);
      const runtime = {
        assertAuthorized: async () => {},
        parse: async () => '验收后付款。',
        retrieve: async () => evidence,
        model: {
          invoke: async () => {
            calls++;
            return {
              content: JSON.stringify(
                calls === 1
                  ? {
                      contractType: '合同',
                      clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }],
                    }
                  : analysis(
                      [],
                      calls === 2
                        ? [
                            {
                              description: '资料规定的付款期限不同，需核对适用范围。',
                              evidenceIds: ['17', '18'],
                            },
                          ]
                        : [],
                    ),
              ),
            };
          },
        },
      };
      const result = await runContractReview(f.input, f.saver, runtime);
      assert.equal(calls, 3);
      assert.equal(result.outcome, 'WAITING_HUMAN');
      assert.equal(result.report.basisConflicts.length, 1);
      assert.equal(result.report.needsHuman, true);
      const resumed = await runContractReview(
        { ...f.input, decisionId: '12345678-1234-1234-1234-123456789abc' },
        f.saver,
        runtime,
      );
      assert.equal(resumed.outcome, 'SUCCEEDED');
      assert.equal(calls, 3);
    } finally {
      await f.cleanup();
    }
  },
);
