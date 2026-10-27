// Real graph and MySQL; synthetic model responses only, no provider/network calls.
const { appendFileSync } = require('node:fs');
const { workflowPool } = require('../../dist/graphs/persistence/workflow-pool');
const { TenantMySqlCheckpointer } = require('../../dist/graphs/persistence/mysql-checkpointer');
const { runContractReview } = require('../../dist/graphs/contract-review.graph');
const [mode, tenant, task, counter] = process.argv.slice(2);
const pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
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
  decisionId: mode === 'resume' ? '12345678-1234-1234-1234-123456789abc' : null,
};
let calls = 0;
const analysis = {
  risks: [
    {
      clauseId: '1',
      level: 'HIGH',
      reason: '付款期限不清晰',
      suggestion: '补充明确期限',
      evidenceIds: [],
    },
  ],
  missingMaterials: [],
  insufficientEvidence: false,
};
const model = {
  invoke: async () => {
    calls++;
    appendFileSync(counter, 'model\n');
    return {
      content: JSON.stringify(
        calls === 1
          ? {
              contractType: '服务合同',
              clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }],
            }
          : analysis,
      ),
    };
  },
};
(async () => {
  try {
    const result = await runContractReview(input, new TenantMySqlCheckpointer(pool, tenant), {
      model,
      assertAuthorized: async () => {},
      parse: async () => {
        appendFileSync(counter, 'parse\n');
        return '服务合同\n验收后付款。';
      },
      retrieve: async () => [],
    });
    process.stdout.write(JSON.stringify({ ok: true, result }) + '\n');
  } catch (error) {
    process.stdout.write(
      JSON.stringify({
        ok: false,
        error: String(error.message).replaceAll(input.apiKey, '[hidden]'),
      }) + '\n',
    );
    process.exitCode = 2;
  } finally {
    await pool.end();
  }
})();
