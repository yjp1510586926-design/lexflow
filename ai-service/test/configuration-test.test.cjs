require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID, randomInt } = require('node:crypto');
const { ConfigurationTestController } = require('../dist/ai/configuration-test.controller');
const {
  createConfigurationProbe,
  validateConfigurationTest,
  configurationTestInputHash,
  executeConfigurationTest,
} = require('../dist/ai/configuration-test.service');
const {
  TenantMySqlCheckpointer,
  configurationTestThreadId,
  workflowThreadId,
} = require('../dist/graphs/persistence/mysql-checkpointer');
const { workflowPool } = require('../dist/graphs/persistence/workflow-pool');
const { setupWorkflowStore } = require('../dist/graphs/persistence/setup-workflow-store');

const hosts = 'models.example.test';
const input = {
  tenantId: '910000111',
  testId: '1',
  purpose: 'LLM',
  revision: '666b7ea1-37c4-4cf3-82c7-12efcd5c3e0a',
  provider: 'synthetic',
  baseUrl: 'https://models.example.test/v1',
  model: 'test-model',
  apiKey: 'fake-test-secret',
};
const secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';
const localIntegration = process.env.LEXFLOW_WORKFLOW_INTEGRATION === 'true';
const url =
  process.env.AI_WORKFLOW_DATABASE_URL ??
  'mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai';
function safe(value) {
  assert.equal(JSON.stringify(value).includes(input.apiKey), false);
  assert.equal(JSON.stringify(value).includes('private-case-text'), false);
}

test('连接测试鉴权先于参数、数据库和模型；越界身份及未授权地址均不进入服务', async () => {
  let calls = 0;
  const controller = new ConfigurationTestController({
    test: async () => {
      calls++;
      assert.fail('must not invoke');
    },
  });
  for (const supplied of [undefined, 'incorrect-secret', [], secret + 'extra']) {
    try {
      await controller.test(supplied, undefined);
      assert.fail('must reject');
    } catch (error) {
      assert.equal(error.getStatus(), 401);
    }
  }
  for (const bad of [
    undefined,
    [],
    { ...input, tenantId: '0' },
    { ...input, testId: '9223372036854775808' },
    { ...input, revision: 'not-uuid' },
    { ...input, purpose: 'CASE_ANALYSIS' },
    { ...input, baseUrl: 'http://models.example.test/v1' },
    { ...input, baseUrl: 'https://models.example.test.evil.test/v1' },
    { ...input, baseUrl: 'https://user:pass@models.example.test/v1' },
    { ...input, baseUrl: 'https://models.example.test/v1?apiKey=fake-test-secret' },
    { ...input, apiKey: 'bad\r\nheader' },
    { ...input, apiKey: '' },
    { ...input, model: 'm'.repeat(121) },
  ]) {
    assert.throws(() => validateConfigurationTest(bad, hosts));
    try {
      await controller.test(secret, bad);
      assert.fail('must reject');
    } catch (error) {
      assert.equal(error.getStatus(), 400);
      safe(error.getResponse());
    }
  }
  assert.equal(calls, 0);
  assert.throws(() => createConfigurationProbe(input, ''), /授权/);
  assert.equal(configurationTestThreadId('7', '123'), 'lft:7:123');
  assert.notEqual(configurationTestThreadId('7', '123'), workflowThreadId('7', '123'));
  assert.equal(
    configurationTestInputHash(input),
    configurationTestInputHash({ ...input, apiKey: 'changed-key' }),
  );
  assert.notEqual(
    configurationTestInputHash(input),
    configurationTestInputHash({ ...input, revision: randomUUID() }),
  );
});

test('三种探测只使用合成文本，返回安全能力结果，不传案件原文或保存模型回答', async () => {
  let calls = 0;
  const llm = createConfigurationProbe(input, hosts, {
    llm: () => ({
      invoke: async (messages, options) => {
        calls++;
        assert.match(messages[1].content, /连接测试.*合成文本/);
        assert.equal(options.response_format.type, 'json_object');
        return { content: '{"ok":true}' };
      },
    }),
  });
  const result = await llm.probe();
  assert.equal(result.status, 'SUCCEEDED');
  assert.deepEqual(Object.keys(result).sort(), ['elapsedMs', 'status']);
  safe(result);
  const embedding = createConfigurationProbe({ ...input, purpose: 'EMBEDDING' }, hosts, {
    embeddings: () => ({
      embedDocuments: async (texts) => {
        calls++;
        assert.equal(texts.length, 1);
        assert.match(texts[0], /合成文本/);
        return [[1, 0.5, -1]];
      },
    }),
  });
  assert.deepEqual(
    { ...(await embedding.probe()), elapsedMs: 0 },
    { status: 'SUCCEEDED', elapsedMs: 0, dimension: 3 },
  );
  const rerank = createConfigurationProbe({ ...input, purpose: 'RERANK' }, hosts, {
    rerank: async (request, config, allowed) => {
      calls++;
      assert.equal(allowed, hosts);
      assert.equal(request.limit, 2);
      assert.equal(request.documents.length, 2);
      assert.equal(
        request.documents.every((text) => text.includes('合成文本')),
        true,
      );
      assert.equal(config.apiKey, input.apiKey);
      return [
        { index: 1, score: 0.9 },
        { index: 0, score: 0.1 },
      ];
    },
  });
  assert.equal((await rerank.probe()).status, 'SUCCEEDED');
  assert.equal(calls, 3);
});

test('错误模型正文及非有限、全零、超范围向量不判定连接成功', async () => {
  for (const content of [
    'not-json',
    '{"ok":false}',
    '{"ok":true,"private":"private-case-text"}',
    [{ text: '{"ok":true}' }],
    'x'.repeat(257),
  ]) {
    const { probe } = createConfigurationProbe(input, hosts, {
      llm: () => ({ invoke: async () => ({ content }) }),
    });
    const result = await probe();
    assert.equal(result.status, 'FAILED');
    assert.equal(result.errorCode, 'RESPONSE_INVALID');
    safe(result);
  }
  for (const vectors of [
    [],
    [[1]],
    [[0, 0]],
    [[1, Infinity]],
    [[1, '0']],
    [
      [1, 0],
      [1, 0],
    ],
    [Array(4097).fill(1)],
  ]) {
    const { probe } = createConfigurationProbe({ ...input, purpose: 'EMBEDDING' }, hosts, {
      embeddings: () => ({ embedDocuments: async () => vectors }),
    });
    const result = await probe();
    assert.equal(result.status, 'FAILED');
    assert.equal(result.errorCode, 'RESPONSE_INVALID');
    safe(result);
  }
  for (const ranks of [
    [
      { index: 0, score: 0.9 },
      { index: 0, score: 0.3 },
    ],
    [
      { index: 0, score: 1.1 },
      { index: 1, score: 0.3 },
    ],
    [],
  ]) {
    const { probe } = createConfigurationProbe({ ...input, purpose: 'RERANK' }, hosts, {
      rerank: async () => ranks,
    });
    assert.equal((await probe()).status, 'FAILED');
  }
});

test('错误 Key 与超时结果区分，探测不自动重试且供应商异常不进入输出', async () => {
  for (const [error, status, code] of [
    [
      Object.assign(Error(input.apiKey + ' private-case-text'), { status: 401 }),
      'FAILED',
      'REQUEST_FAILED',
    ],
    [
      new DOMException(input.apiKey + ' private-case-text', 'TimeoutError'),
      'UNKNOWN',
      'RESULT_UNCERTAIN',
    ],
    [Error(input.apiKey + ' private-case-text'), 'UNKNOWN', 'RESULT_UNCERTAIN'],
  ]) {
    let calls = 0;
    const { probe } = createConfigurationProbe(input, hosts, {
      llm: () => ({
        invoke: async () => {
          calls++;
          throw error;
        },
      }),
    });
    const result = await probe();
    assert.equal(result.status, status);
    assert.equal(result.errorCode, code);
    assert.equal(calls, 1);
    safe(result);
  }
});

test('真实适配器发出小规模 HTTPS 请求，32 token、单文本与零重试限制生效', async () => {
  const original = global.fetch;
  let calls = 0,
    mode = 'success';
  try {
    global.fetch = async (address, options) => {
      calls++;
      const target = String(address);
      assert.match(target, /^https:\/\/models\.example\.test\/v1\//);
      assert.equal(options.redirect, 'error');
      assert.ok(options.signal instanceof AbortSignal);
      const body = JSON.parse(options.body);
      if (mode === '401')
        return new Response(
          JSON.stringify({
            error: { message: input.apiKey + ' private-case-text', type: 'invalid_api_key' },
          }),
          { status: 401, headers: { 'Content-Type': 'application/json' } },
        );
      if (target.endsWith('/chat/completions')) {
        assert.equal(body.max_tokens ?? body.max_completion_tokens, 32);
        assert.equal(body.response_format.type, 'json_object');
        assert.equal(body.messages.length, 2);
        assert.match(body.messages[1].content, /合成文本/);
        return new Response(
          JSON.stringify({
            id: 'synthetic',
            object: 'chat.completion',
            created: 1,
            model: input.model,
            choices: [
              {
                index: 0,
                message: { role: 'assistant', content: '{"ok":true}' },
                finish_reason: 'stop',
              },
            ],
            usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
          }),
          { headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (target.endsWith('/embeddings')) {
        assert.equal(body.input.length, 1);
        assert.match(body.input[0], /合成文本/);
        return new Response(
          JSON.stringify({
            object: 'list',
            model: input.model,
            data: [{ object: 'embedding', index: 0, embedding: [1, 0, 0] }],
            usage: { prompt_tokens: 1, total_tokens: 1 },
          }),
          { headers: { 'Content-Type': 'application/json' } },
        );
      }
      assert.ok(target.endsWith('/rerank'));
      assert.equal(body.top_n, 2);
      assert.equal(body.documents.length, 2);
      return new Response(
        JSON.stringify({
          results: [
            { index: 0, relevance_score: 0.9 },
            { index: 1, relevance_score: 0.1 },
          ],
        }),
        { headers: { 'Content-Type': 'application/json' } },
      );
    };
    for (const purpose of ['LLM', 'EMBEDDING', 'RERANK']) {
      const { probe } = createConfigurationProbe({ ...input, purpose }, hosts);
      const result = await probe();
      assert.equal(result.status, 'SUCCEEDED');
      safe(result);
    }
    assert.equal(calls, 3);
    mode = '401';
    for (const purpose of ['LLM', 'EMBEDDING', 'RERANK']) {
      const { probe } = createConfigurationProbe({ ...input, purpose }, hosts);
      const result = await probe();
      assert.equal(result.status, 'FAILED');
      assert.equal(result.errorCode, 'REQUEST_FAILED');
      safe(result);
    }
    assert.equal(calls, 6);
  } finally {
    global.fetch = original;
  }
});

function integrationPool() {
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1');
  assert.equal(parsed.port, '13306');
  assert.equal(parsed.pathname, '/lexflow_ai');
  assert.equal(parsed.username, 'lexflow_ai');
  return workflowPool(url);
}
function testConfig(value) {
  return {
    configurable: {
      tenant_id: value.tenantId,
      thread_id: configurationTestThreadId(value.tenantId, value.testId),
      checkpoint_ns: '',
    },
  };
}

test(
  '真实 MySQL 对成功、失败和不确定结果持久去重，Key 不入日志且工作流命名空间与租户隔离',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool(),
      tenant = '910000111',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant);
    const other = new TenantMySqlCheckpointer(pool, '910000112'),
      created = [];
    let calls = 0;
    try {
      await setupWorkflowStore(pool);
      for (const [i, status] of ['SUCCEEDED', 'FAILED', 'UNKNOWN'].entries()) {
        const value = { ...input, tenantId: tenant, testId: String(BigInt(task) + BigInt(i)) };
        created.push(configurationTestThreadId(tenant, value.testId));
        const first = await executeConfigurationTest(value, saver, async () => {
          calls++;
          return {
            status,
            elapsedMs: 3,
            ...(status === 'SUCCEEDED' ? {} : { errorCode: 'REQUEST_FAILED' }),
          };
        });
        const fresh = new TenantMySqlCheckpointer(pool, tenant);
        assert.deepEqual(
          await executeConfigurationTest({ ...value, apiKey: 'another-key' }, fresh, async () => {
            calls++;
            assert.fail('must reuse');
          }),
          first,
        );
        const conflict = await executeConfigurationTest(
          { ...value, revision: randomUUID() },
          fresh,
          async () => {
            calls++;
            assert.fail('changed revision');
          },
        );
        assert.equal(conflict.status, 'FAILED');
        assert.equal(conflict.errorCode, 'INPUT_CONFLICT');
      }
      assert.equal(calls, 3);
      const [rows] = await pool.execute(
        'SELECT status,error_code,result_blob FROM workflow_operation WHERE tenant_id=? AND thread_id IN (?,?,?)',
        [tenant, ...created],
      );
      assert.equal(rows.length, 3);
      assert.equal(
        rows.every((row) => row.status === 'SUCCEEDED'),
        true,
      );
      assert.equal(
        rows.some((row) => row.result_blob?.includes(Buffer.from(input.apiKey))),
        false,
      );
      assert.equal(
        rows.some((row) => row.result_blob?.includes(Buffer.from('another-key'))),
        false,
      );
      const otherInput = { ...input, tenantId: '910000112', testId: task };
      assert.equal(
        (
          await executeConfigurationTest(otherInput, other, async () => {
            calls++;
            return { status: 'SUCCEEDED', elapsedMs: 1 };
          })
        ).status,
        'SUCCEEDED',
      );
      assert.equal(calls, 4);
      assert.equal(
        (
          await executeConfigurationTest(
            { ...input, tenantId: tenant, testId: task },
            other,
            async () => {
              calls++;
              assert.fail('cross tenant');
            },
          )
        ).status,
        'UNKNOWN',
      );
      assert.equal(calls, 4);
      // A regular task with the same ID uses a different lock and journal keyspace.
      const graph = {
        configurable: {
          tenant_id: tenant,
          thread_id: workflowThreadId(tenant, task),
          checkpoint_ns: '',
        },
      };
      created.push(graph.configurable.thread_id);
      assert.equal(
        await saver.withThreadLock(graph, () =>
          saver.runOnce(
            graph,
            'connection_probe_v1',
            configurationTestInputHash(input),
            async () => 'separate task result',
          ),
        ),
        'separate task result',
      );
      await saver.deleteThread(graph.configurable.thread_id);
    } finally {
      try {
        for (const id of created) await saver.deleteThread(id);
        await other.deleteThread(configurationTestThreadId('910000112', task));
      } finally {
        await pool.end();
      }
    }
  },
);

test(
  '真实 MySQL 并发探测返回 PROCESSING，遗留 STARTED 返回 UNKNOWN 且不重复外部调用',
  { skip: !localIntegration },
  async () => {
    const pool = integrationPool(),
      tenant = '910000113',
      task = String(Date.now()) + randomInt(1000, 9999),
      saver = new TenantMySqlCheckpointer(pool, tenant),
      value = { ...input, tenantId: tenant, testId: task };
    const config = testConfig(value);
    let release,
      started,
      calls = 0,
      held;
    try {
      await setupWorkflowStore(pool);
      const pause = new Promise((resolve) => (release = resolve)),
        ready = new Promise((resolve) => (started = resolve));
      held = executeConfigurationTest(value, saver, async () => {
        calls++;
        started();
        await pause;
        return { status: 'SUCCEEDED', elapsedMs: 2 };
      });
      await ready;
      const busy = await executeConfigurationTest(
        value,
        new TenantMySqlCheckpointer(pool, tenant),
        async () => {
          calls++;
          assert.fail('competing call');
        },
      );
      assert.equal(busy.status, 'PROCESSING');
      assert.equal(busy.errorCode, 'TEST_BUSY');
      assert.equal(calls, 1);
      release();
      assert.equal((await held).status, 'SUCCEEDED');
      held = undefined;
      await saver.deleteThread(config.configurable.thread_id);
      // This is the durable state left by a process interrupted after reserving its provider call.
      await saver.withThreadLock(config, async () => undefined);
      await pool.execute(
        "INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token) VALUES(?,?,'connection_probe_v1',?,'STARTED',?)",
        [tenant, config.configurable.thread_id, configurationTestInputHash(value), randomUUID()],
      );
      const unknown = await executeConfigurationTest(
        value,
        new TenantMySqlCheckpointer(pool, tenant),
        async () => {
          calls++;
          assert.fail('uncertain call must not retry');
        },
      );
      assert.equal(unknown.status, 'UNKNOWN');
      assert.equal(unknown.errorCode, 'RESULT_UNCERTAIN');
      assert.equal(calls, 1);
      const [rows] = await pool.execute(
        'SELECT status,result_blob FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
        [tenant, config.configurable.thread_id],
      );
      assert.equal(rows[0].status, 'STARTED');
      assert.equal(rows[0].result_blob, null);
    } finally {
      release?.();
      if (held) await held;
      try {
        await saver.deleteThread(config.configurable.thread_id);
      } finally {
        await pool.end();
      }
    }
  },
);
