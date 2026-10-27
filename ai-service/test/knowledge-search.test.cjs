require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { KnowledgeSearchController } = require('../dist/rag/knowledge-search.controller');

const input = {
  tenantId: '7',
  caseId: '8',
  knowledgeBaseId: '9',
  documentIds: ['10'],
  indexVersion: 1,
  provider: 'test',
  baseUrl: 'https://models.example.test/v1',
  model: 'embed-v1',
  apiKey: 'fake-key',
  collectionName: 'wrong-model',
  dimension: 4,
  query: '付款条款',
  limit: 5,
};

test('内部检索缺少服务凭据时不得处理请求', async () => {
  const controller = new KnowledgeSearchController();
  await assert.rejects(controller.search(undefined, input), /Unauthorized/);
});

test('索引模型不匹配时不得调用 Embedding', async () => {
  const controller = new KnowledgeSearchController();
  const secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';
  await assert.rejects(controller.search(secret, input), /模型/);
  await assert.rejects(controller.search(secret, { ...input, query: 'a' }), /参数/);
});

test('检索范围只兼容正数案件或明确公共范围，不接受 null/0 作为默认公共库', async () => {
  const controller = new KnowledgeSearchController();
  const secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';
  // Valid scopes reach the next independent model-identity gate; neither invokes an embedding.
  await assert.rejects(controller.search(secret, { ...input, scope: 'CASE' }), /模型/);
  await assert.rejects(
    controller.search(secret, { ...input, scope: 'TENANT', caseId: null }),
    /模型/,
  );
  for (const invalid of [
    { caseId: null },
    { caseId: undefined },
    { caseId: '0' },
    { scope: 'CASE', caseId: null },
    { scope: 'TENANT', caseId: '8' },
    { scope: 'TENANT', caseId: '0' },
    { scope: 'TENANT', caseId: undefined },
    { scope: null, caseId: null },
    { scope: 'PUBLIC', caseId: null },
    { tenantId: '9223372036854775808' },
  ])
    await assert.rejects(controller.search(secret, { ...input, ...invalid }), /范围|案件/);
});

test('扩大召回上限为 50，超限和非整数在模型调用前被拒绝', async () => {
  const controller = new KnowledgeSearchController();
  const secret = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';
  await assert.rejects(controller.search(secret, { ...input, limit: 50 }), /模型/);
  for (const limit of [0, 51, 500, 1.5, '50'])
    await assert.rejects(controller.search(secret, { ...input, limit }), /参数/);
});
