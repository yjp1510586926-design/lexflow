// Isolated integration harness: only the real retirement controller plus synthetic vector I/O.
require('reflect-metadata');
const { NestFactory } = require('@nestjs/core');
const { Module } = require('@nestjs/common');
const { MilvusClient, ConsistencyLevelEnum } = require('@zilliz/milvus2-sdk-node');
const { createInterface } = require('node:readline');
const {
  KnowledgeVectorRetirementController,
} = require('../../dist/rag/knowledge-vector-retirement.controller');
const { MilvusKnowledgeStore } = require('../../dist/rag/milvus-knowledge.store');
class RetirementTestModule {}
Module({ controllers: [KnowledgeVectorRetirementController] })(RetirementTestModule);
(async () => {
  const client = new MilvusClient({ address: process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530' }),
    store = new MilvusKnowledgeStore(client);
  const app = await NestFactory.create(RetirementTestModule, { logger: false });
  await app.listen(0, '127.0.0.1');
  process.stdout.write(
    'LEXFLOW_VECTOR ' + JSON.stringify({ port: app.getHttpServer().address().port }) + '\n',
  );
  const rl = createInterface({ input: process.stdin }),
    owned = new Set();
  try {
    for await (const line of rl) {
      const input = JSON.parse(line);
      let output;
      try {
        if (input.action === 'seed') {
          const expected = MilvusKnowledgeStore.collectionName(input.modelIdentity, 4);
          if (!owned.has(expected)) {
            if ((await client.hasCollection({ collection_name: expected })).value)
              throw new Error('refuse preexisting collection');
            owned.add(expected);
          }
          const name = await store.ensureCollection(input.modelIdentity, 4);
          await store.upsert(name, input.records);
          output = { seeded: true };
        } else if (input.action === 'query') {
          await client.loadCollectionSync({ collection_name: input.collectionName });
          const result = await client.query({
            collection_name: input.collectionName,
            filter: 'chunk_id != ""',
            output_fields: ['chunk_id'],
            consistency_level: ConsistencyLevelEnum.Strong,
          });
          if (result.status.code !== undefined && result.status.code !== 0)
            throw new Error('query failed');
          output = { ids: result.data.map((row) => row.chunk_id).sort() };
        } else if (input.action === 'drop') {
          if (!owned.has(input.collectionName)) throw new Error('refuse non-owned collection');
          const present = await client.hasCollection({ collection_name: input.collectionName });
          if (present.value) await client.dropCollection({ collection_name: input.collectionName });
          output = {
            absent: !(await client.hasCollection({ collection_name: input.collectionName })).value,
          };
        } else throw new Error('unknown command');
        process.stdout.write('LEXFLOW_VECTOR ' + JSON.stringify(output) + '\n');
      } catch (e) {
        process.stdout.write(
          'LEXFLOW_VECTOR ' + JSON.stringify({ failed: true, error: e.message }) + '\n',
        );
      }
    }
  } finally {
    for (const name of owned)
      if ((await client.hasCollection({ collection_name: name })).value)
        await client.dropCollection({ collection_name: name });
    await app.close();
    await client.closeConnection();
  }
})().catch(() => {
  process.stderr.write('isolated vector harness failed\n');
  process.exitCode = 1;
});
