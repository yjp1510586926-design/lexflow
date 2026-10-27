import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../services/task-callback.service';
import { DocumentGenerationWorkflowService } from '../graphs/document-generation-workflow.service';
import {
  WorkflowBusyError,
  WorkflowOperationFailedError,
} from '../graphs/persistence/mysql-checkpointer';
import { GenerationInput, GeneratedDraft } from '../graphs/document-generation.graph';
import { TaskDeliveryService } from '../transport/task-delivery.service';
import { deliveryId, workflowDeliveryFailure } from '../transport/workflow-delivery';
import { ConsumeMessage } from 'amqplib';

interface Event {
  tenantId: string;
  taskId: string;
  resourceVersionId: string;
  attempt: number;
  stateVersion: number;
}
@Controller()
export class DocumentGenerationConsumer {
  private readonly logger = new Logger(DocumentGenerationConsumer.name);
  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: DocumentGenerationWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}
  @MessagePattern('lexflow.q.document.generate')
  async handle(@Payload() data: string | Event, @Ctx() ctx: RmqContext): Promise<void> {
    const channel = ctx.getChannelRef(),
      message = ctx.getMessage() as ConsumeMessage;
    let event: Event;
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
      )
        throw new Error('invalid event');
    } catch {
      channel.nack(message, false, false);
      return;
    }
    let claimed = false;
    try {
      const input = await this.callback.generationInput(event);
      // 输入必须仍指向消息携带的来源版本和轮次，认领仅允许递增一次状态版本。
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
      let active: GenerationInput | undefined,
        outcome: 'SUCCEEDED' | 'FAILED' = 'SUCCEEDED',
        draft: GeneratedDraft | undefined;
      try {
        draft = await this.workflow.run(input, (task) => {
          active = task;
          claimed = true;
        });
      } catch (error) {
        if (error instanceof WorkflowBusyError) {
          channel.ack(message);
          return;
        }
        if (!(error instanceof WorkflowOperationFailedError) || !active) throw error;
        outcome = 'FAILED';
      }
      if (!active) throw new Error('generation not claimed');
      // 先回传草稿再 ACK，回调丢失时通过操作日志恢复，避免再次付费生成。
      await this.callback.generationResult(active, outcome, draft);
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
