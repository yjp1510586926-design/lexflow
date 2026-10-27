import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../services/task-callback.service';
import { KnowledgeScopeKind, knowledgeScope, knowledgeDecimalId } from '../rag/knowledge-scope';
import { KnowledgeIndexWorkflowService } from '../rag/knowledge-index-workflow.service';
import { WorkflowOperationUncertainError } from '../graphs/persistence/mysql-checkpointer';
import { TaskDeliveryService, transientDeliveryFailure } from '../transport/task-delivery.service';
import { ConsumeMessage } from 'amqplib';

interface KnowledgeEvent {
  tenantId: string;
  taskId: string;
  type: string;
  attempt: number;
  scope?: KnowledgeScopeKind;
  caseId?: string | null;
  knowledgeBaseId?: string;
}

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
      )
        throw new Error('invalid event');
      knowledgeDecimalId(event.tenantId);
      knowledgeDecimalId(event.taskId);
      if (event.scope !== undefined || event.caseId !== undefined) {
        if (event.scope === undefined) throw new Error('消息缺少明确的知识范围');
        knowledgeScope(event.scope, event.caseId);
      }
      if (event.knowledgeBaseId !== undefined) knowledgeDecimalId(event.knowledgeBaseId);
    } catch {
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
      if (taskScope.scope === 'TENANT' || task.knowledgeBaseId != null)
        knowledgeDecimalId(task.knowledgeBaseId);
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
      // 旧消息仅兼容案件私有索引；租户公共索引必须显式声明 TENANT 范围。
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
      let receipt;
      try {
        const input = await this.callback.getIndexInput(task);
        const inputScope = knowledgeScope(input.scope, input.caseId);
        if (
          input.tenantId !== task.tenantId ||
          inputScope.scope !== taskScope.scope ||
          inputScope.caseId !== taskScope.caseId ||
          (task.knowledgeBaseId != null && input.knowledgeBaseId !== task.knowledgeBaseId)
        )
          throw new Error('索引任务范围不匹配');
        // CASE 任务上下文只绑定文件，授权后的索引输入才确定知识库，需在此补齐一致性校验。
        if (
          event.knowledgeBaseId !== undefined &&
          event.knowledgeBaseId !== input.knowledgeBaseId
        ) {
          channel.ack(message);
          return;
        }
        receipt = await this.workflow.run(task, input);
      } catch (error) {
        if (error instanceof WorkflowOperationUncertainError || transientDeliveryFailure(error)) {
          await this.delivery.defer(
            channel,
            message,
            'lexflow.q.knowledge.ingest',
            error instanceof WorkflowOperationUncertainError ? 'UNCERTAIN' : 'TRANSIENT',
          );
          return;
        }
        // 服务商错误可能包含私有材料或凭据，回调失败状态时不携带原始异常。
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
