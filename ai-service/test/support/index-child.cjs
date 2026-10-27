require('reflect-metadata');
const { readFileSync, writeFileSync } = require('node:fs');
const { workflowPool } = require('../../dist/graphs/persistence/workflow-pool');
const { TenantMySqlCheckpointer } = require('../../dist/graphs/persistence/mysql-checkpointer');
const { runPersistedKnowledgeIndex } = require('../../dist/rag/knowledge-index-workflow.service');
const { MilvusKnowledgeStore } = require('../../dist/rag/milvus-knowledge.store');

function fixture(tenant, task) {
  return {
    task: { tenantId: tenant, taskId: task, scope: 'CASE', caseId: '8', knowledgeBaseId: '9' },
    input: {
      tenantId: tenant,
      scope: 'CASE',
      caseId: '8',
      knowledgeBaseId: '9',
      documentId: '10',
      fileVersionId: '11',
      indexVersion: 1,
      provider: 'synthetic',
      baseUrl: 'https://example.test/v1',
      model: 'synthetic',
      configurationRevision: 'revision-test',
      apiKey: 'synthetic-key-only',
      chunks: Array.from({ length: 60 }, (_, i) => ({
        id: String(i + 1),
        text: `合成条款${i + 1}`,
      })),
    },
  };
}
module.exports = { fixture };
if (require.main === module)
  (async () => {
    const [phase, tenant, taskId, counter] = process.argv.slice(2);
    const pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
    const data = fixture(tenant, taskId);
    let writes = 0;
    const store = {
      ensureCollection: async () => MilvusKnowledgeStore.collectionName('synthetic', 4),
      upsert: async () => {
        writes++;
        if (phase === 'fail') throw new Error('synthetic store disconnect');
      },
    };
    try {
      const receipt = await runPersistedKnowledgeIndex(
        data.task,
        data.input,
        new TenantMySqlCheckpointer(pool, tenant),
        {
          embedDocuments: async (texts) => {
            writeFileSync(counter, String(Number(readFileSync(counter, 'utf8')) + 1));
            return texts.map(() => [1, 0, 0, 0]);
          },
        },
        store,
        async () => {},
      );
      process.stdout.write(JSON.stringify({ outcome: 'SUCCEEDED', receipt, writes }));
    } catch (error) {
      process.stdout.write(JSON.stringify({ outcome: error.constructor.name, writes }));
    } finally {
      await pool.end();
    }
  })().catch(() => process.exit(1));
