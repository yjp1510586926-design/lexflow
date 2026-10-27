require('reflect-metadata');
require('@nestjs/core');
const { appendFileSync } = require('node:fs');
const { workflowPool } = require('../../dist/graphs/persistence/workflow-pool');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
} = require('../../dist/graphs/persistence/mysql-checkpointer');
const { runContractReview } = require('../../dist/graphs/contract-review.graph');
const { runDocumentGeneration } = require('../../dist/graphs/document-generation.graph');
const { runCaseAnalysis } = require('../../dist/graphs/case-analysis.graph');
const { ContractReviewConsumer } = require('../../dist/consumers/contract-review.consumer');
const { DocumentGenerationConsumer } = require('../../dist/consumers/document-generation.consumer');
const { CaseAnalysisConsumer } = require('../../dist/consumers/case-analysis.consumer');
const data = require('./case-analysis-fixture.cjs');
async function main() {
  const [type, mode, tenant, task, counter] = process.argv.slice(2),
    pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
  const saver = new TenantMySqlCheckpointer(pool, tenant),
    thread = workflowThreadId(tenant, task);
  let input;
  if (type === 'CONTRACT_REVIEW')
    input = {
      tenantId: tenant,
      taskId: task,
      fileVersionId: '13',
      sha256: 'f'.repeat(64),
      mimeType: 'text/plain',
      attempt: 2,
      stateVersion: 5,
      stance: 'PARTY_A',
      focus: '付款',
      knowledgeBaseId: null,
      provider: 'test',
      baseUrl: 'https://models.example.test/v1',
      model: 'workflow-recovery-child',
      decisionId: null,
      apiKey: 'synthetic-only',
    };
  else if (type === 'DOCUMENT_GENERATE')
    input = {
      tenantId: tenant,
      taskId: task,
      caseId: '41',
      documentId: '12',
      sourceVersionId: '13',
      attempt: 2,
      stateVersion: 5,
      title: '催款函',
      baseContent: '甲欠乙100元。',
      instructions: '起草催款函',
      caseContext: JSON.stringify({ title: '合成案件' }),
      modelRevision: '00000000-0000-0000-0000-000000000018',
      provider: 'test',
      baseUrl: 'https://models.example.test/v1',
      model: 'workflow-recovery-child',
      apiKey: 'synthetic-only',
    };
  else input = { ...data.input(tenant, task), attempt: 2, stateVersion: 5 };
  const event = {
    schemaVersion: '1.0',
    eventId: '12345678-1234-1234-1234-123456789abc',
    tenantId: tenant,
    taskId: task,
    caseId: '41',
    type,
    resourceVersionId: type === 'CASE_ANALYSIS' ? '21' : '13',
    attempt: 2,
    stateVersion: 4,
  };
  const context = { ...event, status: 'RUNNING', stateVersion: 5, sourceVersionId: '13' };
  const original = saver.put.bind(saver);
  let injected = false;
  if (mode === 'checkpoint')
    saver.put = async (...args) => {
      const [rows] = await pool.execute(
        "SELECT COUNT(*) AS n FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND status='SUCCEEDED'",
        [tenant, thread],
      );
      if (!injected && Number(rows[0].n) >= 1) {
        injected = true;
        throw Object.assign(Error('synthetic checkpoint lock timeout'), {
          code: 'ER_LOCK_WAIT_TIMEOUT',
        });
      }
      return original(...args);
    };
  let modelCalls = 0;
  const model = {
    invoke: async () => {
      modelCalls++;
      appendFileSync(counter, 'model\n');
      if (mode === 'paid429')
        throw {
          response: { status: 429, data: 'never logged' },
          config: { headers: { Authorization: 'secret' } },
        };
      if (type === 'DOCUMENT_GENERATE')
        return { content: JSON.stringify({ content: '待复核草稿', missingInformation: [] }) };
      if (type === 'CASE_ANALYSIS')
        return {
          content: JSON.stringify(modelCalls === 1 ? data.extraction() : data.assessment()),
        };
      return {
        content: JSON.stringify(
          modelCalls === 1 && !(mode === 'resume' || mode === 'callback-resume')
            ? { contractType: '合同', clauses: [{ id: '1', title: '付款', quote: '验收后付款。' }] }
            : {
                risks: [
                  {
                    clauseId: '1',
                    level: 'LOW',
                    reason: '核对付款约定',
                    suggestion: '明确期限',
                    evidenceIds: [],
                  },
                ],
                missingMaterials: [],
                insufficientEvidence: true,
                basisConflicts: [],
              },
        ),
      };
    },
  };
  // A resumed graph may request its second model step without making the first call in this process.
  if (type === 'CASE_ANALYSIS' && mode === 'resume')
    model.invoke = async () => {
      modelCalls++;
      appendFileSync(counter, 'model\n');
      return { content: JSON.stringify(data.assessment()) };
    };
  const outcomes = [],
    decisions = [],
    events = [];
  const callback = {
    getContext: async () => context,
    generationInput: async () => input,
    contractResult: async (_t, outcome) => {
      outcomes.push(outcome);
      if (mode === 'callback') throw { response: { status: 503 } };
    },
    analysisResult: async (_t, outcome) => {
      outcomes.push(outcome);
      if (mode === 'callback') throw { response: { status: 503 } };
    },
    generationResult: async (_t, outcome) => {
      outcomes.push(outcome);
      if (mode === 'callback') throw { response: { status: 503 } };
    },
  };
  const workflow = {
    run: async (_t, claim) => {
      claim(type === 'DOCUMENT_GENERATE' ? input : context);
      const runtime = {
        assertAuthorized: async () => {},
        model,
        parse: async () => '验收后付款。',
        retrieve: async () => [],
      };
      return type === 'CONTRACT_REVIEW'
        ? runContractReview(input, saver, runtime)
        : type === 'DOCUMENT_GENERATE'
          ? runDocumentGeneration(input, saver, runtime)
          : runCaseAnalysis(input, saver, runtime);
    },
  };
  const Consumer =
    type === 'CONTRACT_REVIEW'
      ? ContractReviewConsumer
      : type === 'DOCUMENT_GENERATE'
        ? DocumentGenerationConsumer
        : CaseAnalysisConsumer;
  const delivery = {
    defer: async (_c, message, queue, kind) => {
      decisions.push({
        queue,
        kind,
        contentUnchanged: message.content.equals(Buffer.from(JSON.stringify(event))),
      });
    },
  };
  const ctx = {
    getChannelRef: () => ({ ack: () => events.push('ack'), nack: () => events.push('nack') }),
    getMessage: () => ({
      content: Buffer.from(JSON.stringify(event)),
      properties: { headers: {} },
    }),
  };
  try {
    await new Consumer(callback, workflow, delivery).handle(event, ctx);
    const [ops] = await pool.execute(
      'SELECT operation_key,status FROM workflow_operation WHERE tenant_id=? AND thread_id=? ORDER BY operation_key',
      [tenant, thread],
    );
    const { apiKey, ...safeInput } = input;
    process.stdout.write(
      '\nRESULT ' +
        JSON.stringify({
          type,
          mode,
          outcomes,
          decisions,
          events,
          modelCalls,
          ops,
          recoveryRequest: { type, taskId: task, executionProtocol: 2, input: safeInput },
        }) +
        '\n',
    );
  } finally {
    await pool.end();
  }
}
main().catch(() => {
  process.stderr.write('Workflow child failed\n');
  process.exitCode = 1;
});
