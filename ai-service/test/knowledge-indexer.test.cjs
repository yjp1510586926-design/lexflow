const { test } = require('node:test');
const assert = require('node:assert/strict');
const { configuredEmbeddings, indexKnowledgeDocument } = require('../dist/rag/knowledge-indexer');

const config = {
  provider: 'example',
  baseUrl: 'https://models.example.test/v1',
  model: 'embed-v1',
  apiKey: 'user-key',
};
const source = {
  tenantId: '11',
  caseId: '21',
  knowledgeBaseId: '31',
  documentId: '41',
  fileVersionId: '51',
  indexVersion: 1,
  chunks: [
    { id: '61', text: '付款期限' },
    { id: '62', text: '交付方式' },
  ],
};

test('未授权的模型域名和缺失 Key 不能发起 Embedding', () => {
  assert.throws(() => configuredEmbeddings(config, ''), /授权/);
  assert.throws(
    () => configuredEmbeddings({ ...config, apiKey: '' }, 'models.example.test'),
    /API Key/,
  );
  assert.throws(
    () =>
      configuredEmbeddings(
        { ...config, baseUrl: 'http://models.example.test/v1' },
        'models.example.test',
      ),
    /HTTPS/,
  );
  assert.ok(configuredEmbeddings(config, 'models.example.test'));
});

test('真实切片顺序与范围进入向量库；成功后才返回发布凭据', async () => {
  const operations = [];
  const embeddings = {
    async embedDocuments(texts) {
      operations.push(['embed', texts]);
      return texts.map((_, i) => [1, i + 1, 0, 0]);
    },
  };
  const store = {
    async ensureCollection(identity, dim) {
      operations.push(['collection', identity, dim]);
      return 'collection';
    },
    async upsert(name, rows) {
      operations.push(['upsert', name, rows]);
    },
  };
  const receipt = await indexKnowledgeDocument(source, config, embeddings, store);
  assert.equal(receipt.dimension, 4);
  assert.equal(receipt.indexedChunks, 2);
  assert.deepEqual(operations[0], ['embed', ['付款期限', '交付方式']]);
  assert.equal(operations[1][2], 4);
  assert.deepEqual(
    operations[2][2].map((row) => [row.chunkId, row.tenantId, row.documentId]),
    [
      ['61', '11', '41'],
      ['62', '11', '41'],
    ],
  );
});

test('向量数量或维度不一致时不写入；切片重复时不调用模型', async () => {
  let writes = 0;
  const store = {
    async ensureCollection() {
      return 'collection';
    },
    async upsert() {
      writes++;
    },
  };
  await assert.rejects(
    indexKnowledgeDocument(
      source,
      config,
      {
        async embedDocuments() {
          return [[1, 0, 0]];
        },
      },
      store,
    ),
    /数量/,
  );
  await assert.rejects(
    indexKnowledgeDocument(
      source,
      config,
      {
        async embedDocuments() {
          return [
            [1, 0, 0],
            [1, 0],
          ];
        },
      },
      store,
    ),
    /无效向量/,
  );
  await assert.rejects(
    indexKnowledgeDocument(
      { ...source, chunks: [source.chunks[0], source.chunks[0]] },
      config,
      {
        async embedDocuments() {
          throw new Error('不应调用模型');
        },
      },
      store,
    ),
    /编号/,
  );
  await assert.rejects(
    indexKnowledgeDocument(
      { ...source, tenantId: '1" || true' },
      config,
      {
        async embedDocuments() {
          throw new Error('不应调用模型');
        },
      },
      store,
    ),
    /范围/,
  );
  assert.equal(writes, 0);
});

test('公共知识必须显式 TENANT 且 caseId 为 null；索引记录保留公共范围', async () => {
  let calls = 0;
  const rows = [];
  const embeddings = {
    async embedDocuments(texts) {
      calls++;
      return texts.map(() => [1, 0, 0, 0]);
    },
  };
  const store = {
    async ensureCollection() {
      return 'collection';
    },
    async upsert(_, batch) {
      rows.push(...batch);
    },
  };
  await indexKnowledgeDocument(
    { ...source, scope: 'TENANT', caseId: null },
    config,
    embeddings,
    store,
  );
  assert.equal(calls, 1);
  assert.ok(
    rows.every((row) => row.scope === 'TENANT' && row.caseId === null && row.tenantId === '11'),
  );
  for (const invalid of [
    { caseId: null },
    { caseId: undefined },
    { caseId: '0' },
    { scope: 'CASE', caseId: null },
    { scope: 'CASE', caseId: '0' },
    { scope: 'TENANT', caseId: '21' },
    { scope: 'TENANT', caseId: '0' },
    { scope: 'TENANT', caseId: undefined },
    { scope: 'PUBLIC', caseId: null },
    { tenantId: '9223372036854775808' },
  ])
    await assert.rejects(
      indexKnowledgeDocument({ ...source, ...invalid }, config, embeddings, store),
    );
  assert.equal(calls, 1, '所有不明确或伪造范围必须在调用模型前拒绝');
});
