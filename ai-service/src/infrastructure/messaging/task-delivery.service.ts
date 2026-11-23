import { Injectable } from '@nestjs/common';
import { connect, ConsumeMessage } from 'amqplib';
import { randomUUID } from 'node:crypto';
import { rabbitMqUrl } from '../../config/settings';
import { IndexStoreUnavailableError } from '../errors/index-store-unavailable.error';
import { transientInfrastructureFailure } from '../errors/workflow-infrastructure-error';
import { WorkflowBusyError } from '../persistence/mysql-checkpointer';

// 延时单位为毫秒：三级队列分别等待 10、60、300 秒，再将消息死信回原业务队列。
// 这里只安排消息何时重投，不能绕过工作流操作日志去重新发送不确定的付费模型请求。
export const DELIVERY_DELAYS = [10000, 60000, 300000] as const;
/** TRANSIENT 可延时重投；PERMANENT 不再自动尝试；UNCERTAIN 需保留供人工判断。 */
export type DeliveryFailure = 'TRANSIENT' | 'PERMANENT' | 'UNCERTAIN';
// 只允许已有业务队列参与恢复，防止由调用方输入创建任意队列或改变消息流向。
const RECOVERABLE_QUEUES = new Set([
  'lexflow.q.document.parse',
  'lexflow.q.knowledge.ingest',
  'lexflow.q.contract.review',
  'lexflow.q.document.generate',
  'lexflow.q.case.analysis',
]);

/**
 * 判断故障是否属于明确的瞬时基础设施问题。
 * 同线程执行锁忙、向量库暂不可用可稍后处理；其他错误委托统一基础设施分类器。
 * 返回 true 只代表消息可以延后重投，不代表其中的模型操作可以重新发送。
 *
 * @param error 消费者或工作流抛出的异常，不在此读取供应商原始正文。
 * @returns 是否允许投递层按延时策略继续尝试。
 */
export function transientDeliveryFailure(error: unknown): boolean {
  if (error instanceof WorkflowBusyError || error instanceof IndexStoreUnavailableError) {
    return true;
  }
  return transientInfrastructureFailure(error);
}

/**
 * 根据原队列、已有重试头和失败类别，纯计算下一次投递位置，不访问 RabbitMQ。
 * x-lexflow-delivery-retry 记录已安排的重试次数，初次失败从 0 开始。
 * 只有瞬时故障且次数未耗尽才进入 retry.N；永久失败、不确定结果和耗尽次数都进 DLQ。
 *
 * @param queue 原业务队列名，必须属于本服务允许恢复的队列集合。
 * @param headers 原消息头，保留已有字段并更新恢复次数与失败分类。
 * @param failure 调用方根据业务/工作流异常确定的失败类别。
 * @returns 恢复目的队列、是否延时重试、次数和发送新副本时使用的消息头。
 */
export function deliveryDestination(
  queue: string,
  headers: Record<string, unknown>,
  failure: DeliveryFailure,
) {
  if (!RECOVERABLE_QUEUES.has(queue)) {
    throw new Error('不支持的消费恢复队列');
  }
  const rawRetryCount = headers['x-lexflow-delivery-retry'] ?? 0;
  const valid =
    Number.isSafeInteger(rawRetryCount) &&
    Number(rawRetryCount) >= 0 &&
    Number(rawRetryCount) <= DELIVERY_DELAYS.length;
  // 头字段必须是真正的安全整数，字符串数字、负数或越界值都按次数耗尽处理。
  // 不能简单归零，否则坏消息可能反复进入第一条延时队列而形成无限循环。
  const count = valid ? Number(rawRetryCount) : DELIVERY_DELAYS.length;
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

/**
 * 消息投递恢复服务，负责“发布恢复副本并确认 → ACK 原消息”的顺序。
 * 失败时不立即 requeue 原消息，避免数据库或供应商故障期间形成高速循环。
 * 即使使用发布确认，也可能出现副本已经接收而原 ACK 丢失的窗口；业务仍须幂等，
 * 工作流仍须依赖检查点和操作日志复用结果，不能把此服务理解为恰好一次投递。
 */
@Injectable()
export class TaskDeliveryService {
  /**
   * 为原消息安排下一步去向，然后结束当前投递。
   * 恢复副本确认成功才 ACK；发布失败则 NACK 且不重新入原队列，由原队列死信配置接管。
   * 此方法不更改消息正文中的 attempt/stateVersion，业务轮次仍由 Java 管理。
   *
   * @param channel 当前消费者的消息通道，只需具备 ACK/NACK 能力。
   * @param message 当前未确认的 RabbitMQ 原消息。
   * @param queue 原业务队列，延时结束后消息将回到这里。
   * @param failure 已确定的失败分类，默认按瞬时故障安排恢复。
   * @returns 恢复副本已确认或原消息已交给死信处理。
   */
  async defer(
    channel: {
      ack: (message: ConsumeMessage) => void;
      nack: (message: ConsumeMessage, all: boolean, requeue: boolean) => void;
    },
    message: ConsumeMessage,
    queue: string,
    failure: DeliveryFailure = 'TRANSIENT',
  ): Promise<void> {
    const route = deliveryDestination(queue, message.properties?.headers ?? {}, failure);
    try {
      await this.publish(message, queue, route);
      channel.ack(message);
    } catch {
      // 不立即重新入队，避免故障期间形成热循环；未确认的副本不能作为 ACK 依据。
      channel.nack(message, false, false);
    }
  }

  /**
   * 打开独立连接与发布确认通道，将原正文发送到恢复目的队列。
   * confirm 检查 Broker 是否接受消息，mandatory return 检查消息是否未能路由，
   * 两项都通过才允许调用方确认原消息；这不代表下一个消费者已经完成业务处理。
   *
   * @param message 原消息，保留正文、内容类型、编码、messageId 等业务关联信息。
   * @param queue 原业务队列，作为延时队列 TTL 到期后的回投目的地。
   * @param route deliveryDestination 计算出的重试或死信位置。
   * @returns 发布已经确认；未确认、被退回或连接故障会抛错给 defer 处理。
   */
  async publish(
    message: ConsumeMessage,
    queue: string,
    route: ReturnType<typeof deliveryDestination>,
  ): Promise<void> {
    const connection = await connect(rabbitMqUrl(), { timeout: 10000 });
    try {
      const channel = await connection.createConfirmChannel();
      try {
        await channel.assertQueue(`${queue}.dlq`, { durable: true });
        if (route.retry) {
          // 消息停留至 TTL 到期后，经默认交换机按原队列名称回投，不在进程内等待。
          await channel.assertQueue(route.queue, {
            durable: true,
            arguments: {
              'x-message-ttl': DELIVERY_DELAYS[route.retryCount - 1],
              'x-dead-letter-exchange': '',
              'x-dead-letter-routing-key': queue,
            },
          });
        }
        // 每次发布使用独立 correlationId，只把本次消息的 return 事件计为路由失败。
        // confirm 不能单独证明路由成功，所以还必须检查 returned 标记。
        let returned = false;
        const correlationId = randomUUID();
        channel.on('return', (value) => {
          if (value.properties.correlationId === correlationId) {
            returned = true;
          }
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
          // 等待确认最多 10 秒；超时不能证明 Broker 未收到，因而不能把原消息当作已完成。
          await Promise.race([
            channel.waitForConfirms(),
            new Promise<never>((_resolve, reject) => {
              timeout = setTimeout(() => reject(new Error('恢复消息确认超时')), 10000);
            }),
          ]);
        } finally {
          // 成功或失败都取消本地计时器，避免已结束的发布仍保留定时任务。
          if (timeout) {
            clearTimeout(timeout);
          }
        }
        if (returned) {
          throw new Error('恢复消息未路由');
        }
      } finally {
        // 关闭资源失败不遮蔽原发布结果，连接层也在外层 finally 中回收。
        await channel.close().catch(() => undefined);
      }
    } finally {
      await connection.close().catch(() => undefined);
    }
  }
}
