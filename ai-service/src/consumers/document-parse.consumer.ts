import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../services/task-callback.service';
import { parseDocument } from '../parsers/document.parser';
import { sourceSegments } from '../parsers/source-segments';
import { TaskDeliveryService, transientDeliveryFailure } from '../transport/task-delivery.service';
import { ConsumeMessage } from 'amqplib';

interface DocumentParsePayload {
  eventId: string;
  schemaVersion: string;
  tenantId: string;
  taskId: string;
  resourceVersionId: string;
  attempt: number;
  traceId: string;
  occurredAt: string;
}

/**
 * 文书解析消费者。
 *
 * 当前支持 UTF-8 TXT、含文字层 PDF 和 DOCX 的原文提取。
 * 流程：
 *   1. 获取任务上下文（文件 objectKey 等）
 *   2. 校验摘要和格式后提取原文；不调用模型
 *   3. 回传结果 → Java Core 幂等落库
 *   4. 手动 ACK
 */
@Controller()
export class DocumentParseConsumer {
  private readonly logger = new Logger(DocumentParseConsumer.name);

  constructor(
    private readonly callback: TaskCallbackService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  @MessagePattern('lexflow.q.document.parse')
  async handle(
    @Payload() data: string | DocumentParsePayload,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const originalMsg = ctx.getMessage() as ConsumeMessage;

    let payload: DocumentParsePayload;
    try {
      payload = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !payload ||
        !/^[1-9][0-9]*$/.test(payload.taskId) ||
        !/^[1-9][0-9]*$/.test(payload.tenantId) ||
        !Number.isSafeInteger(payload.attempt) ||
        payload.attempt < 0
      )
        throw new Error('invalid event');
    } catch {
      this.logger.error('Invalid message format, sending to DLQ');
      channel.nack(originalMsg, false, false);
      return;
    }

    const { taskId, attempt } = payload;
    this.logger.log(`[document.parse] taskId=${taskId} attempt=${attempt}`);

    try {
      // 1. 获取任务上下文（含文件 objectKey、mimeType 等）
      const ctx = await this.callback.getContext(taskId);
      // 任务重试或完成后，旧投递只需确认，不得再解析或覆盖新一轮结果。
      if (
        ctx.tenantId !== payload.tenantId ||
        ctx.type !== 'DOCUMENT_PARSE' ||
        ctx.attempt !== attempt ||
        !['QUEUED', 'RUNNING'].includes(ctx.status)
      ) {
        channel.ack(originalMsg);
        return;
      }

      // 格式/内容错误作为任务失败回传，不将无文字层材料伪装成成功。
      const bytes = await this.callback.downloadSource(ctx);
      let parsed;
      let segments;
      try {
        parsed = await parseDocument(bytes, ctx.mimeType, ctx.sha256);
        segments = sourceSegments(parsed);
      } catch (error) {
        const message = error instanceof Error ? error.message : '文件解析失败';
        await this.callback.submitResult({
          taskId,
          attempt: ctx.attempt,
          stateVersion: ctx.stateVersion,
          success: false,
          errorMessage: message,
        });
        channel.ack(originalMsg);
        return;
      }

      await this.callback.submitResult({
        taskId,
        attempt: ctx.attempt,
        stateVersion: ctx.stateVersion,
        success: true,
        parsedText: parsed.text,
        sourceSha256: parsed.sourceSha256,
        segments,
      });

      // Java 已接收幂等回调后才 ACK；回调失败留给投递恢复流程处理。
      channel.ack(originalMsg);
      this.logger.log(
        `[document.parse] taskId=${taskId} → SUCCEEDED (${parsed.mimeType} extracted)`,
      );
    } catch (error) {
      this.logger.warn(`Task ${taskId} delivery deferred; source identity remains fixed`);
      await this.delivery.defer(
        channel,
        originalMsg,
        'lexflow.q.document.parse',
        transientDeliveryFailure(error) ? 'TRANSIENT' : 'PERMANENT',
      );
    }
  }
}
