const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { MilvusClient } = require('@zilliz/milvus2-sdk-node');
const { MilvusKnowledgeStore } = require('../dist/rag/milvus-knowledge.store');

test('模型或维度变化产生不同 Collection；非法范围被拒绝', () => {
  const name = MilvusKnowledgeStore.collectionName('provider|model-v1', 4);
  assert.notEqual(name, MilvusKnowledgeStore.collectionName('provider|model-v2', 4));
  assert.notEqual(name, MilvusKnowledgeStore.collectionName('provider|model-v1', 8));
  assert.throws(() => MilvusKnowledgeStore.collectionName('', 4));
  assert.throws(() => MilvusKnowledgeStore.collectionName('model', 1));
});

test('现有 v1 schema 只将明确 TENANT 映射保留 case_id=0，过滤同时限制租户、知识库、版本与授权文档', async () => {
  const writes = [],
    searches = [];
  const client = {
    async upsert(request) {
      writes.push(request);
      return { status: { code: 0, error_code: 'Success' } };
    },
    async search(request) {
      searches.push(request);
      return { status: { code: 0 }, results: [] };
    },
  };
  const store = new MilvusKnowledgeStore(client);
  const collection = MilvusKnowledgeStore.collectionName('provider|model-v1', 4);
  const row = {
    chunkId: '61',
    tenantId: '11',
    scope: 'TENANT',
    caseId: null,
    knowledgeBaseId: '31',
    documentId: '41',
    fileVersionId: '51',
    indexVersion: 2,
    embedding: [1, 0, 0, 0],
  };
  await store.upsert(collection, [row]);
  assert.equal(writes[0].data[0].case_id, '0');
  assert.equal(writes[0].data[0].tenant_id, '11');
  assert.ok(!Object.hasOwn(writes[0].data[0], 'scope'), '保持 v1 schema，不写动态 scope 列');
  const publicScope = {
    tenantId: '11',
    scope: 'TENANT',
    caseId: null,
    knowledgeBaseId: '31',
    documentIds: ['41'],
    indexVersion: 2,
  };
  await store.search(collection, publicScope, [1, 0, 0, 0]);
  assert.equal(
    searches[0].filter,
    'tenant_id == "11" && case_id == "0" && knowledge_base_id == "31" && index_version == 2 && document_id in ["41"]',
  );
  await store.search(collection, { ...publicScope, scope: 'CASE', caseId: '21' }, [1, 0, 0, 0]);
  assert.match(searches[1].filter, /case_id == "21"/);
  for (const invalid of [
    { scope: undefined, caseId: null },
    { scope: undefined, caseId: '0' },
    { scope: 'CASE', caseId: null },
    { scope: 'CASE', caseId: '0' },
    { scope: 'TENANT', caseId: undefined },
    { scope: 'TENANT', caseId: '21' },
    { scope: 'TENANT', caseId: '0' },
    { scope: 'PUBLIC', caseId: null },
  ]) {
    await assert.rejects(store.upsert(collection, [{ ...row, ...invalid }]));
    await assert.rejects(store.search(collection, { ...publicScope, ...invalid }, [1, 0, 0, 0]));
  }
  assert.equal(writes.length, 1);
  assert.equal(searches.length, 2);
});

test(
  '真实 Milvus 写入和带租户、CASE/TENANT、知识库、文档版本过滤的检索',
  { skip: process.env.LEXFLOW_VECTOR_INTEGRATION !== 'true' },
  async () => {
    const client = new MilvusClient({ address: process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530' });
    const store = new MilvusKnowledgeStore(client);
    const modelIdentity = `integration-${randomUUID()}`;
    const collectionName = MilvusKnowledgeStore.collectionName(modelIdentity, 4);
    try {
      assert.equal(await store.ensureCollection(modelIdentity, 4), collectionName);
      await store.upsert(collectionName, [
        {
          chunkId: '900000101',
          tenantId: '900000001',
          caseId: '900000011',
          knowledgeBaseId: '900000021',
          documentId: '900000031',
          fileVersionId: '900000041',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        {
          chunkId: '900000102',
          tenantId: '900000002',
          caseId: '900000012',
          knowledgeBaseId: '900000022',
          documentId: '900000032',
          fileVersionId: '900000042',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        // Deliberately reuse base/document identifiers so CASE/TENANT and tenant predicates must isolate hits.
        {
          chunkId: '900000106',
          tenantId: '900000001',
          scope: 'TENANT',
          caseId: null,
          knowledgeBaseId: '900000021',
          documentId: '900000031',
          fileVersionId: '900000041',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        {
          chunkId: '900000107',
          tenantId: '900000002',
          scope: 'TENANT',
          caseId: null,
          knowledgeBaseId: '900000021',
          documentId: '900000031',
          fileVersionId: '900000041',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        {
          chunkId: '900000108',
          tenantId: '900000001',
          scope: 'TENANT',
          caseId: null,
          knowledgeBaseId: '900000022',
          documentId: '900000031',
          fileVersionId: '900000041',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        {
          chunkId: '900000109',
          tenantId: '900000001',
          scope: 'TENANT',
          caseId: null,
          knowledgeBaseId: '900000021',
          documentId: '900000032',
          fileVersionId: '900000042',
          indexVersion: 1,
          embedding: [1, 0, 0, 0],
        },
        {
          chunkId: '900000110',
          tenantId: '900000001',
          scope: 'TENANT',
          caseId: null,
          knowledgeBaseId: '900000021',
          documentId: '900000031',
          fileVersionId: '900000041',
          indexVersion: 2,
          embedding: [0, 1, 0, 0],
        },
      ]);
      const scope = {
        tenantId: '900000001',
        caseId: '900000011',
        knowledgeBaseId: '900000021',
        documentIds: ['900000031'],
        indexVersion: 1,
      };
      const hits = await store.search(collectionName, scope, [1, 0, 0, 0]);
      assert.deepEqual(
        hits.map((hit) => hit.chunkId),
        ['900000101'],
      );
      const publicScope = { ...scope, scope: 'TENANT', caseId: null };
      assert.deepEqual(
        (await store.search(collectionName, publicScope, [1, 0, 0, 0])).map((h) => h.chunkId),
        ['900000106'],
      );
      assert.deepEqual(
        (
          await store.search(
            collectionName,
            { ...publicScope, tenantId: '900000002' },
            [1, 0, 0, 0],
          )
        ).map((h) => h.chunkId),
        ['900000107'],
      );
      assert.deepEqual(
        (await store.search(collectionName, { ...publicScope, indexVersion: 2 }, [0, 1, 0, 0])).map(
          (h) => h.chunkId,
        ),
        ['900000110'],
      );
      assert.deepEqual(
        (
          await store.search(
            collectionName,
            { ...publicScope, knowledgeBaseId: '900000022' },
            [1, 0, 0, 0],
          )
        ).map((h) => h.chunkId),
        ['900000108'],
      );
      assert.deepEqual(
        (
          await store.search(
            collectionName,
            { ...publicScope, documentIds: ['900000032'] },
            [1, 0, 0, 0],
          )
        ).map((h) => h.chunkId),
        ['900000109'],
      );
      // Same-model rebuilds use new primary keys; shadow writes must preserve published vectors.
      const rebuilt = {
        chunkId: '900000104',
        tenantId: '900000001',
        caseId: '900000011',
        knowledgeBaseId: '900000021',
        documentId: '900000031',
        fileVersionId: '900000041',
        indexVersion: 2,
        embedding: [0, 1, 0, 0],
      };
      await store.upsert(collectionName, [rebuilt]);
      assert.deepEqual(
        (await store.search(collectionName, scope, [1, 0, 0, 0])).map((h) => h.chunkId),
        ['900000101'],
      );
      assert.deepEqual(
        (await store.search(collectionName, { ...scope, indexVersion: 2 }, [0, 1, 0, 0])).map(
          (h) => h.chunkId,
        ),
        ['900000104'],
      );
      // Late old-worker writes remain scoped to their abandoned version.
      await store.upsert(collectionName, [{ ...rebuilt, embedding: [1, 0, 0, 0] }]);
      await store.upsert(collectionName, [
        { ...rebuilt, chunkId: '900000105', indexVersion: 3, embedding: [0, 0, 1, 0] },
      ]);
      assert.deepEqual(
        (await store.search(collectionName, scope, [1, 0, 0, 0])).map((h) => h.chunkId),
        ['900000101'],
      );
      assert.deepEqual(
        (await store.search(collectionName, { ...scope, indexVersion: 3 }, [0, 0, 1, 0])).map(
          (h) => h.chunkId,
        ),
        ['900000105'],
      );
      assert.deepEqual(
        await store.search(collectionName, { ...scope, documentIds: ['900000032'] }, [1, 0, 0, 0]),
        [],
      );
      assert.deepEqual(
        await store.search(collectionName, { ...scope, documentIds: [] }, [1, 0, 0, 0]),
        [],
      );
      // One broader retrieval still uses exactly the original scope and can return over ten candidates.
      await store.upsert(
        collectionName,
        Array.from({ length: 12 }, (_, i) => ({
          chunkId: String(900000200 + i),
          tenantId: '900000001',
          caseId: '900000011',
          knowledgeBaseId: '900000021',
          documentId: '900000033',
          fileVersionId: '900000043',
          indexVersion: 1,
          embedding: [1, i * 0.001, 0, 0],
        })),
      );
      const wider = { ...scope, documentIds: ['900000033'] };
      assert.equal((await store.search(collectionName, wider, [1, 0, 0, 0], 5)).length, 5);
      const expanded = await store.search(collectionName, wider, [1, 0, 0, 0], 50);
      assert.equal(expanded.length, 12);
      assert.ok(expanded.every((h) => h.documentId === '900000033'));
      assert.deepEqual(
        await store.search(collectionName, { ...wider, tenantId: '900000002' }, [1, 0, 0, 0], 50),
        [],
      );
      await assert.rejects(store.search(collectionName, wider, [1, 0, 0, 0], 51));
      await assert.rejects(
        store.search(collectionName, { ...scope, tenantId: '1" || true' }, [1, 0, 0, 0]),
      );
      await assert.rejects(
        store.upsert(collectionName, [
          {
            chunkId: '900000103',
            tenantId: '900000001',
            caseId: '900000011',
            knowledgeBaseId: '900000021',
            documentId: '900000031',
            fileVersionId: '900000041',
            indexVersion: 1,
            embedding: [1, 0],
          },
        ]),
      );
    } finally {
      const present = await client.hasCollection({ collection_name: collectionName });
      if (present.value) await client.dropCollection({ collection_name: collectionName });
      await client.closeConnection();
    }
  },
);
