require('reflect-metadata');
require('@nestjs/core');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { ContractReviewConsumer } = require('../dist/consumers/contract-review.consumer');
const { DocumentParseConsumer } = require('../dist/consumers/document-parse.consumer');
const { KnowledgeIndexConsumer } = require('../dist/consumers/knowledge-index.consumer');
const {
  WorkflowOperationUncertainError,
  WorkflowOperationFailedError,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const { createHash } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

test('合同工作流失败不得报告成功，回调使用领取后的状态版本', async () => {
  const Consumer = ContractReviewConsumer;
  const results = [];
  let acked = false;
  const callback = {
    getContext: async () => ({
      taskId: '123',
      resourceVersionId: '456',
      attempt: 1,
      stateVersion: 1,
      tenantId: '7',
      type: 'CONTRACT_REVIEW',
      status: 'QUEUED',
    }),
    contractResult: async (task, outcome, report) => {
      results.push({ task, outcome, report });
    },
  };
  const channel = {
    ack: () => {
      acked = true;
    },
    nack: () => assert.fail('有效任务已回传失败，应确认消费'),
  };
  const context = { getChannelRef: () => channel, getMessage: () => ({}) };
  const workflow = {
    run: async (task, claimed) => {
      claimed({ ...task, stateVersion: 2 });
      throw new WorkflowOperationFailedError();
    },
  };
  await new Consumer(callback, workflow).handle(
    JSON.stringify({
      taskId: '123',
      tenantId: '7',
      resourceVersionId: '456',
      attempt: 1,
      stateVersion: 1,
    }),
    context,
  );
  assert.equal(results.length, 1);
  assert.equal(results[0].outcome, 'FAILED');
  assert.equal(results[0].task.stateVersion, 2);
  assert.equal(results[0].report, undefined);
  assert.equal(acked, true);
});

test('合同补材领取后回调503交有限投递，原消息重投使用当前领取版本并丢弃已完结旧事件', async () => {
  for (const eventVersion of [1, 4, 7]) {
    let current = {
      taskId: '123',
      tenantId: '7',
      resourceVersionId: '456',
      type: 'CONTRACT_REVIEW',
      attempt: 0,
      status: 'QUEUED',
      stateVersion: eventVersion,
    };
    const event = {
      taskId: '123',
      tenantId: '7',
      resourceVersionId: '456',
      attempt: 0,
      stateVersion: eventVersion,
    };
    let ack = 0,
      nack = 0,
      resultCalls = 0;
    const versions = [],
      runs = [],
      report = { synthetic: 'completed report' };
    const callback = {
      getContext: async () => ({ ...current }),
      contractResult: async (task, outcome, result) => {
        versions.push(task.stateVersion);
        resultCalls++;
        assert.equal(outcome, 'WAITING_HUMAN');
        assert.equal(result, report);
        if (resultCalls === 1) throw { response: { status: 503 } };
        current = { ...current, status: 'WAITING_HUMAN', stateVersion: task.stateVersion + 1 };
      },
    };
    const workflow = {
      run: async (task, claimed) => {
        runs.push({ status: task.status, version: task.stateVersion });
        current = {
          ...task,
          status: 'RUNNING',
          stateVersion: task.stateVersion + (task.status === 'QUEUED' ? 1 : 0),
        };
        claimed({ ...current });
        return { outcome: 'WAITING_HUMAN', report };
      },
    };
    const context = {
      getChannelRef: () => ({ ack: () => ack++, nack: () => nack++ }),
      getMessage: () => ({}),
    };
    const consumer = new ContractReviewConsumer(callback, workflow, {
      defer: async (channel, message, queue, kind) => {
        assert.equal(kind, 'TRANSIENT');
        channel.nack(message, false, false);
      },
    });
    await consumer.handle(event, context);
    assert.equal(nack, 1);
    assert.equal(ack, 0);
    assert.equal(current.stateVersion, eventVersion + 1);
    await consumer.handle(event, context);
    assert.equal(nack, 1);
    assert.equal(ack, 1);
    assert.deepEqual(runs, [
      { status: 'QUEUED', version: eventVersion },
      { status: 'RUNNING', version: eventVersion + 1 },
    ]);
    assert.deepEqual(versions, [eventVersion + 1, eventVersion + 1]);
    await consumer.handle(event, context);
    assert.equal(ack, 2);
    assert.equal(runs.length, 2);
    assert.equal(resultCalls, 2);
  }
});

test('合同领取重投只接受同租户同任务同文件版本同次数及相邻运行版本', async () => {
  const event = {
    taskId: '123',
    tenantId: '7',
    resourceVersionId: '456',
    attempt: 0,
    stateVersion: 4,
  };
  const task = { ...event, type: 'CONTRACT_REVIEW', status: 'RUNNING', stateVersion: 5 };
  for (const override of [
    { tenantId: '8' },
    { taskId: '124' },
    { resourceVersionId: '457' },
    { resourceVersionId: null },
    { resourceVersionId: undefined },
    { attempt: 1 },
    { type: 'CASE_ANALYSIS' },
    { stateVersion: 3 },
    { stateVersion: 6 },
    { status: 'QUEUED' },
    { status: 'WAITING_HUMAN' },
    { status: 'CANCELLED' },
    { status: 'SUCCEEDED' },
  ]) {
    let ack = 0;
    const callback = {
      getContext: async () => ({ ...task, ...override }),
      contractResult: async () => assert.fail('旧消息不得回报'),
    };
    const consumer = new ContractReviewConsumer(callback, {
      run: async () => assert.fail('不同输入或轮次不得执行'),
    });
    await consumer.handle(event, {
      getChannelRef: () => ({ ack: () => ack++, nack: () => assert.fail('过期消息应确认丢弃') }),
      getMessage: () => ({}),
    });
    assert.equal(ack, 1, JSON.stringify(override));
  }
});

test('合同消息必须携带合法固定文件版本，非法消息不读取任务及模型配置', async () => {
  for (const resourceVersionId of [
    undefined,
    null,
    '0',
    456,
    '-1',
    '9223372036854775808',
    '1'.repeat(20),
  ]) {
    let nack = 0;
    const consumer = new ContractReviewConsumer(
      { getContext: async () => assert.fail('非法事件不得读取任务') },
      { run: async () => assert.fail('非法事件不得执行') },
    );
    await consumer.handle(
      { taskId: '123', tenantId: '7', resourceVersionId, attempt: 0, stateVersion: 4 },
      {
        getChannelRef: () => ({
          ack: () => assert.fail('非法事件不得确认成功'),
          nack: () => nack++,
        }),
        getMessage: () => ({}),
      },
    );
    assert.equal(nack, 1);
  }
});

test('合同领取重投仍须通过 Java 权威领取，拒绝时不得回报报告', async () => {
  let nack = 0;
  const task = {
    taskId: '123',
    tenantId: '7',
    resourceVersionId: '456',
    type: 'CONTRACT_REVIEW',
    attempt: 0,
    status: 'RUNNING',
    stateVersion: 5,
  };
  const callback = {
    getContext: async () => task,
    contractResult: async () => assert.fail('未领取不得回报'),
  };
  const workflow = {
    run: async () => {
      throw new Error('synthetic Java begin rejected current permission');
    },
  };
  await new ContractReviewConsumer(callback, workflow, {
    defer: async (channel, message, queue, kind) => {
      assert.equal(kind, 'PERMANENT');
      channel.nack(message, false, false);
    },
  }).handle(
    { ...task, stateVersion: 4 },
    {
      getChannelRef: () => ({ ack: () => assert.fail('未领取不能确认成功'), nack: () => nack++ }),
      getMessage: () => ({}),
    },
  );
  assert.equal(nack, 1);
});

test('合同领取重投保留旧操作不确定状态，直接分类死信且不回调覆盖', async () => {
  const task = {
    taskId: '123',
    tenantId: '7',
    resourceVersionId: '456',
    type: 'CONTRACT_REVIEW',
    attempt: 0,
    status: 'RUNNING',
    stateVersion: 5,
  };
  const results = [];
  let ack = 0;
  const callback = {
    getContext: async () => task,
    contractResult: async (...args) => results.push(args),
  };
  const workflow = {
    run: async (current, claimed) => {
      claimed(current);
      throw new WorkflowOperationUncertainError();
    },
  };
  await new ContractReviewConsumer(callback, workflow, {
    defer: async (channel, message, queue, kind) => {
      assert.equal(kind, 'UNCERTAIN');
      channel.ack(message);
    },
  }).handle(
    { ...task, stateVersion: 4 },
    {
      getChannelRef: () => ({
        ack: () => ack++,
        nack: () => assert.fail('不确定状态已交付，应确认消费'),
      }),
      getMessage: () => ({}),
    },
  );
  assert.equal(ack, 1);
  assert.equal(results.length, 0);
});

test('文本解析下载真实字节并回传原文及摘要', async () => {
  const bytes = Buffer.from('测试合同\n\n付款条款', 'utf8');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const calls = [];
  let acked = false;
  const callback = {
    getContext: async () => ({
      taskId: '123',
      tenantId: '7',
      type: 'DOCUMENT_PARSE',
      status: 'QUEUED',
      attempt: 1,
      stateVersion: 2,
      mimeType: 'text/plain',
      sha256,
    }),
    downloadSource: async () => bytes,
    submitResult: async (value) => calls.push(value),
  };
  const channel = {
    ack: () => {
      acked = true;
    },
    nack: () => assert.fail('不应死信'),
  };
  await new DocumentParseConsumer(callback).handle(
    { taskId: '123', tenantId: '7', attempt: 1 },
    { getChannelRef: () => channel, getMessage: () => ({}) },
  );
  assert.equal(acked, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].success, true);
  assert.equal(calls[0].parsedText, '测试合同\n\n付款条款');
  assert.equal(calls[0].sourceSha256, sha256);
  assert.deepEqual(
    calls[0].segments.map((s) => ({
      kind: s.kind,
      index: s.index,
      text: calls[0].parsedText.slice(s.startOffset, s.endOffset),
    })),
    [
      { kind: 'PARAGRAPH', index: 1, text: '测试合同' },
      { kind: 'PARAGRAPH', index: 2, text: '付款条款' },
    ],
  );
});

test('不支持的文件格式明确失败并确认消息', async () => {
  const bytes = Buffer.from('fake-image');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const calls = [];
  let acked = false;
  const callback = {
    getContext: async () => ({
      taskId: '123',
      tenantId: '7',
      type: 'DOCUMENT_PARSE',
      status: 'QUEUED',
      attempt: 1,
      stateVersion: 2,
      mimeType: 'image/png',
      sha256,
    }),
    downloadSource: async () => bytes,
    submitResult: async (value) => calls.push(value),
  };
  await new DocumentParseConsumer(callback).handle(
    { taskId: '123', tenantId: '7', attempt: 1 },
    {
      getChannelRef: () => ({
        ack: () => {
          acked = true;
        },
        nack: () => assert.fail('格式错误不应死信'),
      }),
      getMessage: () => ({}),
    },
  );
  assert.equal(acked, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].success, false);
  assert.match(calls[0].errorMessage, /格式/);
});

test('PDF 和 DOCX 消费者下载源文件并回传真实提取文字', async () => {
  for (const [name, mime, expected] of [
    ['two-page.pdf', 'application/pdf', '【第 2 页】'],
    [
      'two-paragraph.docx',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      '付款条款',
    ],
  ]) {
    const bytes = readFileSync(join(__dirname, 'fixtures', name));
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const calls = [];
    let acked = false;
    const callback = {
      getContext: async () => ({
        taskId: '123',
        tenantId: '7',
        type: 'DOCUMENT_PARSE',
        status: 'QUEUED',
        attempt: 1,
        stateVersion: 2,
        mimeType: mime,
        sha256,
      }),
      downloadSource: async () => bytes,
      submitResult: async (value) => calls.push(value),
    };
    await new DocumentParseConsumer(callback).handle(
      { taskId: '123', tenantId: '7', attempt: 1 },
      {
        getChannelRef: () => ({
          ack: () => {
            acked = true;
          },
          nack: () => assert.fail('不应死信'),
        }),
        getMessage: () => ({}),
      },
    );
    assert.equal(acked, true);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].success, true);
    assert.match(calls[0].parsedText, new RegExp(expected));
    assert.equal(calls[0].sourceSha256, sha256);
    assert.equal(calls[0].segments.length, 2);
    assert.deepEqual(
      calls[0].segments.map((s) => s.index),
      [1, 2],
    );
    assert.deepEqual(
      [...new Set(calls[0].segments.map((s) => s.kind))],
      [mime === 'application/pdf' ? 'PAGE' : 'PARAGRAPH'],
    );
    assert.ok(
      calls[0].segments.every(
        (s) => calls[0].parsedText.slice(s.startOffset, s.endOffset).length > 0,
      ),
    );
  }
});

for (const Consumer of [ContractReviewConsumer, DocumentParseConsumer]) {
  test(`${Consumer.name}: 旧次数、跨租户、终态消息不会回报结果`, async () => {
    for (const override of [{ attempt: 2 }, { tenantId: '8' }, { status: 'SUCCEEDED' }]) {
      let acked = false;
      const callback = {
        getContext: async () => ({
          taskId: '123',
          resourceVersionId: '456',
          attempt: 1,
          stateVersion: 1,
          tenantId: '7',
          status: 'QUEUED',
          type: Consumer === ContractReviewConsumer ? 'CONTRACT_REVIEW' : 'DOCUMENT_PARSE',
          ...override,
        }),
        submitResult: async () => assert.fail('不应覆盖当前任务'),
      };
      const context = {
        getChannelRef: () => ({
          ack: () => {
            acked = true;
          },
          nack: () => assert.fail('过期消息应确认丢弃'),
        }),
        getMessage: () => ({}),
      };
      await new Consumer(callback, { run: async () => assert.fail('stale task') }).handle(
        { taskId: '123', tenantId: '7', resourceVersionId: '456', attempt: 1, stateVersion: 1 },
        context,
      );
      assert.equal(acked, true);
    }
  });
}

test('知识索引未获模型域名授权时只回报失败，不写入向量库', async () => {
  const previous = process.env.LEXFLOW_AI_ALLOWED_HOSTS;
  delete process.env.LEXFLOW_AI_ALLOWED_HOSTS;
  try {
    const results = [];
    let acked = false;
    const callback = {
      getContext: async () => ({
        taskId: '123',
        tenantId: '7',
        caseId: '8',
        type: 'KNOWLEDGE_INGEST',
        status: 'QUEUED',
        attempt: 0,
        stateVersion: 1,
      }),
      getIndexInput: async () => ({
        tenantId: '7',
        caseId: '8',
        knowledgeBaseId: '9',
        documentId: '10',
        fileVersionId: '11',
        indexVersion: 1,
        provider: 'test',
        baseUrl: 'https://models.example.test/v1',
        model: 'embed-v1',
        apiKey: 'fake-key',
        chunks: [{ id: '12', text: '付款条款' }],
      }),
      submitIndexResult: async (value) => results.push(value),
    };
    await new KnowledgeIndexConsumer(callback).handle(
      { taskId: '123', tenantId: '7', type: 'KNOWLEDGE_INGEST', attempt: 0 },
      {
        getChannelRef: () => ({
          ack: () => {
            acked = true;
          },
          nack: () => assert.fail('应已回报失败'),
        }),
        getMessage: () => ({}),
      },
    );
    assert.equal(acked, true);
    assert.deepEqual(
      results.map((value) => value.success),
      [false],
    );
  } finally {
    if (previous === undefined) delete process.env.LEXFLOW_AI_ALLOWED_HOSTS;
    else process.env.LEXFLOW_AI_ALLOWED_HOSTS = previous;
  }
});

test('知识索引旧次数与跨租户消息不得获取模型 Key', async () => {
  for (const override of [{ attempt: 2 }, { tenantId: '99' }, { status: 'SUCCEEDED' }]) {
    let acked = false;
    const callback = {
      getContext: async () => ({
        taskId: '123',
        tenantId: '7',
        caseId: '8',
        type: 'KNOWLEDGE_INGEST',
        status: 'QUEUED',
        attempt: 0,
        stateVersion: 1,
        ...override,
      }),
      getIndexInput: async () => assert.fail('不得读取 Key'),
      submitIndexResult: async () => assert.fail('不得回报'),
    };
    await new KnowledgeIndexConsumer(callback).handle(
      { taskId: '123', tenantId: '7', type: 'KNOWLEDGE_INGEST', attempt: 0 },
      {
        getChannelRef: () => ({
          ack: () => {
            acked = true;
          },
          nack: () => assert.fail('过期消息应确认丢弃'),
        }),
        getMessage: () => ({}),
      },
    );
    assert.equal(acked, true);
  }
});

test('公共索引消息、任务和输入必须保持同一租户、显式公共范围及知识库', async () => {
  const previous = process.env.LEXFLOW_AI_ALLOWED_HOSTS;
  delete process.env.LEXFLOW_AI_ALLOWED_HOSTS;
  try {
    const baseEvent = {
      taskId: '123',
      tenantId: '7',
      type: 'KNOWLEDGE_INGEST',
      attempt: 0,
      scope: 'TENANT',
      caseId: null,
      knowledgeBaseId: '9',
    };
    const baseTask = { ...baseEvent, status: 'QUEUED', stateVersion: 1 };
    const baseInput = {
      tenantId: '7',
      scope: 'TENANT',
      caseId: null,
      knowledgeBaseId: '9',
      documentId: '10',
      fileVersionId: '11',
      indexVersion: 1,
      provider: 'test',
      baseUrl: 'https://models.example.test/v1',
      model: 'embed-v1',
      chunks: [{ id: '12', text: '付款条款' }],
    };
    for (const fixture of [
      { name: '合法公共三路范围', models: 1, inputs: 1 },
      {
        name: '公共任务不能接收缺省范围的旧消息',
        event: { scope: undefined, caseId: undefined },
        models: 0,
        inputs: 0,
      },
      { name: '消息伪装案件', event: { scope: 'CASE', caseId: '8' }, models: 0, inputs: 0 },
      { name: '消息知识库变化', event: { knowledgeBaseId: '99' }, models: 0, inputs: 0 },
      { name: '任务编号变化', task: { taskId: '124' }, models: 0, inputs: 0 },
      { name: '输入租户变化', input: { tenantId: '99' }, models: 0, inputs: 1 },
      { name: '输入案件变化', input: { scope: 'CASE', caseId: '8' }, models: 0, inputs: 1 },
      { name: '输入知识库变化', input: { knowledgeBaseId: '99' }, models: 0, inputs: 1 },
      { name: '输入缺少公共 scope', input: { scope: undefined }, models: 0, inputs: 1 },
    ]) {
      let inputs = 0,
        models = 0,
        acked = false;
      const results = [];
      const source = { ...baseInput, ...fixture.input };
      Object.defineProperty(source, 'apiKey', {
        enumerable: true,
        get() {
          models++;
          return 'fake-key';
        },
      });
      const callback = {
        getContext: async () => ({ ...baseTask, ...fixture.task }),
        getIndexInput: async () => {
          inputs++;
          return source;
        },
        submitIndexResult: async (result) => results.push(result),
      };
      await new KnowledgeIndexConsumer(callback).handle(
        { ...baseEvent, ...fixture.event },
        {
          getChannelRef: () => ({
            ack: () => {
              acked = true;
            },
            nack: () => assert.fail(fixture.name),
          }),
          getMessage: () => ({}),
        },
      );
      assert.equal(acked, true, fixture.name);
      assert.equal(inputs, fixture.inputs, fixture.name);
      assert.equal(models, fixture.models, fixture.name);
      assert.deepEqual(
        results.map((result) => result.success),
        fixture.inputs ? [false] : [],
        fixture.name,
      );
    }
    for (const invalidEvent of [
      { scope: 'TENANT', caseId: undefined },
      { scope: 'TENANT', caseId: '0' },
      { scope: undefined, caseId: null },
      { scope: 'CASE', caseId: '0' },
      { scope: 'PUBLIC', caseId: null },
      { tenantId: '9223372036854775808' },
    ]) {
      let rejected = false;
      await new KnowledgeIndexConsumer({
        getContext: async () => assert.fail('非法消息不得读取任务/Key'),
      }).handle(
        { ...baseEvent, ...invalidEvent },
        {
          getChannelRef: () => ({
            ack: () => assert.fail('非法消息不得确认成功'),
            nack: () => {
              rejected = true;
            },
          }),
          getMessage: () => ({}),
        },
      );
      assert.equal(rejected, true);
    }
  } finally {
    if (previous === undefined) delete process.env.LEXFLOW_AI_ALLOWED_HOSTS;
    else process.env.LEXFLOW_AI_ALLOWED_HOSTS = previous;
  }
});
