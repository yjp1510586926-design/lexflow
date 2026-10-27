require('reflect-metadata');
require('@nestjs/core');
const { test } = require('node:test'),
  assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { ContractReviewConsumer } = require('../dist/consumers/contract-review.consumer');
const { DocumentGenerationConsumer } = require('../dist/consumers/document-generation.consumer');
const { CaseAnalysisConsumer } = require('../dist/consumers/case-analysis.consumer');
const {
  WorkflowOperationUncertainError,
  WorkflowOperationFailedError,
  WorkflowBusyError,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const { WorkflowInfrastructureError } = require('../dist/graphs/workflow-infrastructure-error');
const {
  TaskDeliveryService,
  deliveryDestination,
  DELIVERY_DELAYS,
} = require('../dist/transport/task-delivery.service');
const types = [
  ['CONTRACT_REVIEW', ContractReviewConsumer, 'contract.review'],
  ['DOCUMENT_GENERATE', DocumentGenerationConsumer, 'document.generate'],
  ['CASE_ANALYSIS', CaseAnalysisConsumer, 'case.analysis'],
];
function fixture(type, status = 'QUEUED', version = 4) {
  const events = [],
    copies = [],
    event = {
      schemaVersion: '1.0',
      eventId: randomUUID(),
      tenantId: '910000301',
      taskId: '51',
      caseId: '11',
      type,
      resourceVersionId: '13',
      attempt: 2,
      stateVersion: version,
    };
  const task = {
    ...event,
    status,
    stateVersion: version + (status === 'RUNNING' ? 1 : 0),
    sourceVersionId: '13',
  };
  const message = {
    content: Buffer.from(JSON.stringify(event)),
    properties: { headers: {}, contentType: 'application/json' },
  };
  const channel = {
    ack: () => events.push('ack'),
    nack: (_m, _a, requeue) => events.push(['nack', requeue]),
  };
  const delivery = new TaskDeliveryService();
  delivery.publish = async (msg, q, route) => copies.push({ content: msg.content, q, route });
  return {
    event,
    task,
    message,
    events,
    copies,
    delivery,
    ctx: { getChannelRef: () => channel, getMessage: () => message },
  };
}
for (const [type, Consumer, suffix] of types) {
  test(`${type} 非付费读取/领取503有界TTL，付费错误优先且成功回调不能降级`, async () => {
    for (const scenario of [
      'context',
      'claim',
      'download',
      'permission',
      'uncertain',
      'failed',
      'failed-callback',
      'callback',
      'unknown',
      'busy',
    ]) {
      const f = fixture(type);
      let callbacks = 0;
      const results = [];
      const read = async () => {
        if (scenario === 'context') throw { response: { status: 503 } };
        return f.task;
      };
      const result = async (_task, outcome) => {
        callbacks++;
        results.push(outcome);
        if (['failed-callback', 'callback'].includes(scenario)) throw { response: { status: 503 } };
      };
      const callback = {
        getContext: read,
        generationInput: read,
        contractResult: result,
        analysisResult: result,
        generationResult: result,
      };
      const workflow = {
        run: async (t, claim) => {
          if (scenario === 'claim') throw { response: { status: 503 } };
          if (scenario === 'busy') throw new WorkflowBusyError();
          claim({ ...t, stateVersion: 5, status: 'RUNNING' });
          if (scenario === 'download')
            throw new WorkflowInfrastructureError(true, '原文暂时不可用');
          if (scenario === 'permission') throw new WorkflowInfrastructureError(false, '权限变化');
          if (scenario === 'uncertain') {
            const e = new WorkflowOperationUncertainError();
            e.response = { status: 503 };
            throw e;
          }
          if (['failed', 'failed-callback'].includes(scenario)) {
            const e = new WorkflowOperationFailedError();
            e.response = { status: 429 };
            throw e;
          }
          if (scenario === 'unknown') throw Error('unknown after claim');
          return type === 'DOCUMENT_GENERATE'
            ? { content: '草稿', missingInformation: [] }
            : { outcome: 'SUCCEEDED', report: {} };
        },
      };
      await new Consumer(callback, workflow, f.delivery).handle(f.event, f.ctx);
      assert.deepEqual(f.events, ['ack'], scenario);
      if (['failed', 'busy'].includes(scenario)) {
        assert.equal(f.copies.length, 0);
        assert.equal(callbacks, scenario === 'failed' ? 1 : 0);
      } else {
        assert.equal(f.copies.length, 1);
        const { q, route, content } = f.copies[0];
        assert.equal(q, `lexflow.q.${suffix}`);
        assert.equal(content, f.message.content);
        const kind =
          scenario === 'permission'
            ? 'PERMANENT'
            : ['uncertain', 'unknown'].includes(scenario)
              ? 'UNCERTAIN'
              : 'TRANSIENT';
        assert.equal(route.headers['x-lexflow-failure-kind'], kind);
        assert.equal(
          route.queue,
          `lexflow.q.${suffix}.${kind === 'TRANSIENT' ? 'retry.1' : 'dlq'}`,
        );
      }
      if (
        ['uncertain', 'permission', 'download', 'claim', 'context', 'unknown', 'busy'].includes(
          scenario,
        )
      )
        assert.equal(callbacks, 0);
      else assert.deepEqual(results, [scenario.startsWith('failed') ? 'FAILED' : 'SUCCEEDED']);
    }
  });
  test(`${type} 恢复后的 Q4/R5 相邻重投固定来源，未确认副本不可ACK`, async () => {
    for (const status of ['QUEUED', 'RUNNING']) {
      const f = fixture(type, status);
      let calls = 0;
      const read = async () => f.task,
        result = async (t) => assert.equal(t.stateVersion, 5);
      const cb = {
        getContext: read,
        generationInput: read,
        contractResult: result,
        analysisResult: result,
        generationResult: result,
      };
      const wf = {
        run: async (t, claim) => {
          calls++;
          claim({ ...t, stateVersion: 5 });
          return type === 'DOCUMENT_GENERATE'
            ? { content: '草稿', missingInformation: [] }
            : { outcome: 'SUCCEEDED', report: {} };
        },
      };
      await new Consumer(cb, wf, f.delivery).handle(f.event, f.ctx);
      assert.equal(calls, 1);
      assert.deepEqual(f.events, ['ack']);
      for (const patch of [
        { tenantId: '2' },
        { taskId: '52' },
        { attempt: 3 },
        { stateVersion: 6 },
        type === 'DOCUMENT_GENERATE' ? { sourceVersionId: '14' } : { resourceVersionId: '14' },
      ]) {
        f.events.length = 0;
        const mutated = { ...f.task, ...patch },
          read2 = async () => mutated;
        await new Consumer(
          { ...cb, getContext: read2, generationInput: read2 },
          { run: () => assert.fail('changed input must not run') },
          f.delivery,
        ).handle(f.event, f.ctx);
        assert.deepEqual(f.events, ['ack']);
      }
    }
    const f = fixture(type);
    f.delivery.publish = async () => {
      throw Error('unconfirmed copy');
    };
    const read = async () => {
      throw { response: { status: 503 } };
    };
    await new Consumer(
      { getContext: read, generationInput: read },
      { run: () => assert.fail('not claimed') },
      f.delivery,
    ).handle(f.event, f.ctx);
    assert.deepEqual(f.events, [['nack', false]]);
  });
}
test('三新队列保持10/60/300秒、原字节和全部业务身份，第三次后DLQ', () => {
  for (const [, , suffix] of types) {
    const queue = `lexflow.q.${suffix}`;
    for (let count = 0; count <= 3; count++) {
      const r = deliveryDestination(
        queue,
        { 'x-lexflow-delivery-retry': count, owned: 'kept' },
        'TRANSIENT',
      );
      assert.equal(r.queue, count < 3 ? `${queue}.retry.${count + 1}` : `${queue}.dlq`);
      assert.equal(r.headers.owned, 'kept');
    }
  }
  assert.deepEqual([...DELIVERY_DELAYS], [10000, 60000, 300000]);
});

test('三领域service在模型配置前核对固定来源，已知无付费配置错误为PERMANENT', async () => {
  const { ContractWorkflowService } = require('../dist/graphs/contract-workflow.service');
  const {
    DocumentGenerationWorkflowService,
  } = require('../dist/graphs/document-generation-workflow.service');
  const { CaseAnalysisWorkflowService } = require('../dist/graphs/case-analysis-workflow.service');
  const { workflowDeliveryFailure } = require('../dist/transport/workflow-delivery');
  const data = require('./support/case-analysis-fixture.cjs'),
    previous = process.env.AI_WORKFLOW_DATABASE_URL;
  const url = previous ?? 'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai',
    parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  process.env.AI_WORKFLOW_DATABASE_URL = url;
  try {
    for (const [type, Service] of [
      ['CONTRACT_REVIEW', ContractWorkflowService],
      ['DOCUMENT_GENERATE', DocumentGenerationWorkflowService],
      ['CASE_ANALYSIS', CaseAnalysisWorkflowService],
    ]) {
      let source =
        type === 'CASE_ANALYSIS'
          ? data.input('910000301', '51')
          : {
              tenantId: '910000301',
              taskId: '51',
              attempt: 2,
              stateVersion: 5,
              provider: 'test',
              baseUrl: 'https://models.example.test/v1',
              model: 'guard-test',
              apiKey: '',
              ...(type === 'CONTRACT_REVIEW'
                ? {
                    fileVersionId: '13',
                    sha256: 'f'.repeat(64),
                    mimeType: 'text/plain',
                    stance: 'NEUTRAL',
                    focus: '',
                    knowledgeBaseId: null,
                    decisionId: null,
                  }
                : {
                    caseId: '41',
                    documentId: '12',
                    sourceVersionId: '13',
                    title: '待复核函',
                    baseContent: '甲欠乙100元。',
                    instructions: '起草待复核函',
                    caseContext: '{}',
                    modelRevision: '00000000-0000-0000-0000-000000000018',
                  }),
            };
      source = { ...source, attempt: 2, stateVersion: 5, apiKey: '' };
      const task =
        type === 'DOCUMENT_GENERATE'
          ? { ...source, stateVersion: 4 }
          : {
              taskId: '51',
              tenantId: '910000301',
              caseId: '41',
              resourceVersionId: type === 'CASE_ANALYSIS' ? '21' : '13',
              sha256: 'f'.repeat(64),
              attempt: 2,
              stateVersion: 4,
              status: 'QUEUED',
            };
      for (const mode of ['source', 'config']) {
        let claimed = 0;
        const live = structuredClone(source);
        if (mode === 'source') {
          if (type === 'CONTRACT_REVIEW') live.fileVersionId = '14';
          else if (type === 'DOCUMENT_GENERATE') live.sourceVersionId = '14';
          else live.sources[0].fileVersionId = '22';
        }
        const callback = {
            contractInput: async () => live,
            generationInput: async () => live,
            analysisInput: async () => live,
          },
          service = new Service(callback);
        try {
          await assert.rejects(
            service.run(task, () => claimed++),
            (e) => {
              assert.equal(workflowDeliveryFailure(e, claimed > 0), 'PERMANENT');
              return true;
            },
          );
          assert.equal(claimed, mode === 'source' ? 0 : 1);
        } finally {
          await service.onModuleDestroy();
        }
      }
    }
  } finally {
    if (previous === undefined) delete process.env.AI_WORKFLOW_DATABASE_URL;
    else process.env.AI_WORKFLOW_DATABASE_URL = previous;
  }
});

test(
  '真实AI SQL跨Node进程 checkpoint故障/成功回调503复用已付费缓存，供应商429禁重放',
  { skip: process.env.LEXFLOW_WORKFLOW_INTEGRATION !== 'true', timeout: 90000 },
  async () => {
    const { spawn } = require('node:child_process'),
      { mkdtempSync, readFileSync, rmSync } = require('node:fs'),
      { join } = require('node:path'),
      { tmpdir } = require('node:os');
    const { workflowPool } = require('../dist/graphs/persistence/workflow-pool'),
      { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
    const {
      TenantMySqlCheckpointer,
      workflowThreadId,
    } = require('../dist/graphs/persistence/mysql-checkpointer');
    const {
      inspectWorkflowRecovery,
    } = require('../dist/graphs/workflow-recovery-eligibility.service');
    const url = process.env.AI_WORKFLOW_DATABASE_URL,
      parsed = new URL(url);
    assert.equal(parsed.hostname, '127.0.0.1');
    assert.equal(parsed.port, '13306');
    assert.equal(parsed.pathname, '/lexflow_ai');
    const pool = workflowPool(url),
      tenant = '910000303',
      saver = new TenantMySqlCheckpointer(pool, tenant),
      owned = [];
    const dir = mkdtempSync(join(tmpdir(), 'lexflow-workflow-delivery-'));
    const child = (type, mode, task, counter) =>
      new Promise((resolve, reject) => {
        const p = spawn(
          process.execPath,
          [
            join(__dirname, 'support/workflow-recovery-child.cjs'),
            type,
            mode,
            tenant,
            task,
            counter,
          ],
          { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
        );
        let output = '';
        p.stdout.on('data', (b) => (output += b));
        p.stderr.resume();
        const timer = setTimeout(() => {
          p.kill('SIGKILL');
          reject(Error('owned child timed out'));
        }, 15000);
        p.once('error', reject);
        p.once('exit', (code) => {
          clearTimeout(timer);
          try {
            assert.equal(code, 0);
            resolve(
              JSON.parse(
                output
                  .split('\n')
                  .find((s) => s.startsWith('RESULT '))
                  .slice(7),
              ),
            );
          } catch (e) {
            reject(e);
          }
        });
      });
    try {
      await setupWorkflowStore(pool);
      for (const [type] of types)
        for (const scenario of ['checkpoint', 'callback', 'paid429']) {
          const task = String(Date.now()) + String(owned.length + 1000),
            counter = join(dir, task + '.txt');
          owned.push(task);
          const first = await child(type, scenario, task, counter),
            before = readFileSync(counter, 'utf8').split('\n').filter(Boolean).length;
          assert.deepEqual(
            await inspectWorkflowRecovery(pool, first.recoveryRequest),
            { status: scenario === 'paid429' ? 'FAILED' : 'SAFE' },
            'intermediate cached checkpoint/result must be independently recoverable without paid calls',
          );
          if (scenario === 'paid429') {
            assert.deepEqual(first.outcomes, ['FAILED']);
            assert.equal(first.decisions.length, 0);
            assert(first.ops.some((o) => o.status === 'FAILED'));
            const resumed = await child(type, 'callback-resume', task, counter);
            assert.equal(resumed.modelCalls, 0);
            assert.deepEqual(resumed.outcomes, ['FAILED']);
            assert.equal(resumed.decisions.length, 0);
          } else {
            assert.equal(first.decisions.length, 1);
            assert.equal(first.decisions[0].kind, 'TRANSIENT');
            assert.equal(first.decisions[0].contentUnchanged, true);
            if (scenario === 'checkpoint') {
              assert.deepEqual(first.outcomes, []);
              assert(first.ops.some((o) => o.status === 'SUCCEEDED'));
            } else assert.equal(first.outcomes.length, 1);
            const resumed = await child(
              type,
              scenario === 'checkpoint' ? 'resume' : 'callback-resume',
              task,
              counter,
            );
            assert.deepEqual(resumed.decisions, []);
            assert.deepEqual(resumed.events, ['ack']);
            assert.equal(resumed.outcomes.length, 1);
            if (scenario === 'callback') assert.equal(resumed.modelCalls, 0);
            assert.equal(
              resumed.ops.filter((o) => o.status === 'SUCCEEDED').length,
              type === 'DOCUMENT_GENERATE' ? 1 : 2,
            );
          }
          const after = readFileSync(counter, 'utf8').split('\n').filter(Boolean).length;
          assert.equal(after, scenario === 'paid429' ? 1 : type === 'DOCUMENT_GENERATE' ? 1 : 2);
          if (scenario === 'callback') assert.equal(after, before);
        }
    } finally {
      try {
        for (const task of owned) {
          await saver.deleteThread(workflowThreadId(tenant, task));
          for (const table of [
            'workflow_thread',
            'workflow_operation',
            'workflow_checkpoint',
            'workflow_checkpoint_write',
          ]) {
            const [rows] = await pool.execute(
              `SELECT COUNT(*) AS n FROM ${table} WHERE tenant_id=? AND thread_id=?`,
              [tenant, workflowThreadId(tenant, task)],
            );
            assert.equal(Number(rows[0].n), 0);
          }
        }
      } finally {
        await pool.end();
        rmSync(dir, { recursive: true, force: true });
      }
    }
  },
);

test(
  '隔离真实 RabbitMQ 三类10秒TTL回原队列及已耗尽/不确定消息死信',
  { skip: process.env.LEXFLOW_RMQ_INTEGRATION !== 'true', timeout: 25000 },
  async () => {
    const amqp = require('amqplib');
    const vhost = `lexflow-workflow-delivery-${randomUUID()}`;
    const headers = {
      Authorization: 'Basic ' + Buffer.from('lexflow:lexflow_mq_dev_only').toString('base64'),
      'Content-Type': 'application/json',
    };
    const management = async (method, path, body) => {
      const r = await fetch('http://127.0.0.1:15673/api/' + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return r;
    };
    let connection;
    const old = process.env.RABBITMQ_URL;
    try {
      assert.equal(
        (await management('PUT', 'vhosts/' + encodeURIComponent(vhost), {})).status,
        201,
      );
      assert.equal(
        (
          await management('PUT', 'permissions/' + encodeURIComponent(vhost) + '/lexflow', {
            configure: '.*',
            write: '.*',
            read: '.*',
          })
        ).status,
        204,
      );
      process.env.RABBITMQ_URL =
        'amqp://lexflow:lexflow_mq_dev_only@127.0.0.1:15672/' + encodeURIComponent(vhost);
      connection = await amqp.connect(process.env.RABBITMQ_URL);
      const channel = await connection.createConfirmChannel();
      const waits = [];
      for (const [, , suffix] of types) {
        const queue = `lexflow.q.${suffix}`;
        await channel.assertQueue(queue, { durable: true });
        for (let n = 0; n < 3; n++)
          await channel.assertQueue(`${queue}.retry.${n + 1}`, {
            durable: true,
            arguments: {
              'x-message-ttl': DELIVERY_DELAYS[n],
              'x-dead-letter-exchange': '',
              'x-dead-letter-routing-key': queue,
            },
          });
        const body = Buffer.from(
          ' {"tenantId":"910000301","taskId":"51","attempt":2,"stateVersion":4,"resourceVersionId":"13","text":"原始中文"}\n',
        );
        const service = new TaskDeliveryService(),
          message = {
            content: body,
            properties: { contentType: 'application/json', headers: { owned: vhost } },
          };
        let ack = 0;
        await service.defer(
          { ack: () => ack++, nack: () => assert.fail('confirmed copy') },
          message,
          queue,
        );
        assert.equal(ack, 1);
        waits.push(
          new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(Error('TTL did not return')), 18000);
            channel
              .consume(
                queue,
                (msg) => {
                  try {
                    assert.deepEqual(msg.content, body);
                    assert.equal(msg.properties.headers['x-lexflow-delivery-retry'], 1);
                    assert.equal(msg.properties.headers.owned, vhost);
                    channel.ack(msg);
                    clearTimeout(timer);
                    resolve();
                  } catch (e) {
                    clearTimeout(timer);
                    reject(e);
                  }
                },
                { noAck: false },
              )
              .catch(reject);
          }),
        );
        for (const kind of ['TRANSIENT', 'UNCERTAIN']) {
          await service.defer(
            { ack: () => ack++, nack: () => assert.fail('DLQ copy') },
            {
              ...message,
              properties: { ...message.properties, headers: { 'x-lexflow-delivery-retry': 3 } },
            },
            queue,
            kind,
          );
          const dead = await channel.get(`${queue}.dlq`, { noAck: false });
          assert(dead);
          assert.deepEqual(dead.content, body);
          assert.equal(dead.properties.headers['x-lexflow-failure-kind'], kind);
          channel.ack(dead);
        }
      }
      await Promise.all(waits);
      await channel.close();
    } finally {
      if (connection) await connection.close();
      if (old === undefined) delete process.env.RABBITMQ_URL;
      else process.env.RABBITMQ_URL = old;
      assert.equal((await management('DELETE', 'vhosts/' + encodeURIComponent(vhost))).status, 204);
      assert.equal((await management('GET', 'vhosts/' + encodeURIComponent(vhost))).status, 404);
    }
  },
);
