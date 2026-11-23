import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../../infrastructure/java/task-callback.service';
import { ContractWorkflowService } from './contract-workflow.service';
import type { ContractReport } from './contract-review.state';
import {
  WorkflowBusyError,
  WorkflowOperationFailedError,
} from '../../infrastructure/persistence/mysql-checkpointer';
import { TaskDeliveryService } from '../../infrastructure/messaging/task-delivery.service';
import {
  deliveryId,
  workflowDeliveryFailure,
} from '../../infrastructure/messaging/workflow-delivery';
import { ConsumeMessage } from 'amqplib';

/**
 * Java 发布的合同审查事件，只负责定位任务，不携带合同正文或模型凭据。
 * 固定来源版本与执行轮次用于排除旧事件；实际授权、状态和输入仍以 Java 接口为准。
 */
interface ContractReviewPayload {
  eventId: string;
  schemaVersion: string;
  tenantId: string;
  taskId: string;
  /** 本轮审查绑定的不可变文件版本，不能替换成文件的最新版本。 */
  resourceVersionId: string;
  /** 业务轮次与发布时的状态版本；认领后须改用 Java 返回的新版本回调。 */
  attempt: number;
  stateVersion: number;
  traceId: string;
  occurredAt: string;
}

/**
 * 合同审查的消息入口，连接 Java 业务状态与 LangGraph 可恢复计算。
 * Java 校验权限、保存固定输入及人工复核决定，图只负责计算与人工中断。
 * 这里始终先提交结果再确认消息，避免“队列已确认，但 Java 不知道执行结果”。
 */
@Controller()
export class ContractReviewConsumer {
  private readonly logger = new Logger(ContractReviewConsumer.name);

  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: ContractWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  /**
   * 处理一条审查投递；等待人工复核属于正常工作流结果，也应回调并 ACK。
   * 认领回调会更新 claimedTask，确保结果不会继续使用消息发布时的旧状态版本。
   *
   * @param data Java 事件对象或兼容的 JSON 字符串。
   * @param ctx 提供原消息与手动确认通道。
   * @returns 该投递已确认或已交给延时/死信恢复流程，不在这里等待人工决定。
   */
  @MessagePattern('lexflow.q.contract.review')
  async handle(
    @Payload() data: string | ContractReviewPayload,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const message = ctx.getMessage() as ConsumeMessage;

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
      ) {
        throw new Error('invalid event');
      }
    } catch {
      // 编号或轮次格式错误不能通过重试修复，拒绝消息并由队列配置路由到 DLQ。
      this.logger.error('Invalid contract review message, sending to DLQ');
      channel.nack(message, false, false);
      return;
    }

    const { taskId, attempt } = payload;
    this.logger.log(`[contract.review] taskId=${taskId} attempt=${attempt}`);

    let claimed = false;
    try {
      const taskContext = await this.callback.getContext(taskId);
      // 补材可能已经排入新轮次；必须先检查当前任务，旧消息只 ACK，不覆盖新输入。
      // 同一轮重复投递还可能发生在 begin 认领之后，允许 RUNNING 的紧邻状态版本。
      const claimedVersion =
        taskContext.status === 'RUNNING' && taskContext.stateVersion === payload.stateVersion + 1;
      if (
        taskContext.taskId !== taskId ||
        taskContext.tenantId !== payload.tenantId ||
        taskContext.type !== 'CONTRACT_REVIEW' ||
        taskContext.resourceVersionId !== payload.resourceVersionId ||
        taskContext.attempt !== attempt ||
        !['QUEUED', 'RUNNING'].includes(taskContext.status) ||
        !(taskContext.stateVersion === payload.stateVersion || claimedVersion)
      ) {
        channel.ack(message);
        return;
      }

      let claimedTask = taskContext;
      let outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED';
      let report: ContractReport | undefined;
      try {
        // Java begin 接口认领成功后，工作流通过回调提供更新后的 TaskContext。
        const result = await this.workflow.run(taskContext, (task) => {
          claimedTask = task;
          claimed = true;
        });
        outcome = result.outcome;
        report = result.report;
      } catch (error) {
        // 同一线程已有执行者持锁时，当前重复投递退出，由持锁者完成结果回调。
        if (error instanceof WorkflowBusyError) {
          channel.ack(message);
          return;
        }
        // 明确的操作失败可提交 FAILED；认领前错误或无法判定的错误交给外层分类。
        if (!(error instanceof WorkflowOperationFailedError) || !claimed) {
          throw error;
        }
        outcome = 'FAILED';
      }
      // 结果回调单独位于计算异常处理之外：回调失败不等于模型计算失败。
      // Java 接受认领后的版本结果再 ACK，后续恢复可复用已经成功保存的模型操作。
      await this.callback.contractResult(claimedTask, outcome, report);
      channel.ack(message);
      this.logger.log(`[contract.review] taskId=${taskId} → ${outcome}`);
    } catch (error) {
      this.logger.error(`Contract task ${taskId} delivery could not complete`);
      await this.delivery.defer(
        channel,
        message,
        'lexflow.q.contract.review',
        workflowDeliveryFailure(error, claimed),
      );
    }
  }
}
