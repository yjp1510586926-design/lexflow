'use strict';
const path = require('node:path');
const { createRequire } = require('node:module');
const dependencies = createRequire(path.resolve(__dirname, '../../../ai-service/package.json'));
const { Annotation, StateGraph, START, END, interrupt, Command } =
  dependencies('@langchain/langgraph');
const { emptyCheckpoint, uuid6 } = dependencies('@langchain/langgraph-checkpoint');
const mysql = dependencies('mysql2/promise');
const { appendFileSync } = require('node:fs');
const { createHash } = require('node:crypto');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  WorkflowOperationUncertainError,
  WorkflowOperationFailedError,
} = require('../../../ai-service/dist/graphs/persistence/mysql-checkpointer');
const [mode, tenant, task, counter] = process.argv.slice(2);
const pool = mysql.createPool(JSON.parse(process.env.LEXFLOW_BACKUP_WORKFLOW_CONFIG));
const saver = new TenantMySqlCheckpointer(pool, tenant),
  config = {
    configurable: { tenant_id: tenant, thread_id: workflowThreadId(tenant, task) },
    durability: 'sync',
  };
const hash = createHash('sha256').update('fixed synthetic backup workflow input').digest('hex');
const State = Annotation.Root({
  material: Annotation(),
  cached: Annotation(),
  decision: Annotation(),
  report: Annotation(),
});
const graph = new StateGraph(State)
  .addNode('prepare', async () => ({
    cached: await saver.runOnce(config, 'backup-cache-v1', hash, async () => {
      appendFileSync(counter, 'synthetic-call\n');
      return { fact: 'synthetic preserved paid result' };
    }),
  }))
  .addNode('humanReview', () => ({
    decision: interrupt({ purpose: 'synthetic backup restore proof' }),
  }))
  .addNode('report', (state) => {
    if (state.decision !== 'approve') throw Error('Invalid synthetic decision');
    appendFileSync(counter, 'report\n');
    return { report: 'synthetic resumed report' };
  })
  .addEdge(START, 'prepare')
  .addEdge('prepare', 'humanReview')
  .addEdge('humanReview', 'report')
  .addEdge('report', END)
  .compile({ checkpointer: saver });
(async () => {
  try {
    const result = await saver.withThreadLock(config, async () => {
      if (mode === 'seed') {
        await graph.invoke({ material: 'synthetic source evidence' }, config);
        try {
          await saver.runOnce(config, 'backup-failed-v1', hash, async () => {
            appendFileSync(counter, 'synthetic-failed-call\n');
            throw Error('Synthetic local failure');
          });
        } catch (error) {
          if (!(error instanceof WorkflowOperationFailedError)) throw error;
        }
        const cp = emptyCheckpoint();
        cp.v = 4;
        cp.id = uuid6(-1);
        cp.channel_values = { scopeProbe: 'synthetic' };
        await saver.put(
          {
            ...config,
            configurable: { ...config.configurable, checkpoint_ns: 'backup-scope-probe' },
          },
          cp,
          { source: 'input', step: -1, parents: {} },
          {},
        );
      } else if (mode === 'resume') {
        const state = await graph.getState(config);
        if (!state.next.includes('humanReview')) throw Error('Missing preserved interruption');
        if (
          (
            await saver.runOnce(config, 'backup-cache-v1', hash, async () => {
              appendFileSync(counter, 'unexpected-call\n');
              throw Error('Cache lost');
            })
          ).fact !== 'synthetic preserved paid result'
        )
          throw Error('Invalid cache');
        let uncertain = false,
          failed = false;
        try {
          await saver.runOnce(config, 'backup-uncertain-v1', hash, async () => {
            appendFileSync(counter, 'unexpected-call\n');
          });
        } catch (error) {
          uncertain = error instanceof WorkflowOperationUncertainError;
        }
        try {
          await saver.runOnce(config, 'backup-failed-v1', hash, async () => {
            appendFileSync(counter, 'unexpected-call\n');
          });
        } catch (error) {
          failed = error instanceof WorkflowOperationFailedError;
        }
        if (!uncertain || !failed) throw Error('Unsafe journal recovery');
        const other = new TenantMySqlCheckpointer(pool, String(BigInt(tenant) + 100n));
        let rejected = false;
        try {
          await other.getTuple(config);
        } catch (error) {
          rejected = true;
        }
        if (!rejected) throw Error('Cross tenant read accepted');
        await graph.invoke(new Command({ resume: 'approve' }), config);
      } else throw Error('Unknown proof mode');
      const state = await graph.getState(config);
      return { next: state.next, values: state.values };
    });
    process.stdout.write(JSON.stringify({ ok: true, result }) + '\n');
  } catch (error) {
    process.stdout.write(
      JSON.stringify({ ok: false, error: 'SYNTHETIC_RESTORE_PROOF_FAILED' }) + '\n',
    );
    process.exitCode = 1;
  } finally {
    await pool.end();
  }
})().catch(() => {
  process.stderr.write('Synthetic restore child could not start\n');
  process.exitCode = 1;
});
