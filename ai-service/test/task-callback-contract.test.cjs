require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { HttpService } = require('@nestjs/axios');
const { ConfigService } = require('@nestjs/config');
const { TaskCallbackService } = require('../dist/services/task-callback.service');

const secret = 'synthetic-contract-callback-secret';
async function withResponse(body, run) {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    let requestBody = '';
    for await (const chunk of req) requestBody += chunk;
    requests.push({
      method: req.method,
      url: req.url,
      secret: req.headers['x-internal-secret'],
      body: requestBody,
    });
    res.setHeader('Content-Type', 'application/json');
    res.end(body);
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const callback = new TaskCallbackService(
    new HttpService(),
    new ConfigService({
      JAVA_CORE_URL: `http://127.0.0.1:${server.address().port}`,
      INTERNAL_SECRET: secret,
    }),
  );
  try {
    await run(callback, requests);
  } finally {
    await new Promise((resolve) => {
      server.close(resolve);
      server.closeAllConnections();
    });
  }
}

function validInput() {
  const text = '验'.repeat(35000),
    sourceText = '验收后付款。',
    sha256 = 'f'.repeat(64);
  return {
    tenantId: '7',
    taskId: '123',
    fileVersionId: '456',
    sha256,
    mimeType: 'text/plain',
    attempt: 0,
    stateVersion: 5,
    stance: 'NEUTRAL',
    focus: '',
    knowledgeBaseId: null,
    provider: 'test',
    baseUrl: 'https://models.example.test/v1',
    model: 'test',
    apiKey: 'synthetic-only',
    decisionId: null,
    round: 1,
    instructions: '已补验收说明',
    supplements: [
      {
        parseTaskId: '201',
        fileVersionId: '202',
        sha256: 'a'.repeat(64),
        fileName: '验收说明.txt',
        text,
        segments: [{ kind: 'PARAGRAPH', index: 1, startOffset: 0, endOffset: text.length }],
      },
    ],
    previousReport: {
      sourceSha256: sha256,
      sourceText,
      contractType: '合同',
      clauses: [
        { id: '1', title: '付款', quote: sourceText, startOffset: 0, endOffset: sourceText.length },
      ],
      risks: [],
      missingMaterials: ['需验收说明'],
      evidence: [],
      needsHuman: true,
      basisConflicts: [],
    },
  };
}

test('真实本地 HTTP 合同 begin/input 可读取超过100KB的合法中文补材，保留请求身份及版本', async () => {
  const input = validInput(),
    body = JSON.stringify(input);
  assert.ok(Buffer.byteLength(body) > 100000);
  assert.ok(Buffer.byteLength(body) < 8 * 1024 * 1024);
  await withResponse(body, async (callback, requests) => {
    const begun = await callback.contractInput(
      { taskId: '123', attempt: 0, stateVersion: 4 },
      true,
    );
    const resumed = await callback.contractInput({ taskId: '123', attempt: 0, stateVersion: 5 });
    assert.deepEqual(begun, input);
    assert.deepEqual(resumed, input);
    assert.deepEqual(requests, [
      {
        method: 'POST',
        url: '/internal/v1/contract-reviews/123/begin',
        secret,
        body: JSON.stringify({ attempt: 0, stateVersion: 4 }),
      },
      {
        method: 'GET',
        url: '/internal/v1/contract-reviews/123/input?attempt=0&stateVersion=5',
        secret,
        body: '',
      },
    ]);
  });
});

test('真实本地 HTTP 合同 begin/input 拒绝超过8MiB的响应', async () => {
  const body = JSON.stringify({ padding: 'x'.repeat(8 * 1024 * 1024) });
  assert.ok(Buffer.byteLength(body) > 8 * 1024 * 1024);
  await withResponse(body, async (callback, requests) => {
    for (const begin of [true, false]) {
      await assert.rejects(
        callback.contractInput({ taskId: '123', attempt: 0, stateVersion: begin ? 4 : 5 }, begin),
        (error) =>
          error.code === 'ERR_BAD_RESPONSE' &&
          /maxContentLength size of 8388608 exceeded/.test(error.message),
      );
    }
    assert.equal(requests.length, 2);
    assert.ok(requests.every((request) => request.secret === secret));
  });
});
