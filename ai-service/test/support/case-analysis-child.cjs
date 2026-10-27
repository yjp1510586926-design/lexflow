// Independent process, real durable graph, in-process synthetic responses; no provider calls.
const { appendFileSync } = require('node:fs');
const { workflowPool } = require('../../dist/graphs/persistence/workflow-pool');
const { TenantMySqlCheckpointer } = require('../../dist/graphs/persistence/mysql-checkpointer');
const { runCaseAnalysis } = require('../../dist/graphs/case-analysis.graph');
const fixture = require('./case-analysis-fixture.cjs');
const [mode, tenant, task, counter] = process.argv.slice(2),
  pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
const input = fixture.input(
  tenant,
  task,
  mode === 'resume' ? '12345678-1234-1234-1234-123456789abc' : null,
);
let calls = 0;
(async () => {
  try {
    const result = await runCaseAnalysis(input, new TenantMySqlCheckpointer(pool, tenant), {
      assertAuthorized: async () => {},
      model: {
        invoke: async () => {
          calls++;
          appendFileSync(counter, 'model\n');
          return {
            content: JSON.stringify(calls === 1 ? fixture.extraction() : fixture.assessment(true)),
          };
        },
      },
    });
    process.stdout.write(JSON.stringify({ result }) + '\n');
  } catch (error) {
    process.stdout.write(JSON.stringify({ error: error.message }) + '\n');
    process.exitCode = 2;
  } finally {
    await pool.end();
  }
})();
