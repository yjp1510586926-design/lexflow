require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Module } = require('@nestjs/common');
const { NestFactory } = require('@nestjs/core');
const { configureInternalJsonBody } = require('../dist/http-body');
const { KnowledgeRerankController } = require('../dist/rag/knowledge-rerank.controller');

test('50 段中文候选通过实际 HTTP 请求大小检查，超限 JSON 仍拒绝', async () => {
  class TestModule {}
  Module({ controllers: [KnowledgeRerankController] })(TestModule);
  const app = await NestFactory.create(TestModule, { bodyParser: false, logger: false });
  configureInternalJsonBody(app);
  try {
    await app.listen(0, '127.0.0.1');
    const url = (await app.getUrl()) + '/internal/v1/knowledge/rerank';
    const input = {
      query: '何时付款？',
      documents: Array(50).fill('中'.repeat(1000)),
      limit: 10,
      provider: 'test',
      baseUrl: 'https://not-approved.invalid/v2',
      model: 'test',
      apiKey: 'fake-key',
    };
    const request = JSON.stringify(input);
    assert.ok(Buffer.byteLength(request) > 100 * 1024);
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-internal-secret': process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
      },
      body: request,
    });
    // Configuration is deliberately unapproved: reaches the controller, without an external model call.
    assert.equal(response.status, 503);
    const result = await response.text();
    assert.ok(!result.includes('fake-key'));
    assert.ok(!result.includes('中中'));
    const oversized = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ padding: '中'.repeat(100000) }),
    });
    assert.equal(oversized.status, 413);
    await oversized.body?.cancel();
  } finally {
    await app.close();
  }
});
