import { Injectable } from '@nestjs/common';
import { connect, ConsumeMessage } from 'amqplib';
import { randomUUID } from 'node:crypto';
import { WorkflowBusyError } from '../graphs/persistence/mysql-checkpointer';
import { IndexStoreUnavailableError } from '../rag/knowledge-index-workflow.service';
import { transientInfrastructureFailure } from '../graphs/workflow-infrastructure-error';

// 仅瞬时故障进入三级延时队列；永久故障、结果不确定或次数耗尽统一进入 DLQ。
export const DELIVERY_DELAYS = [10000, 60000, 300000] as const;
export type DeliveryFailure = 'TRANSIENT' | 'PERMANENT' | 'UNCERTAIN';
const queues = new Set([
  'lexflow.q.document.parse',
  'lexflow.q.knowledge.ingest',
  'lexflow.q.contract.review',
  'lexflow.q.document.generate',
  'lexflow.q.case.analysis',
]);

export function transientDeliveryFailure(error: unknown): boolean {
  if (error instanceof WorkflowBusyError || error instanceof IndexStoreUnavailableError)
    return true;
  return transientInfrastructureFailure(error);
}

export function deliveryDestination(
  queue: string,
  headers: Record<string, unknown>,
  failure: DeliveryFailure,
) {
  if (!queues.has(queue)) throw new Error('不支持的消费恢复队列');
  const raw = headers['x-lexflow-delivery-retry'] ?? 0;
  const valid =
    Number.isSafeInteger(raw) && Number(raw) >= 0 && Number(raw) <= DELIVERY_DELAYS.length;
  // 非法重试头按次数耗尽处理，防止坏消息通过重置计数无限循环。
  const count = valid ? Number(raw) : DELIVERY_DELAYS.length;
  const retry = failure === 'TRANSIENT' && count < DELIVERY_DELAYS.length;
  return {
    queue: retry ? `${queue}.retry.${count + 1}` : `${queue}.dlq`,
    retry,
    retryCount: retry ? count + 1 : count,
    headers: {
      ...headers,
      'x-lexflow-delivery-retry': retry ? count + 1 : count,
      'x-lexflow-failure-kind': failure,
    },
  };
}

@Injectable()
export class TaskDeliveryService {
  /** 恢复副本获得发布确认后才 ACK；发布失败时由原队列的死信配置保留原消息。 */
  async defer(
    channel: {
      ack: (message: ConsumeMessage) => void;
      nack: (message: ConsumeMessage, all: boolean, requeue: boolean) => void;
    },
    message: ConsumeMessage,
    queue: string,
    failure: DeliveryFailure = 'TRANSIENT',
  ) {
    const route = deliveryDestination(queue, message.properties?.headers ?? {}, failure);
    try {
      await this.publish(message, queue, route);
      channel.ack(message);
    } catch {
      // 不立即重新入队，避免故障期间形成热循环；未确认的副本不能作为 ACK 依据。
      channel.nack(message, false, false);
    }
  }

  async publish(
    message: ConsumeMessage,
    queue: string,
    route: ReturnType<typeof deliveryDestination>,
  ) {
    const connection = await connect(
      process.env.RABBITMQ_URL ?? 'amqp://lexflow:lexflow_mq_dev_only@127.0.0.1:15672',
      { timeout: 10000 },
    );
    try {
      const channel = await connection.createConfirmChannel();
      try {
        await channel.assertQueue(`${queue}.dlq`, { durable: true });
        if (route.retry)
          await channel.assertQueue(route.queue, {
            durable: true,
            arguments: {
              'x-message-ttl': DELIVERY_DELAYS[route.retryCount - 1],
              'x-dead-letter-exchange': '',
              'x-dead-letter-routing-key': queue,
            },
          });
        // confirm 证明 Broker 已接收，mandatory 的 return 另行检测消息是否真正路由到队列。
        let returned = false;
        const correlationId = randomUUID();
        channel.on('return', (value) => {
          if (value.properties.correlationId === correlationId) returned = true;
        });
        channel.sendToQueue(route.queue, message.content, {
          persistent: true,
          mandatory: true,
          contentType: message.properties.contentType,
          contentEncoding: message.properties.contentEncoding,
          messageId: message.properties.messageId,
          correlationId,
          headers: route.headers,
        });
        let timeout: ReturnType<typeof setTimeout> | undefined;
        try {
          await Promise.race([
            channel.waitForConfirms(),
            new Promise<never>((_resolve, reject) => {
              timeout = setTimeout(() => reject(new Error('恢复消息确认超时')), 10000);
            }),
          ]);
        } finally {
          if (timeout) clearTimeout(timeout);
        }
        if (returned) throw new Error('恢复消息未路由');
      } finally {
        await channel.close().catch(() => undefined);
      }
    } finally {
      await connection.close().catch(() => undefined);
    }
  }
}
