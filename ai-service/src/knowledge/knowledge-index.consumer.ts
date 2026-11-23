import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../infrastructure/java/task-callback.service';
import { KnowledgeScopeKind, knowledgeScope, knowledgeDecimalId } from './knowledge-scope';
import { KnowledgeIndexWorkflowService } from './knowledge-index-workflow.service';
import { WorkflowOperationUncertainError } from '../infrastructure/persistence/mysql-checkpointer';
import {
  TaskDeliveryService,
  transientDeliveryFailure,
} from '../infrastructure/messaging/task-delivery.service';
import { ConsumeMessage } from 'amqplib';

/**
 * Java 发布的知识索引事件，只携带任务身份和可选的知识范围信息。
 * scope 可省略以兼容旧案件消息；公共知识库消息必须明确声明 TENANT。
 * 切片、固定索引版本及模型凭据均需从 Java 的索引输入接口重新取得。
 */
interface KnowledgeEvent {
  tenantId: string;
  taskId: string;
  type: string;
  attempt: number;
  /** CASE 需要案件编号，TENANT 表示律所公共知识；具体约束由 knowledgeScope 校验。 */
  scope?: KnowledgeScopeKind;
  caseId?: string | null;
  knowledgeBaseId?: string;
}

/**
 * 知识索引的消息入口，依次核对事件、Java 任务上下文和实际索引输入的范围一致性。
 * 工作流逐批保存 Embedding 结果并写入向量，消费者只回传写入回执，不自行发布索引。
 * Java 接受当前轮次成功结果后才切换发布版本，Milvus 已写入不代表文档已可检索。
 */
@Controller()
export class KnowledgeIndexConsumer {
  private readonly logger = new Logger(KnowledgeIndexConsumer.name);

  constructor(
    private readonly callback: TaskCallbackService,
    private readonly workflow: KnowledgeIndexWorkflowService = new KnowledgeIndexWorkflowService(
      callback,
    ),
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  /**
   * 消费知识入库消息，并将模型计算失败与成功回调失败分开处理。
   * 已保存的向量化批次可在合法恢复中复用；遗留不确定调用直接交给死信处理，
   * 不能因为消息重投就再次调用可能已经计费的 Embedding 请求。
   *
   * @param data Java 索引事件对象或 JSON 字符串。
   * @param ctx 提供消费通道与原消息，回调被接受后才 ACK。
   * @returns 本次投递结束；索引发布状态仍由 Java 业务记录决定。
   */
  @MessagePattern('lexflow.q.knowledge.ingest')
  async handle(@Payload() data: string | KnowledgeEvent, @Ctx() ctx: RmqContext): Promise<void> {
    const channel = ctx.getChannelRef();
    const message = ctx.getMessage() as ConsumeMessage;
    let event: KnowledgeEvent;
    try {
      event = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !event ||
        event.type !== 'KNOWLEDGE_INGEST' ||
        !Number.isSafeInteger(event.attempt) ||
        event.attempt < 0
      ) {
        throw new Error('invalid event');
      }
      knowledgeDecimalId(event.tenantId);
      knowledgeDecimalId(event.taskId);
      if (event.scope !== undefined || event.caseId !== undefined) {
        // 一旦携带范围字段，就必须完整表达语义，不能把缺 scope 的消息猜成公共索引。
        if (event.scope === undefined) {
          throw new Error('消息缺少明确的知识范围');
        }
        knowledgeScope(event.scope, event.caseId);
      }
      if (event.knowledgeBaseId !== undefined) {
        knowledgeDecimalId(event.knowledgeBaseId);
      }
    } catch {
      // 格式或范围无效的事件不值得自动重试；拒绝后由原队列死信配置接管。
      this.logger.error('Invalid knowledge index event sent to DLQ');
      channel.nack(message, false, false);
      return;
    }

    const { taskId } = event;
    try {
      const task = await this.callback.getContext(taskId);
      const taskScope = knowledgeScope(task.scope, task.caseId);
      const eventScope =
        event.scope === undefined ? undefined : knowledgeScope(event.scope, event.caseId);
      if (taskScope.scope === 'TENANT' || task.knowledgeBaseId != null) {
        knowledgeDecimalId(task.knowledgeBaseId);
      }
      // 先核对当前 Java 任务身份、执行轮次和状态；失效投递只 ACK，不执行索引或回调。
      if (
        task.type !== 'KNOWLEDGE_INGEST' ||
        task.taskId !== event.taskId ||
        task.tenantId !== event.tenantId ||
        task.attempt !== event.attempt ||
        !['QUEUED', 'RUNNING'].includes(task.status)
      ) {
        channel.ack(message);
        return;
      }
      // 旧消息仅兼容案件私有索引；公共任务要求事件明确声明 TENANT，避免范围升级。
      // 已声明的范围、案件编号和知识库编号必须与 Java 当前上下文一致。
      if (
        (!eventScope && taskScope.scope === 'TENANT') ||
        (eventScope &&
          (eventScope.scope !== taskScope.scope || eventScope.caseId !== taskScope.caseId)) ||
        (event.knowledgeBaseId !== undefined &&
          task.knowledgeBaseId != null &&
          event.knowledgeBaseId !== task.knowledgeBaseId)
      ) {
        channel.ack(message);
        return;
      }
      // 回执只描述已写入的位置；是否对外可检索仍由 Java 的发布指针决定。
      let receipt: Awaited<ReturnType<KnowledgeIndexWorkflowService['run']>>;
      try {
        // 输入接口由 Java 重新授权切片及模型配置，本地再与任务范围做一致性比较。
        const input = await this.callback.getIndexInput(task);
        const inputScope = knowledgeScope(input.scope, input.caseId);
        if (
          input.tenantId !== task.tenantId ||
          inputScope.scope !== taskScope.scope ||
          inputScope.caseId !== taskScope.caseId ||
          (task.knowledgeBaseId != null && input.knowledgeBaseId !== task.knowledgeBaseId)
        ) {
          throw new Error('索引任务范围不匹配');
        }
        // CASE 任务上下文只绑定文件，授权后的索引输入才确定知识库，需在此补齐一致性校验。
        if (
          event.knowledgeBaseId !== undefined &&
          event.knowledgeBaseId !== input.knowledgeBaseId
        ) {
          channel.ack(message);
          return;
        }
        // 工作流持锁执行，批次操作日志先保存模型结果，再登记并写入精确向量位置。
        receipt = await this.workflow.run(task, input);
      } catch (error) {
        // 不确定调用进入 UNCERTAIN 死信；明确瞬时故障才走延时重试并复用已存批次。
        if (error instanceof WorkflowOperationUncertainError || transientDeliveryFailure(error)) {
          await this.delivery.defer(
            channel,
            message,
            'lexflow.q.knowledge.ingest',
            error instanceof WorkflowOperationUncertainError ? 'UNCERTAIN' : 'TRANSIENT',
          );
          return;
        }
        // 其余计算错误形成明确失败回调。供应商错误可能含材料或凭据，因此不透传异常。
        // Java 接受失败状态后才能 ACK；这里的回调若失败，仍会进入最外层投递恢复。
        this.logger.warn(`[knowledge.ingest] taskId=${taskId} failed`);
        await this.callback.submitIndexResult({
          taskId,
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          success: false,
        });
        channel.ack(message);
        return;
      }
      // 成功回调单独置于计算失败分支之外，避免回调丢失时把已完成索引改报为失败。
      await this.callback.submitIndexResult({
        taskId,
        attempt: task.attempt,
        stateVersion: task.stateVersion,
        success: true,
        ...receipt,
      });
      this.logger.log(`[knowledge.ingest] taskId=${taskId} indexed`);
      channel.ack(message);
    } catch (error) {
      this.logger.warn(`[knowledge.ingest] taskId=${taskId} delivery deferred`);
      await this.delivery.defer(
        channel,
        message,
        'lexflow.q.knowledge.ingest',
        transientDeliveryFailure(error) ? 'TRANSIENT' : 'PERMANENT',
      );
    }
  }
}
