import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../services/task-callback.service';
import { ContractWorkflowService } from '../graphs/contract-workflow.service';
import {
  WorkflowBusyError,
  WorkflowOperationFailedError,
} from '../graphs/persistence/mysql-checkpointer';
import { TaskDeliveryService } from '../transport/task-delivery.service';
import { deliveryId, workflowDeliveryFailure } from '../transport/workflow-delivery';
import { ConsumeMessage } from 'amqplib';

interface ContractReviewPayload {
  eventId: string;
  schemaVersion: string;
  tenantId: string;
  taskId: string;
  resourceVersionId: string;
  attempt: number;
  stateVersion: number;
  traceId: string;
  occurredAt: string;
}

/**
 * 合同审查：Java 校验授权、持久化输入和决定；LangGraph 负责可恢复计算。
 */
@Controller()
export class ContractReviewConsumer {
  private readonly logger = new Logger(ContractReviewConsumer.name);

  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: ContractWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  @MessagePattern('lexflow.q.contract.review')
  async handle(
    @Payload() data: string | ContractReviewPayload,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const originalMsg = ctx.getMessage() as ConsumeMessage;

    let payload: ContractReviewPayload;
    try {
      payload = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !payload ||
        !deliveryId(payload.taskId) ||
        !deliveryId(payload.tenantId) ||
        !deliveryId(payload.resourceVersionId) ||
        !Number.isSafeInteger(payload.attempt) ||
        payload.attempt < 0 ||
        !Number.isSafeInteger(payload.stateVersion) ||
        payload.stateVersion < 1
      )
        throw new Error('invalid event');
    } catch {
      this.logger.error('Invalid contract review message, sending to DLQ');
      channel.nack(originalMsg, false, false);
      return;
    }

    const { taskId, attempt } = payload;
    this.logger.log(`[contract.review] taskId=${taskId} attempt=${attempt}`);

    let claimed = false;
    try {
      const ctxData = await this.callback.getContext(taskId);
      // 补材会排入新一轮任务；认领使 stateVersion 增加一次，重复投递也需接受该版本。
      const claimedVersion =
        ctxData.status === 'RUNNING' && ctxData.stateVersion === payload.stateVersion + 1;
      if (
        ctxData.taskId !== taskId ||
        ctxData.tenantId !== payload.tenantId ||
        ctxData.type !== 'CONTRACT_REVIEW' ||
        ctxData.resourceVersionId !== payload.resourceVersionId ||
        ctxData.attempt !== attempt ||
        !['QUEUED', 'RUNNING'].includes(ctxData.status) ||
        !(ctxData.stateVersion === payload.stateVersion || claimedVersion)
      ) {
        channel.ack(originalMsg);
        return;
      }

      let active = ctxData;
      let outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED';
      let report;
      try {
        const result = await this.workflow.run(ctxData, (task) => {
          active = task;
          claimed = true;
        });
        outcome = result.outcome;
        report = result.report;
      } catch (error) {
        // 同一线程已有执行者持锁时，当前重复投递退出，由持锁者完成结果回调。
        if (error instanceof WorkflowBusyError) {
          channel.ack(originalMsg);
          return;
        }
        if (!(error instanceof WorkflowOperationFailedError) || !claimed) throw error;
        outcome = 'FAILED';
      }
      // 使用认领后的版本回调；Java 接收结果后再确认消息，恢复投递可复用已保存的计算。
      await this.callback.contractResult(active, outcome, report);
      channel.ack(originalMsg);
      this.logger.log(`[contract.review] taskId=${taskId} → ${outcome}`);
    } catch (error) {
      this.logger.error(`Contract task ${taskId} delivery could not complete`);
      await this.delivery.defer(
        channel,
        originalMsg,
        'lexflow.q.contract.review',
        workflowDeliveryFailure(error, claimed),
      );
    }
  }
}
