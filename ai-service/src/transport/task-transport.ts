/** Java 发布普通事件 JSON，适配为 NestJS 所需的 pattern/data 消息。 */
export function taskTransport(queue: string) {
  return {
    queue,
    queueOptions: {
      durable: true,
      arguments: {
        'x-dead-letter-exchange': 'lexflow.dlx',
        'x-dead-letter-routing-key': `${queue}.dlq`,
      },
    },
    deserializer: {
      deserialize(value: unknown) {
        // 兼容先前 Java JSON converter 对字符串进行二次编码的消息。
        let data = value;
        if (typeof data === 'string') {
          try {
            data = JSON.parse(data);
          } catch {
            /* 交由消费者拒绝无效消息 */
          }
        }
        return { pattern: queue, data };
      },
    },
  };
}
