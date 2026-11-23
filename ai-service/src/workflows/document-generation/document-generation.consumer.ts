import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../../infrastructure/java/task-callback.service';
import { DocumentGenerationWorkflowService } from './document-generation-workflow.service';
import {
  WorkflowBusyError,
  WorkflowOperationFailedError,
} from '../../infrastructure/persistence/mysql-checkpointer';
import { GenerationInput, GeneratedDraft } from './document-generation.state';
import { TaskDeliveryService } from '../../infrastructure/messaging/task-delivery.service';
import {
  deliveryId,
  workflowDeliveryFailure,
} from '../../infrastructure/messaging/workflow-delivery';
import { ConsumeMessage } from 'amqplib';

/**
 * Java 发布的文书生成事件，绑定不可变源版本和当前执行轮次。
 * 消息不携带生成正文或 API Key，消费者必须通过 Java 专用输入接口重新取得授权数据。
 */
interface DocumentGenerationEvent {
  tenantId: string;
  taskId: string;
  /** 对应 GenerationInput.sourceVersionId，而不是可被人工继续编辑的当前正文。 */
  resourceVersionId: string;
  /** 消息重投沿用原轮次；状态版本可在 Java 认领后增加一次。 */
  attempt: number;
  stateVersion: number;
}

/**
 * 文书生成的消息入口：读取输入、认领计算、发布草稿，再确认消息。
 * LangGraph 保存生成过程与模型操作结果，Java 保存独立待复核草稿及业务状态。
 * 回调失败不应改报模型失败，重复投递需优先复用已保存的生成结果。
 */
@Controller()
export class DocumentGenerationConsumer {
  private readonly logger = new Logger(DocumentGenerationConsumer.name);
  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: DocumentGenerationWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  /**
   * 消费一条文书生成消息，不在此处直接替换用户正在编辑的正文。
   * 必须收到工作流的认领通知后才能回调结果，以保证传回的是当前任务版本。
   *
   * @param data 文书生成事件对象，兼容 JSON 字符串。
   * @param ctx RabbitMQ 原始投递上下文，用于手动确认消息。
   * @returns 投递处理结束，草稿是否发布以 Java 接口接受的结果为准。
   */
  @MessagePattern('lexflow.q.document.generate')
  async handle(
    @Payload() data: string | DocumentGenerationEvent,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const message = ctx.getMessage() as ConsumeMessage;
    let event: DocumentGenerationEvent;
    try {
      event = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !event ||
        !deliveryId(event.tenantId) ||
        !deliveryId(event.taskId) ||
        !deliveryId(event.resourceVersionId) ||
        !Number.isSafeInteger(event.attempt) ||
        event.attempt < 0 ||
        !Number.isSafeInteger(event.stateVersion) ||
        event.stateVersion < 1
      ) {
        throw new Error('invalid event');
      }
    } catch {
      // 无效事件不能安全关联来源，直接拒绝并交给死信路由，而不是重复请求模型。
      channel.nack(message, false, false);
      return;
    }
    let claimed = false;
    try {
      const input = await this.callback.generationInput(event);
      // 这里先调用只读 input 接口，Java 负责来源授权；消费者再比较消息与输入身份。
      // 来源、轮次或版本不匹配表示该投递已失效，ACK 后结束，不认领也不生成正文。
      if (
        input.tenantId !== event.tenantId ||
        input.taskId !== event.taskId ||
        input.sourceVersionId !== event.resourceVersionId ||
        input.attempt !== event.attempt ||
        !(
          input.stateVersion === event.stateVersion || input.stateVersion === event.stateVersion + 1
        )
      ) {
        channel.ack(message);
        return;
      }
      let claimedInput: GenerationInput | undefined;
      let outcome: 'SUCCEEDED' | 'FAILED' = 'SUCCEEDED';
      let draft: GeneratedDraft | undefined;
      try {
        // run 内部通过 begin 认领，并将更新后的输入通知本消费者用于最终回调。
        draft = await this.workflow.run(input, (task) => {
          claimedInput = task;
          claimed = true;
        });
      } catch (error) {
        // 同一任务正在由另一执行者持锁处理，当前重复消息不竞争执行或再次生成。
        if (error instanceof WorkflowBusyError) {
          channel.ack(message);
          return;
        }
        // 只有已经认领且操作日志明确失败时，才能提交该轮生成失败的业务结果。
        if (!(error instanceof WorkflowOperationFailedError) || !claimedInput) {
          throw error;
        }
        outcome = 'FAILED';
      }
      if (!claimedInput) {
        // 这是编排约束：没有 Java 认领返回的版本，就不能安全发布生成结果。
        throw new Error('generation not claimed');
      }
      // 先让 Java 接受草稿或明确失败，再 ACK 原消息；HTTP 回调失败进入外层恢复。
      // 即使模型结果已经写入操作日志，也不能在 Java 尚未接收时直接确认这条投递。
      await this.callback.generationResult(claimedInput, outcome, draft);
      channel.ack(message);
    } catch (error) {
      this.logger.error(`Generation task ${event.taskId} delivery could not complete`);
      await this.delivery.defer(
        channel,
        message,
        'lexflow.q.document.generate',
        workflowDeliveryFailure(error, claimed),
      );
    }
  }
}
