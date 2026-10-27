// Technical persistence probe only: synthetic input, no model, no Java task or legal result.
const { Annotation, StateGraph, START, END, interrupt, Command } = require('@langchain/langgraph');
const { appendFileSync } = require('node:fs');
const { createHash } = require('node:crypto');
const { workflowPool } = require('../../dist/graphs/persistence/workflow-pool');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  WorkflowBusyError,
  WorkflowOperationUncertainError,
} = require('../../dist/graphs/persistence/mysql-checkpointer');

const [mode, tenant, task, counterFile] = process.argv.slice(2);
const pool = workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
const saver = new TenantMySqlCheckpointer(pool, tenant);
const config = {
  configurable: { tenant_id: tenant, thread_id: workflowThreadId(tenant, task) },
  durability: 'sync',
};
const State = Annotation.Root({
  material: Annotation(),
  prepared: Annotation(),
  decision: Annotation(),
  report: Annotation(),
});
const graph = new StateGraph(State)
  .addNode('prepare', (state) => {
    appendFileSync(counterFile, 'prepare\n');
    return { prepared: state.material === 'synthetic contract' };
  })
  .addNode('humanReview', (state) => {
    const decision = interrupt({ purpose: 'persistence test', prepared: state.prepared });
    if (decision !== 'approve') throw new Error('Invalid probe decision');
    return { decision };
  })
  .addNode('buildReport', (state) => {
    appendFileSync(counterFile, 'report\n');
    return { report: state.decision === 'approve' ? 'synthetic result reviewed' : '' };
  })
  .addEdge(START, 'prepare')
  .addEdge('prepare', 'humanReview')
  .addEdge('humanReview', 'buildReport')
  .addEdge('buildReport', END)
  .compile({ checkpointer: saver });

(async () => {
  try {
    const result = await saver.withThreadLock(config, async (assertHeld) => {
      if (mode === 'call-crash' || mode === 'call-recover') {
        return saver.runOnce(
          config,
          'model-analysis-v1',
          createHash('sha256').update('synthetic input').digest('hex'),
          async () => {
            // Simulates a provider request having started; no network or paid model.
            appendFileSync(counterFile, 'model\n');
            if (mode === 'call-crash') {
              if (process.send) process.send({ modelStarted: true });
              await new Promise((resolve) => process.once('message', resolve));
            }
            return { analysis: 'synthetic analysis' };
          },
        );
      }
      if (mode === 'hold') {
        if (process.send) process.send({ locked: true });
        await new Promise((resolve) => process.once('message', resolve));
        await assertHeld();
        return { released: true };
      }
      const existing = await saver.getTuple(config);
      if (mode === 'start') {
        if (!existing) await graph.invoke({ material: 'synthetic contract' }, config);
      } else if (mode === 'resume') {
        const state = await graph.getState(config);
        if (!state.next.includes('humanReview')) throw new Error('NOT_WAITING');
        await assertHeld();
        await graph.invoke(new Command({ resume: 'approve' }), config);
      } else if (mode !== 'inspect') throw new Error('Invalid probe mode');
      const state = await graph.getState(config);
      return {
        values: state.values,
        next: state.next,
        checkpoint: state.config.configurable.checkpoint_id,
      };
    });
    process.stdout.write(JSON.stringify({ ok: true, result }) + '\n');
  } catch (error) {
    process.stdout.write(
      JSON.stringify({
        ok: false,
        error:
          error instanceof WorkflowBusyError
            ? 'BUSY'
            : error instanceof WorkflowOperationUncertainError
              ? 'UNCERTAIN'
              : error.message === 'NOT_WAITING'
                ? 'NOT_WAITING'
                : 'PROBE_FAILED',
      }) + '\n',
    );
    process.exitCode = 2;
  } finally {
    await pool.end();
  }
})().catch(() => {
  process.stderr.write('Probe could not start\n');
  process.exitCode = 3;
});
