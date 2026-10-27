const { test } = require('node:test');
const assert = require('node:assert/strict');
const { taskTransport } = require('../dist/transport/task-transport');
test('Java 事件与历史字符串均可路由到消费者，队列声明匹配死信参数', () => {
  const config = taskTransport('lexflow.q.document.parse');
  const event = { taskId: '123', attempt: 1 };
  for (const value of [event, JSON.stringify(event)]) {
    assert.deepEqual(config.deserializer.deserialize(value), {
      pattern: config.queue,
      data: event,
    });
  }
  assert.equal(config.queueOptions.arguments['x-dead-letter-routing-key'], config.queue + '.dlq');
  assert.equal(config.queueOptions.arguments['x-dead-letter-exchange'], 'lexflow.dlx');
});
