require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  TaskDeliveryService,
  deliveryDestination,
  DELIVERY_DELAYS,
  transientDeliveryFailure,
} = require('../dist/transport/task-delivery.service');
const { DocumentParseConsumer } = require('../dist/consumers/document-parse.consumer');
const { KnowledgeIndexConsumer } = require('../dist/consumers/knowledge-index.consumer');
const { IndexStoreUnavailableError } = require('../dist/rag/knowledge-index-workflow.service');
const {
  WorkflowOperationUncertainError,
  WorkflowOperationFailedError,
} = require('../dist/graphs/persistence/mysql-checkpointer');

test('延迟重试依次为 10/60/300 秒，三次后死信；永久错误和不确定请求不重试', () => {
  assert.deepEqual([...DELIVERY_DELAYS], [10000, 60000, 300000]);
  for (const queue of ['lexflow.q.document.parse', 'lexflow.q.knowledge.ingest']) {
    for (let count = 0; count < 4; count++) {
      const route = deliveryDestination(
        queue,
        { 'x-lexflow-delivery-retry': count, trace: 'kept' },
        'TRANSIENT',
      );
      assert.equal(route.queue, count < 3 ? `${queue}.retry.${count + 1}` : `${queue}.dlq`);
      assert.equal(route.retryCount, Math.min(count + 1, 3));
      assert.equal(route.headers.trace, 'kept');
    }
    for (const value of [-1, NaN, '0', 4])
      assert.equal(
        deliveryDestination(queue, { 'x-lexflow-delivery-retry': value }, 'TRANSIENT').retry,
        false,
      );
    for (const kind of ['PERMANENT', 'UNCERTAIN'])
      assert.equal(deliveryDestination(queue, {}, kind).queue, `${queue}.dlq`);
  }
  assert.throws(() => deliveryDestination('another-queue', {}, 'TRANSIENT'));
  assert.equal(transientDeliveryFailure(new IndexStoreUnavailableError()), true);
  assert.equal(transientDeliveryFailure({ response: { status: 503 } }), true);
  assert.equal(transientDeliveryFailure({ response: { status: 403 } }), false);
  assert.equal(transientDeliveryFailure(new WorkflowOperationFailedError()), false);
});

test('确认持久副本之前不能 ACK；确认失败保留原消息到死信且不热循环', async () => {
  const service = new TaskDeliveryService();
  let finish;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  const calls = [];
  service.publish = async () => {
    await pending;
    calls.push('confirmed');
  };
  const channel = {
    ack: () => calls.push('ack'),
    nack: (_m, _all, requeue) => calls.push(['nack', requeue]),
  };
  const message = { content: Buffer.from('test'), properties: { headers: {} } };
  const run = service.defer(channel, message, 'lexflow.q.document.parse');
  await Promise.resolve();
  assert.deepEqual(calls, []);
  finish();
  await run;
  assert.deepEqual(calls, ['confirmed', 'ack']);
  service.publish = async () => {
    throw new Error('unconfirmed');
  };
  calls.length = 0;
  await service.defer(channel, message, 'lexflow.q.document.parse');
  assert.deepEqual(calls, [['nack', false]]);
});

test('解析上下文暂时不可用时延迟投递，权限拒绝直接交人工处理', async () => {
  for (const [error, kind] of [
    [{ code: 'ECONNREFUSED' }, 'TRANSIENT'],
    [{ response: { status: 403 } }, 'PERMANENT'],
  ]) {
    const decisions = [];
    const callback = {
      getContext: async () => {
        throw error;
      },
    };
    const delivery = { defer: async (_c, _m, q, k) => decisions.push([q, k]) };
    await new DocumentParseConsumer(callback, delivery).handle(
      { tenantId: '7', taskId: '123', attempt: 0 },
      {
        getChannelRef: () => ({
          ack: () => assert.fail('no result'),
          nack: () => assert.fail('delivery handles'),
        }),
        getMessage: () => ({}),
      },
    );
    assert.deepEqual(decisions, [['lexflow.q.document.parse', kind]]);
  }
});

const task = {
  taskId: '123',
  tenantId: '7',
  caseId: '8',
  type: 'KNOWLEDGE_INGEST',
  status: 'QUEUED',
  attempt: 0,
  stateVersion: 1,
};
const input = { tenantId: '7', caseId: '8', knowledgeBaseId: '9' };
test('案件索引恢复以授权索引输入核对知识库，文件上下文无知识库不丢弃合法消息', async () => {
  for (const base of ['9', '99']) {
    const calls = [];
    const callback = {
      getContext: async () => ({ ...task, scope: 'CASE', knowledgeBaseId: null }),
      getIndexInput: async () => {
        calls.push('input');
        return { ...input, scope: 'CASE' };
      },
      submitIndexResult: async (result) => calls.push(result.success),
    };
    const workflow = {
      run: async () => {
        calls.push('indexed');
        return {
          collectionName: 'owned',
          dimension: 4,
          indexedChunks: 1,
          modelIdentity: 'synthetic',
        };
      },
    };
    const context = {
      getChannelRef: () => ({
        ack: () => calls.push('ack'),
        nack: () => assert.fail('bound context should be acknowledged'),
      }),
      getMessage: () => ({}),
    };
    await new KnowledgeIndexConsumer(callback, workflow).handle(
      {
        taskId: '123',
        tenantId: '7',
        type: 'KNOWLEDGE_INGEST',
        attempt: 0,
        scope: 'CASE',
        caseId: '8',
        knowledgeBaseId: base,
      },
      context,
    );
    assert.deepEqual(calls, base === '9' ? ['input', 'indexed', true, 'ack'] : ['input', 'ack']);
  }
});
test('向量存储瞬时失败可恢复，不确定 Embedding 立即进入人工处理且不回报失败覆盖', async () => {
  for (const [error, kind] of [
    [new IndexStoreUnavailableError(), 'TRANSIENT'],
    [new WorkflowOperationUncertainError(), 'UNCERTAIN'],
  ]) {
    const calls = [];
    const callback = {
      getContext: async () => task,
      getIndexInput: async () => input,
      submitIndexResult: async () => assert.fail('不能先发布失败再自动重试'),
    };
    const workflow = {
      run: async () => {
        throw error;
      },
    };
    const delivery = { defer: async (_c, _m, _q, k) => calls.push(k) };
    await new KnowledgeIndexConsumer(callback, workflow, delivery).handle(
      { taskId: '123', tenantId: '7', type: 'KNOWLEDGE_INGEST', attempt: 0 },
      {
        getChannelRef: () => ({
          ack: () => assert.fail('deferred'),
          nack: () => assert.fail('delivery handles'),
        }),
        getMessage: () => ({}),
      },
    );
    assert.deepEqual(calls, [kind]);
  }
});

test('索引成功回调丢失不能改发失败，旧次数及终态不重新获取模型输入', async () => {
  const calls = [];
  const callback = {
    getContext: async () => task,
    getIndexInput: async () => input,
    submitIndexResult: async (result) => {
      calls.push(result.success);
      throw { response: { status: 503 } };
    },
  };
  const workflow = {
    run: async () => ({
      collectionName: 'test',
      dimension: 4,
      indexedChunks: 1,
      modelIdentity: 'test',
    }),
  };
  const delivery = { defer: async (_c, _m, _q, k) => calls.push(k) };
  const context = {
    getChannelRef: () => ({
      ack: () => calls.push('ack'),
      nack: () => assert.fail('delivery handles'),
    }),
    getMessage: () => ({}),
  };
  await new KnowledgeIndexConsumer(callback, workflow, delivery).handle(
    { taskId: '123', tenantId: '7', type: 'KNOWLEDGE_INGEST', attempt: 0 },
    context,
  );
  assert.deepEqual(calls, [true, 'TRANSIENT']);
  callback.getIndexInput = async () => assert.fail('stale');
  calls.length = 0;
  await new KnowledgeIndexConsumer(callback, workflow, delivery).handle(
    { taskId: '123', tenantId: '7', type: 'KNOWLEDGE_INGEST', attempt: 1 },
    context,
  );
  assert.deepEqual(calls, ['ack']);
});
