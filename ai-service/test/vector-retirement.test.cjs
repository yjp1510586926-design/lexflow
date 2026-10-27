const { test } = require('node:test');
const assert = require('node:assert/strict');
const { MilvusKnowledgeStore } = require('../dist/rag/milvus-knowledge.store');

function location() {
  return {
    tenantId: '11',
    scope: 'TENANT',
    caseId: null,
    knowledgeBaseId: '31',
    documentId: '41',
    fileVersionId: '51',
    indexVersion: 2,
    modelIdentity: 'retirement-unit',
    dimension: 4,
    collectionName: MilvusKnowledgeStore.collectionName('retirement-unit', 4),
  };
}

test('向量回收以完整结构化位置构造删除过滤；不能扩大到租户/collection', async () => {
  const calls = [];
  const store = new MilvusKnowledgeStore({
    hasCollection: async () => ({ value: true, status: { code: 0 } }),
    deleteEntities: async (request) => {
      calls.push(request);
      return { status: { code: 0, error_code: 'Success' } };
    },
  });
  await store.deleteRetired(location());
  assert.equal(
    calls[0].filter,
    'tenant_id == "11" && case_id == "0" && knowledge_base_id == "31" && document_id == "41" && file_version_id == "51" && index_version == 2',
  );
  assert.equal(calls[0].collection_name, location().collectionName);
  await store.deleteRetired({ ...location(), scope: 'CASE', caseId: '21' });
  assert.match(calls[1].filter, /case_id == "21"/);
  for (const changed of [
    { tenantId: '1" || true' },
    { knowledgeBaseId: undefined },
    { documentId: undefined },
    { fileVersionId: undefined },
    { indexVersion: 2147483648 },
    { indexVersion: 0 },
    { scope: undefined },
    { scope: 'TENANT', caseId: '21' },
    { scope: 'CASE', caseId: null },
    { collectionName: 'other' },
    { modelIdentity: 'other' },
    { dimension: 8 },
    { filter: 'true' },
    { documentIds: ['41', '42'] },
  ])
    await assert.rejects(store.deleteRetired({ ...location(), ...changed }));
  assert.equal(calls.length, 2);
});

test('真实执行每次向量upsert前先登记；登记不可用时停止写入并复用已持久模型批次', async () => {
  const { runPersistedKnowledgeIndex } = require('../dist/rag/knowledge-index-workflow.service');
  const task = {
    tenantId: '11',
    taskId: '91',
    type: 'KNOWLEDGE_INGEST',
    scope: 'TENANT',
    caseId: null,
    knowledgeBaseId: '31',
  };
  const input = {
    tenantId: '11',
    scope: 'TENANT',
    caseId: null,
    knowledgeBaseId: '31',
    documentId: '41',
    fileVersionId: '51',
    indexVersion: 2,
    provider: 'fixture',
    baseUrl: 'https://localhost/test',
    model: 'model',
    apiKey: 'never-paid',
    configurationRevision: 'revision',
    chunks: Array.from({ length: 60 }, (_, i) => ({ id: String(200 + i), text: 'synthetic-' + i })),
  };
  let paid = 0,
    writes = 0;
  const cache = new Map(),
    order = [];
  const saver = {
    withThreadLock: async (_, operation) => operation(),
    runOnce: async (_, key, hash, operation) => {
      if (!cache.has(key)) cache.set(key, await operation());
      return cache.get(key);
    },
  };
  const embeddings = {
    embedDocuments: async (texts) => {
      paid++;
      return texts.map(() => [1, 0, 0, 0]);
    },
  };
  const model = JSON.stringify([input.provider, input.baseUrl, input.model]),
    collection = MilvusKnowledgeStore.collectionName(model, 4);
  const store = {
    ensureCollection: async () => collection,
    upsert: async () => {
      order.push('write');
      writes++;
    },
  };
  await assert.rejects(
    runPersistedKnowledgeIndex(
      task,
      input,
      saver,
      embeddings,
      store,
      async () => {},
      async () => {
        order.push('register-failed');
        throw new Error('database unavailable');
      },
    ),
    /unavailable/,
  );
  assert.equal(writes, 0);
  assert.equal(paid, 1);
  const receipt = await runPersistedKnowledgeIndex(
    task,
    input,
    saver,
    embeddings,
    store,
    async () => {},
    async (c, d, m) => {
      assert.equal(c, collection);
      assert.equal(d, 4);
      assert.equal(m, model);
      order.push('register');
    },
  );
  assert.equal(receipt.indexedChunks, 60);
  assert.equal(paid, 2);
  assert.equal(writes, 2);
  assert.deepEqual(order, ['register-failed', 'register', 'write', 'register', 'write']);
});

test('不存在的collection幂等成功，探测错误和delete错误保留重试', async () => {
  let deletes = 0;
  const store = new MilvusKnowledgeStore({
    hasCollection: async () => ({ value: false, status: { code: 0 } }),
    deleteEntities: async () => {
      deletes++;
    },
  });
  await store.deleteRetired(location());
  assert.equal(deletes, 0);
  for (const response of [
    { value: false, status: { code: 7 } },
    { value: false, status: { error_code: 'PermissionDenied' } },
    { status: { code: 0 } },
  ])
    await assert.rejects(
      new MilvusKnowledgeStore({ hasCollection: async () => response }).deleteRetired(location()),
    );
  await assert.rejects(
    new MilvusKnowledgeStore({
      hasCollection: async () => ({ value: true }),
      deleteEntities: async () => ({ status: { code: 7, error_code: 'Failure' } }),
    }).deleteRetired(location()),
  );
});
