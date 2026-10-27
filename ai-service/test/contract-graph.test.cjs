const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt } = require('node:crypto');
const { spawn } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const {
  runContractReview,
  validateContractEvidence,
} = require('../dist/graphs/contract-review.graph');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const url =
  process.env.AI_WORKFLOW_DATABASE_URL ??
  'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
function fixture(tenant) {
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  assert.equal(parsed.username, 'lexflow_ai');
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
    focus: '付款条款',
    knowledgeBaseId: null,
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'contract-test',
    apiKey: 'fake-contract-key',
    decisionId: null,
  };
  return {
    pool,
    task,
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
function child(mode, tenant, task, counter) {
  return new Promise((resolve, reject) => {
    const worker = spawn(
      process.execPath,
      [join(__dirname, 'support/contract-child.cjs'), mode, tenant, task, counter],
      { env: { ...process.env, AI_WORKFLOW_DATABASE_URL: url }, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    let output = '';
    worker.stdout.on('data', (b) => (output += b));
    worker.stderr.resume();
    const timer = setTimeout(() => {
      worker.kill('SIGKILL');
      reject(new Error('Contract probe timed out'));
    }, 20000);
    worker.once('error', reject);
    worker.once('exit', (code) => {
      clearTimeout(timer);
      try {
        resolve({ code, ...JSON.parse(output.trim()) });
      } catch {
        reject(new Error('No contract probe output'));
      }
    });
  });
}
test('合同依据编号与文字受限，重复来源拒绝', () => {
  validateContractEvidence([{ id: '17', text: '合成资料' }]);
  for (const value of [
    [
      { id: '17', text: 'x' },
      { id: '17', text: 'y' },
    ],
    [{ id: '0', text: 'x' }],
    [{ id: '17', text: '' }],
  ])
    assert.throws(() => validateContractEvidence(value));
});
test('真实合同图高风险暂停，独立进程复核恢复不重做解析和模型调用', { skip: !enabled }, async () => {
  const f = fixture('910000111'),
    dir = mkdtempSync(join(tmpdir(), 'lexflow-contract-graph-')),
    counter = join(dir, 'effects.txt');
  try {
    await setupWorkflowStore(f.pool);
    const first = await child('start', f.input.tenantId, f.task, counter);
    assert.equal(first.code, 0, first.error);
    assert.equal(first.result.outcome, 'WAITING_HUMAN');
    assert.equal(first.result.report.needsHuman, true);
    const c = first.result.report.clauses[0];
    assert.equal(first.result.report.sourceText.slice(c.startOffset, c.endOffset), c.quote);
    assert.equal(readFileSync(counter, 'utf8'), 'parse\nmodel\nmodel\nmodel\n');
    const duplicate = await child('start', f.input.tenantId, f.task, counter);
    assert.equal(duplicate.result.outcome, 'WAITING_HUMAN');
    const resumed = await child('resume', f.input.tenantId, f.task, counter);
    assert.equal(resumed.code, 0);
    assert.equal(resumed.result.outcome, 'SUCCEEDED');
    const late = await child('resume', f.input.tenantId, f.task, counter);
    assert.equal(late.result.outcome, 'SUCCEEDED');
    assert.equal(readFileSync(counter, 'utf8'), 'parse\nmodel\nmodel\nmodel\n');
    const [rows] = await f.pool.query(
      'SELECT checkpoint_blob,metadata_blob FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
      [f.input.tenantId, workflowThreadId(f.input.tenantId, f.task)],
    );
    assert.equal(
      rows.some(
        (r) =>
          r.checkpoint_blob.includes(Buffer.from(f.input.apiKey)) ||
          r.metadata_blob.includes(Buffer.from(f.input.apiKey)),
      ),
      false,
    );
    await assert.rejects(
      runContractReview({ ...f.input, focus: 'changed' }, f.saver, {
        model: { invoke: async () => assert.fail('changed input') },
        assertAuthorized: async () => {},
        parse: async () => '',
        retrieve: async () => [],
      }),
      /已保存工作流/,
    );
  } finally {
    await f.cleanup();
    rmSync(dir, { recursive: true, force: true });
  }
});
test(
  '缺少依据强制人工复核，合法低风险依据可完成；伪造条款或引用不能生成报告',
  { skip: !enabled },
  async () => {
    for (const scenario of ['no-evidence', 'valid', 'fake-quote', 'fake-citation']) {
      const f = fixture('910000112');
      let count = 0;
      try {
        await setupWorkflowStore(f.pool);
        f.input.knowledgeBaseId = scenario === 'no-evidence' ? null : '8';
        const runtime = {
          assertAuthorized: async () => {},
          parse: async () => '验收后付款。',
          retrieve: async () => [{ id: '17', text: '合成审查资料' }],
          model: {
            invoke: async () => {
              count++;
              return {
                content: JSON.stringify(
                  count === 1
                    ? {
                        contractType: '合同',
                        clauses: [
                          {
                            id: '1',
                            title: '付款',
                            quote: scenario === 'fake-quote' ? '不存在的条款' : '验收后付款。',
                          },
                        ],
                      }
                    : {
                        risks: [
                          {
                            clauseId: '1',
                            level: 'LOW',
                            reason: '条款说明',
                            suggestion: '确认期限',
                            evidenceIds:
                              scenario === 'fake-citation'
                                ? ['999']
                                : scenario === 'valid'
                                  ? ['17']
                                  : [],
                          },
                        ],
                        missingMaterials: [],
                        insufficientEvidence: false,
                      },
                ),
              };
            },
          },
        };
        if (scenario.startsWith('fake-'))
          await assert.rejects(runContractReview(f.input, f.saver, runtime));
        else {
          const result = await runContractReview(f.input, f.saver, runtime);
          assert.equal(result.outcome, scenario === 'valid' ? 'SUCCEEDED' : 'WAITING_HUMAN');
        }
      } finally {
        await f.cleanup();
      }
    }
  },
);
