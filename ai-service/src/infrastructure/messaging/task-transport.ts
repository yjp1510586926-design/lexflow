/**
 * 把 Java 的普通事件 JSON 适配为 NestJS 消息消费者需要的 pattern/data 结构。
 * Java 发布端无需依赖 NestJS 协议；队列名在适配后成为 @MessagePattern 的匹配键。
 * 同时为原业务队列声明死信规则，消费者拒绝且不重新入队的消息可进入相应 DLQ。
 *
 * @param queue 本次连接的业务队列全名，同时作为 NestJS 消息 pattern。
 * @returns 可展开到 RabbitMQ 微服务 options 中的队列声明与反序列化配置。
 */
export function taskTransport(queue: string) {
  return {
    queue,
    queueOptions: {
      durable: true,
      arguments: {
        // 消费者 nack(requeue=false) 时由 Broker 转入对应 DLQ，避免立即循环消费。
        'x-dead-letter-exchange': 'lexflow.dlx',
        'x-dead-letter-routing-key': `${queue}.dlq`,
      },
    },
    deserializer: {
      /**
       * 只处理传输层封装，不判断任务是否合法或是否仍有权限。
       * 字符串无法解析时保留原值，由各消费者按自身事件协议统一拒绝。
       */
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
        // NestJS 根据 pattern 选择 @MessagePattern；Java 无需使用 NestJS 消息封装。
        return { pattern: queue, data };
      },
    },
  };
}
