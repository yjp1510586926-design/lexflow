require('reflect-metadata');
require('@nestjs/core');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomInt } = require('node:crypto');
const { DocumentGenerationConsumer } = require('../dist/consumers/document-generation.consumer');
const {
  runDocumentGeneration,
  validateGeneratedDraft,
  generationIdentity,
} = require('../dist/graphs/document-generation.graph');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');
const {
  TenantMySqlCheckpointer,
  workflowThreadId,
  WorkflowOperationUncertainError,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const enabled = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
function input(task = '123') {
  return {
    tenantId: '910000180',
    taskId: task,
    caseId: '11',
    documentId: '12',
    sourceVersionId: '13',
    attempt: 0,
    stateVersion: 2,
    title: '催款函',
    baseContent: '甲欠乙 100 元。',
    instructions: '起草催款函',
    caseContext: JSON.stringify({
      caseNo: 'TEST',
      title: '合成案件',
      description: '甲欠乙 100 元。',
    }),
    modelRevision: '00000000-0000-0000-0000-000000000018',
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'draft-test',
    apiKey: 'fake-generation-key',
  };
}
test('文书生成输出长度和待补信息严格校验，凭据不参与持久输入摘要', () => {
  assert.deepEqual(validateGeneratedDraft({ content: ' 正文 ', missingInformation: [' 地址 '] }), {
    content: '正文',
    missingInformation: ['地址'],
  });
  for (const v of [
    { content: '', missingInformation: [] },
    { content: 'x'.repeat(30001), missingInformation: [] },
    { content: '正文', missingInformation: [''] },
    { content: '正文', missingInformation: ['x'.repeat(201)] },
    null,
  ])
    assert.throws(() => validateGeneratedDraft(v));
  assert.equal(
    generationIdentity(input()),
    generationIdentity({ ...input(), apiKey: 'replaced-runtime-key' }),
  );
  assert.notEqual(
    generationIdentity(input()),
    generationIdentity({ ...input(), modelRevision: '00000000-0000-0000-0000-000000000019' }),
  );
});
test('生成消费者跨租户事件丢弃，内部输入409永久死信待权威collector判别，非法事件死信', async () => {
  for (const scenario of ['tenant', 'finished', 'invalid']) {
    let ack = 0,
      nack = 0,
      calls = 0;
    const callback = {
      generationInput: async () => {
        if (scenario === 'finished') throw { response: { status: 409 } };
        return { ...input(), tenantId: '910000181' };
      },
    };
    const consumer = new DocumentGenerationConsumer(
      callback,
      {
        run: async () => {
          calls++;
          assert.fail('not authorized');
        },
      },
      {
        defer: async (channel, message, queue, kind) => {
          assert.equal(kind, 'PERMANENT');
          channel.nack(message, false, false);
        },
      },
    );
    const event =
      scenario === 'invalid'
        ? { tenantId: '0', taskId: '123', resourceVersionId: '13', attempt: 0, stateVersion: 1 }
        : {
            tenantId: '910000180',
            taskId: '123',
            resourceVersionId: '13',
            attempt: 0,
            stateVersion: 1,
          };
    await consumer.handle(event, {
      getChannelRef: () => ({ ack: () => ack++, nack: () => nack++ }),
      getMessage: () => ({}),
    });
    assert.equal(calls, 0);
    assert.equal(ack, scenario === 'tenant' ? 1 : 0);
    assert.equal(nack, scenario === 'tenant' ? 0 : 1);
  }
});
test('生成不确定时直接分类死信，不回调覆盖旧操作日志', async () => {
  const calls = [];
  let ack = 0;
  const callback = {
    generationInput: async () => input(),
    generationResult: async (...args) => calls.push(args),
  };
  const workflow = {
    run: async (task, claimed) => {
      claimed({ ...task, stateVersion: 2 });
      throw new WorkflowOperationUncertainError();
    },
  };
  await new DocumentGenerationConsumer(callback, workflow, {
    defer: async (channel, message, queue, kind) => {
      assert.equal(kind, 'UNCERTAIN');
      channel.ack(message);
    },
  }).handle(
    { tenantId: '910000180', taskId: '123', resourceVersionId: '13', attempt: 0, stateVersion: 1 },
    {
      getChannelRef: () => ({
        ack: () => ack++,
        nack: () => assert.fail('valid uncertain result'),
      }),
      getMessage: () => ({}),
    },
  );
  assert.equal(ack, 1);
  assert.equal(calls.length, 0);
});
test('生成结果回调失败进入死信，不重新运行模型或读取新版本', async () => {
  let invoked = 0,
    reads = 0,
    nack = 0;
  const callback = {
    generationInput: async () => {
      reads++;
      return input();
    },
    generationResult: async () => {
      throw { response: { status: 503 } };
    },
  };
  const workflow = {
    run: async (task, claimed) => {
      invoked++;
      claimed(task);
      return { content: '待复核正文', missingInformation: [] };
    },
  };
  await new DocumentGenerationConsumer(callback, workflow).handle(
    { tenantId: '910000180', taskId: '123', resourceVersionId: '13', attempt: 0, stateVersion: 1 },
    {
      getChannelRef: () => ({
        ack: () => assert.fail('callback not received'),
        nack: () => nack++,
      }),
      getMessage: () => ({}),
    },
  );
  assert.equal(nack, 1);
  assert.equal(invoked, 1);
  assert.equal(reads, 1);
});
async function fixture() {
  const url =
    process.env.AI_WORKFLOW_DATABASE_URL ??
    'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  assert.equal(parsed.username, 'lexflow_ai');
  const pool = workflowPool(url),
    value = input(String(Date.now()) + randomInt(1000, 9999)),
    saver = new TenantMySqlCheckpointer(pool, value.tenantId);
  await setupWorkflowStore(pool);
  return {
    pool,
    value,
    saver,
    cleanup: async () => {
      try {
        await saver.deleteThread(workflowThreadId(value.tenantId, value.taskId));
      } finally {
        await pool.end();
      }
    },
  };
}
test(
  '真实 MySQL 生成图恢复复用一次模型结果，不保存 Key，改变输入拒绝',
  { skip: !enabled },
  async () => {
    const f = await fixture();
    let calls = 0;
    try {
      const runtime = {
        assertAuthorized: async () => {},
        model: {
          invoke: async (messages) => {
            calls++;
            assert.match(messages[1].content, /100 元/);
            return {
              content: JSON.stringify({
                content: '请返还 100 元。【待补充：收件地址】',
                missingInformation: ['收件地址'],
              }),
            };
          },
        },
      };
      const first = await runDocumentGeneration(f.value, f.saver, runtime);
      assert.equal(first.missingInformation[0], '收件地址');
      const resumed = await runDocumentGeneration(
        f.value,
        new TenantMySqlCheckpointer(f.pool, f.value.tenantId),
        runtime,
      );
      assert.deepEqual(resumed, first);
      assert.equal(calls, 1);
      const [rows] = await f.pool.query(
        'SELECT checkpoint_blob,metadata_blob FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
        [f.value.tenantId, workflowThreadId(f.value.tenantId, f.value.taskId)],
      );
      assert.equal(
        rows.some(
          (r) =>
            r.checkpoint_blob.includes(Buffer.from(f.value.apiKey)) ||
            r.metadata_blob.includes(Buffer.from(f.value.apiKey)),
        ),
        false,
      );
      const [ops] = await f.pool.query(
        'SELECT result_blob FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [f.value.tenantId, workflowThreadId(f.value.tenantId, f.value.taskId)],
      );
      assert.equal(ops.length, 1);
      assert.equal(ops[0].result_blob.includes(Buffer.from(f.value.apiKey)), false);
      await assert.rejects(
        runDocumentGeneration({ ...f.value, instructions: '改变要求' }, f.saver, runtime),
        /已保存工作流/,
      );
      assert.equal(calls, 1);
    } finally {
      await f.cleanup();
    }
  },
);
test(
  '生成后撤权不能交付，恢复后复用模型结果，未完成外部意图不重发',
  { skip: !enabled },
  async () => {
    const f = await fixture();
    let calls = 0,
      revoked = false;
    try {
      const runtime = {
        assertAuthorized: async () => {
          if (revoked) throw new Error('revoked');
        },
        model: {
          invoke: async () => {
            calls++;
            revoked = true;
            return { content: JSON.stringify({ content: '待复核正文', missingInformation: [] }) };
          },
        },
      };
      await assert.rejects(runDocumentGeneration(f.value, f.saver, runtime), /权限/);
      assert.equal(calls, 1);
      revoked = false;
      assert.equal((await runDocumentGeneration(f.value, f.saver, runtime)).content, '待复核正文');
      assert.equal(calls, 1);
    } finally {
      await f.cleanup();
    }
    const g = await fixture();
    let paid = 0;
    try {
      await g.pool.query(
        "INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token) VALUES(?,?,'document-draft-v1',?,'STARTED','00000000-0000-0000-0000-000000000001')",
        [
          g.value.tenantId,
          workflowThreadId(g.value.tenantId, g.value.taskId),
          generationIdentity(g.value),
        ],
      );
      await assert.rejects(
        runDocumentGeneration(g.value, g.saver, {
          assertAuthorized: async () => {},
          model: {
            invoke: async () => {
              paid++;
              assert.fail('must not resend');
            },
          },
        }),
        WorkflowOperationUncertainError,
      );
      assert.equal(paid, 0);
    } finally {
      await g.cleanup();
    }
  },
);
