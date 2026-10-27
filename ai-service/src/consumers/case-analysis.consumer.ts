import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../services/task-callback.service';
import { CaseAnalysisWorkflowService } from '../graphs/case-analysis-workflow.service';
import {
  WorkflowBusyError,
  WorkflowOperationFailedError,
} from '../graphs/persistence/mysql-checkpointer';
import { TaskDeliveryService } from '../transport/task-delivery.service';
import { deliveryId, workflowDeliveryFailure } from '../transport/workflow-delivery';
import { ConsumeMessage } from 'amqplib';

interface PayloadBody {
  eventId: string;
  schemaVersion: string;
  tenantId: string;
  taskId: string;
  caseId: string;
  resourceVersionId: string;
  type: string;
  attempt: number;
  stateVersion: number;
}
@Controller()
export class CaseAnalysisConsumer {
  private readonly logger = new Logger(CaseAnalysisConsumer.name);
  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: CaseAnalysisWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}
  @MessagePattern('lexflow.q.case.analysis')
  async handle(@Payload() data: string | PayloadBody, @Ctx() ctx: RmqContext): Promise<void> {
    const channel = ctx.getChannelRef(),
      message = ctx.getMessage() as ConsumeMessage;
    let payload: PayloadBody;
    try {
      payload = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !payload ||
        payload.schemaVersion !== '1.0' ||
        payload.type !== 'CASE_ANALYSIS' ||
        [payload.tenantId, payload.taskId, payload.caseId, payload.resourceVersionId].some(
          (v) => !deliveryId(v),
        ) ||
        !Number.isSafeInteger(payload.attempt) ||
        payload.attempt < 0 ||
        !Number.isSafeInteger(payload.stateVersion) ||
        payload.stateVersion < 1
      )
        throw new Error('invalid event');
    } catch {
      channel.nack(message, false, false);
      return;
    }
    let claimed = false;
    try {
      const task = await this.callback.getContext(payload.taskId);
      // 只处理当前轮次，兼容认领造成的一次版本递增；过期消息不再触发模型。
      if (
        task.taskId !== payload.taskId ||
        task.tenantId !== payload.tenantId ||
        task.caseId !== payload.caseId ||
        task.resourceVersionId !== payload.resourceVersionId ||
        task.type !== 'CASE_ANALYSIS' ||
        task.attempt !== payload.attempt ||
        !['QUEUED', 'RUNNING'].includes(task.status) ||
        !(
          task.stateVersion === payload.stateVersion ||
          (task.stateVersion === payload.stateVersion + 1 && task.status === 'RUNNING')
        )
      ) {
        channel.ack(message);
        return;
      }
      let active = task;
      let outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED', report;
      try {
        const result = await this.workflow.run(task, (t) => {
          active = t;
          claimed = true;
        });
        outcome = result.outcome;
        report = result.report;
      } catch (error) {
        if (error instanceof WorkflowBusyError) {
          channel.ack(message);
          return;
        }
        if (!(error instanceof WorkflowOperationFailedError) || !claimed) throw error;
        outcome = 'FAILED';
      }
      // 回调成功是 ACK 的前提；结果日志与快照可在后续投递中复用。
      await this.callback.analysisResult(active, outcome, report);
      channel.ack(message);
    } catch (error) {
      this.logger.error(`Case analysis ${payload.taskId} delivery could not complete`);
      await this.delivery.defer(
        channel,
        message,
        'lexflow.q.case.analysis',
        workflowDeliveryFailure(error, claimed),
      );
    }
  }
}
