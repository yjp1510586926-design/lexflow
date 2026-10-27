require('reflect-metadata');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID, createHash } = require('node:crypto');
const amqp = require('amqplib');
const {
  TaskDeliveryService,
  deliveryDestination,
} = require('../dist/transport/task-delivery.service');
const { DocumentParseConsumer } = require('../dist/consumers/document-parse.consumer');
const enabled = process.env.LEXFLOW_DELIVERY_INTEGRATION === 'true';
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
test(
  '真实隔离 RabbitMQ：解析上下文故障经 10 秒 TTL 返回，确认后成功；其余 TTL 与三次死信上限生效',
  { skip: !enabled },
  async () => {
    const vhost = 'lexflow-delivery-test-' + randomUUID(),
      credentials = Buffer.from('lexflow:lexflow_mq_dev_only').toString('base64');
    const management = `http://127.0.0.1:15673/api/vhosts/${encodeURIComponent(vhost)}`;
    const call = (url, method = 'GET', body) =>
      fetch(url, {
        method,
        headers: { Authorization: `Basic ${credentials}`, 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    assert.equal((await call(management)).status, 404);
    let created = false,
      connection;
    const prior = process.env.RABBITMQ_URL;
    try {
      assert.equal((await call(management, 'PUT', {})).status, 201);
      created = true;
      assert.ok(
        [201, 204].includes(
          (
            await call(
              `http://127.0.0.1:15673/api/permissions/${encodeURIComponent(vhost)}/lexflow`,
              'PUT',
              { configure: '.*', write: '.*', read: '.*' },
            )
          ).status,
        ),
      );
      const url = `amqp://lexflow:lexflow_mq_dev_only@127.0.0.1:15672/${encodeURIComponent(vhost)}`;
      process.env.RABBITMQ_URL = url;
      connection = await amqp.connect(url);
      const channel = await connection.createConfirmChannel();
      await channel.assertExchange('lexflow.dlx', 'topic', { durable: true });
      const queue = 'lexflow.q.document.parse';
      await channel.assertQueue(queue, {
        durable: true,
        arguments: {
          'x-dead-letter-exchange': 'lexflow.dlx',
          'x-dead-letter-routing-key': queue + '.dlq',
        },
      });
      await channel.assertQueue(queue + '.dlq', { durable: true });
      await channel.bindQueue(queue + '.dlq', 'lexflow.dlx', queue + '.dlq');
      const event = { tenantId: '910000205', taskId: '123', attempt: 0 },
        body = Buffer.from(JSON.stringify(event)),
        bytes = Buffer.from('合成解析材料\n\n付款期限十日，仅供测试');
      channel.sendToQueue(queue, body, { persistent: true, headers: { trace: 'synthetic-test' } });
      await channel.waitForConfirms();
      const original = await channel.get(queue, { noAck: false });
      assert.ok(original);
      let contexts = 0;
      const results = [];
      const callback = {
        getContext: async () => {
          if (++contexts === 1) throw { response: { status: 503 } };
          return {
            ...event,
            status: 'QUEUED',
            stateVersion: 1,
            type: 'DOCUMENT_PARSE',
            mimeType: 'text/plain',
            sha256: createHash('sha256').update(bytes).digest('hex'),
          };
        },
        downloadSource: async () => bytes,
        submitResult: async (result) => results.push(result),
      };
      const consumer = new DocumentParseConsumer(callback, new TaskDeliveryService());
      await consumer.handle(event, { getChannelRef: () => channel, getMessage: () => original });
      assert.equal((await channel.checkQueue(queue + '.retry.1')).messageCount, 1);
      assert.equal((await channel.checkQueue(queue)).messageCount, 0);
      const started = Date.now();
      let retry;
      while (Date.now() - started < 20000) {
        retry = await channel.get(queue, { noAck: false });
        if (retry) break;
        await delay(200);
      }
      assert.ok(retry, 'TTL did not return the message');
      assert.ok(Date.now() - started >= 9000);
      assert.deepEqual(retry.content, body);
      assert.equal(retry.properties.headers['x-lexflow-delivery-retry'], 1);
      assert.equal(retry.properties.deliveryMode, 2);
      await consumer.handle(JSON.parse(retry.content), {
        getChannelRef: () => channel,
        getMessage: () => retry,
      });
      assert.equal(results.length, 1);
      assert.equal(results[0].success, true);
      assert.equal(results[0].parsedText, bytes.toString('utf8'));
      const delivery = new TaskDeliveryService();
      for (let count = 1; count <= 2; count++) {
        const route = deliveryDestination(
          queue,
          { 'x-lexflow-delivery-retry': count },
          'TRANSIENT',
        );
        await delivery.publish(original, queue, route);
        const response = await call(
          `http://127.0.0.1:15673/api/queues/${encodeURIComponent(vhost)}/${encodeURIComponent(route.queue)}`,
        );
        assert.equal(response.status, 200);
        const state = await response.json();
        assert.equal(state.arguments['x-message-ttl'], count === 1 ? 60000 : 300000);
        assert.equal(state.arguments['x-dead-letter-routing-key'], queue);
      }
      const exhausted = deliveryDestination(queue, { 'x-lexflow-delivery-retry': 3 }, 'TRANSIENT');
      await delivery.publish(original, queue, exhausted);
      const dead = await channel.get(queue + '.dlq', { noAck: false });
      assert.ok(dead);
      assert.equal(dead.properties.headers['x-lexflow-delivery-retry'], 3);
      assert.equal(dead.properties.headers['x-lexflow-failure-kind'], 'TRANSIENT');
      assert.deepEqual(dead.content, body);
      channel.ack(dead);
    } finally {
      if (prior === undefined) delete process.env.RABBITMQ_URL;
      else process.env.RABBITMQ_URL = prior;
      await connection?.close().catch(() => {});
      if (created) {
        assert.equal((await call(management, 'DELETE')).status, 204);
        assert.equal((await call(management)).status, 404);
      }
    }
  },
);
