import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../../infrastructure/java/task-callback.service';
import { CaseAnalysisWorkflowService } from './case-analysis-workflow.service';
import type { CaseAnalysisReport } from './case-analysis.state';
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
 * Java 经 RabbitMQ 发布的案件分析事件，不包含原文或模型 API Key。
 * 事件描述的是发布时的任务快照，收到消息时任务可能已经完成、取消或进入新轮次，
 * 因此消费者必须再次读取 Java 当前上下文，不能仅凭事件内容开始模型分析。
 */
interface CaseAnalysisPayload {
  /** 事件身份与协议版本；本消费者要求 schemaVersion 为 1.0。 */
  eventId: string;
  schemaVersion: string;
  /** 字符串形式的 Java long 编号，避免转换为 JavaScript number 后丢失精度。 */
  tenantId: string;
  taskId: string;
  caseId: string;
  /** 绑定提交分析时的固定来源版本，防止用新文件替换旧任务材料。 */
  resourceVersionId: string;
  type: string;
  /** 业务执行轮次与消息发布时的状态版本；消息延时重投不会更新这两个字段。 */
  attempt: number;
  stateVersion: number;
}

/**
 * 案件分析的消息入口，只编排业务执行，不在消费者中直接调用模型。
 * Java 负责材料授权、任务状态及结果持久化；工作流服务负责认领和可恢复计算；
 * 本类负责在三者之间传递当前任务版本，并决定原消息何时可以确认。
 * ACK 只代表本次投递无需再处理，不代表分析一定成功，等待人工复核也是有效结果。
 */
@Controller()
export class CaseAnalysisConsumer {
  private readonly logger = new Logger(CaseAnalysisConsumer.name);
  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: CaseAnalysisWorkflowService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  /**
   * 依次校验消息、过滤旧投递、运行工作流、提交结果，最后确认消息。
   * 无法完成回调时不把计算结果改报为失败，而是交给投递层分类处理；
   * 后续合法恢复可复用检查点及操作日志，但不会承诺远程模型恰好执行一次。
   *
   * @param data 传输适配器提供的事件对象，兼容尚未解码的 JSON 字符串。
   * @param ctx RabbitMQ 原始消息及消费通道，用于手动 ACK/NACK。
   * @returns 本次投递处理结束；业务结论由 Java 保存的任务与报告表达。
   */
  @MessagePattern('lexflow.q.case.analysis')
  async handle(
    @Payload() data: string | CaseAnalysisPayload,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const message = ctx.getMessage() as ConsumeMessage;
    let payload: CaseAnalysisPayload;
    try {
      payload = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !payload ||
        payload.schemaVersion !== '1.0' ||
        payload.type !== 'CASE_ANALYSIS' ||
        [payload.tenantId, payload.taskId, payload.caseId, payload.resourceVersionId].some(
          (id) => !deliveryId(id),
        ) ||
        !Number.isSafeInteger(payload.attempt) ||
        payload.attempt < 0 ||
        !Number.isSafeInteger(payload.stateVersion) ||
        payload.stateVersion < 1
      ) {
        throw new Error('invalid event');
      }
    } catch {
      // 无法识别任务身份的消息不能安全执行；不立即重入原队列，由死信配置接管。
      channel.nack(message, false, false);
      return;
    }
    // 认领后的未知故障可能发生在付费调用之后，投递层必须保留“不确定”状态。
    let claimed = false;
    try {
      const task = await this.callback.getContext(payload.taskId);
      // 1. 先比较租户、案件、来源版本与业务轮次，拒绝串任务或过期投递。
      // 2. begin 认领可能已使 Java 状态版本增加一次，所以 RUNNING 的紧邻版本仍可接受。
      // 3. 不匹配时 ACK 是“丢弃旧投递”，不会调用工作流，也不会回调覆盖当前任务。
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
      let claimedTask = task;
      let outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED';
      let report: CaseAnalysisReport | undefined;
      let errorCode: string | undefined;
      try {
        // 工作流服务完成 Java 认领后才调用该回调，后续结果必须使用认领后的版本。
        const result = await this.workflow.run(task, (currentTask) => {
          claimedTask = currentTask;
          claimed = true;
        });
        outcome = result.outcome;
        report = result.report;
      } catch (error) {
        // 已有执行者持有同一线程锁时，重复投递退出，由持锁者继续计算及结果回调。
        if (error instanceof WorkflowBusyError) {
          channel.ack(message);
          return;
        }
        // 只有“已认领 + 操作日志明确失败”才能回报 FAILED；其他异常保留给投递分类。
        if (!(error instanceof WorkflowOperationFailedError) || !claimed) {
          throw error;
        }
        outcome = 'FAILED';
        errorCode = error.errorCode;
        this.logger.warn(`Case analysis ${payload.taskId} failed: ${errorCode}`);
      }
      // 计算成功、待人工复核或明确失败都走同一结果接口。
      // Java 接受当前版本结果之后才能 ACK；回调丢响应时可在后续合法投递中复用已存计算。
      await this.callback.analysisResult(claimedTask, outcome, report, errorCode);
      channel.ack(message);
    } catch (error) {
      // claimed 影响未知故障的分类：认领后可能已经触发付费调用，不能盲目自动重发。
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
