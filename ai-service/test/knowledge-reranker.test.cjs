require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { rerankDocuments } = require('../dist/rag/knowledge-reranker');
const { KnowledgeRerankController } = require('../dist/rag/knowledge-rerank.controller');
const config = {
  provider: 'test',
  baseUrl: 'https://models.example.test/v2',
  model: 'rerank-test',
  apiKey: 'fake-rerank-key',
};
const input = {
  query: '何时付款？',
  documents: ['交付方式：七日内交付。', '付款条款：验收后付款。'],
  limit: 10,
};
const response = (results) => new Response(JSON.stringify({ results }), { status: 200 });

test('Cohere 兼容请求只传授权原文，排序编号映射回原输入', async () => {
  const ranks = await rerankDocuments(
    input,
    config,
    'models.example.test',
    async (url, options) => {
      assert.equal(url, 'https://models.example.test/v2/rerank');
      assert.equal(options.redirect, 'error');
      assert.equal(options.headers.Authorization, 'Bearer fake-rerank-key');
      assert.ok(options.signal instanceof AbortSignal);
      assert.deepEqual(JSON.parse(options.body), {
        model: 'rerank-test',
        query: input.query,
        documents: input.documents,
        top_n: 2,
      });
      return response([
        { index: 0, relevance_score: 0.2, document: { text: '服务商伪造原文' } },
        { index: 1, relevance_score: 0.9 },
      ]);
    },
  );
  assert.deepEqual(ranks, [
    { index: 1, score: 0.9 },
    { index: 0, score: 0.2 },
  ]);
});
test('缺 Key、未授权地址及越界输入不发起模型请求', async () => {
  let calls = 0;
  const send = async () => {
    calls++;
    throw Error('must not call');
  };
  await assert.rejects(
    rerankDocuments(input, { ...config, apiKey: '' }, 'models.example.test', send),
    /配置/,
  );
  await assert.rejects(rerankDocuments(input, config, '', send), /授权/);
  await assert.rejects(
    rerankDocuments(
      input,
      { ...config, baseUrl: 'http://models.example.test/v2' },
      'models.example.test',
      send,
    ),
    /授权/,
  );
  await assert.rejects(
    rerankDocuments({ ...input, documents: [] }, config, 'models.example.test', send),
    /参数/,
  );
  await assert.rejects(
    rerankDocuments(
      { ...input, documents: ['a'.repeat(1001)] },
      config,
      'models.example.test',
      send,
    ),
    /参数/,
  );
  assert.equal(calls, 0);
});
test('重复、越界、缺失编号与非有限或越界分数均拒绝', async () => {
  for (const results of [
    [
      { index: 0, relevance_score: 0.8 },
      { index: 0, relevance_score: 0.2 },
    ],
    [
      { index: 2, relevance_score: 0.8 },
      { index: 0, relevance_score: 0.2 },
    ],
    [{ index: 1, relevance_score: 0.8 }],
    [
      { index: 0, relevance_score: '0.8' },
      { index: 1, relevance_score: 0.2 },
    ],
    [
      { index: 0, relevance_score: 1.1 },
      { index: 1, relevance_score: 0.2 },
    ],
  ])
    await assert.rejects(
      rerankDocuments(input, config, 'models.example.test', async () => response(results)),
      /结果/,
    );
  await assert.rejects(
    rerankDocuments(
      input,
      config,
      'models.example.test',
      async () => new Response('a'.repeat(100001)),
    ),
    /过大/,
  );
  await assert.rejects(
    rerankDocuments(input, config, 'models.example.test', async () => new Response('not-json')),
  );
});
test('供应商失败和超时不自动重试；内部错误不泄露 Key 或原文', async () => {
  let calls = 0;
  await assert.rejects(
    rerankDocuments(input, config, 'models.example.test', async () => {
      calls++;
      return new Response('fake-rerank-key and 付款条款', { status: 429 });
    }),
    /请求失败/,
  );
  assert.equal(calls, 1);
  await assert.rejects(
    rerankDocuments(input, config, 'models.example.test', async () => {
      throw new DOMException('timeout', 'TimeoutError');
    }),
    /timeout/,
  );
  const controller = new KnowledgeRerankController();
  await assert.rejects(controller.rerank(undefined, { ...input, ...config }), /Unauthorized/);
  try {
    await controller.rerank(process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod', {
      ...input,
      ...config,
      baseUrl: 'https://not-approved.invalid/v2',
    });
    assert.fail('must reject');
  } catch (error) {
    assert.equal(error.getStatus(), 503);
    assert.ok(!JSON.stringify(error.getResponse()).includes(config.apiKey));
    assert.ok(!JSON.stringify(error.getResponse()).includes(input.documents[1]));
  }
});
