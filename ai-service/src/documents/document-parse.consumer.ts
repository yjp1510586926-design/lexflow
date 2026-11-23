import { Controller, Logger } from '@nestjs/common';
import { MessagePattern, Payload, Ctx, RmqContext } from '@nestjs/microservices';
import { TaskCallbackService } from '../infrastructure/java/task-callback.service';
import { parseDocument } from './parsers/document.parser';
import { sourceSegments } from './parsers/source-segments';
import type { ParsedDocument } from './parsers/plain-text.parser';
import type { SourceSegment } from './parsers/source-segments';
import {
  TaskDeliveryService,
  transientDeliveryFailure,
} from '../infrastructure/messaging/task-delivery.service';
import { ConsumeMessage } from 'amqplib';

/**
 * Java 经消息队列发布的解析事件，只定位任务，不直接携带文件内容。
 * 文件类型、摘要和下载权限从 Java 当前任务接口取得；不能把事件快照当作永久授权。
 * 本消费者实际校验 taskId、tenantId、attempt，其余事件元数据保留用于协议表达。
 */
interface DocumentParsePayload {
  eventId: string;
  schemaVersion: string;
  tenantId: string;
  taskId: string;
  resourceVersionId: string;
  /** 业务执行轮次；延时重投不增加 attempt，人工重建任务轮次由 Java 决定。 */
  attempt: number;
  traceId: string;
  occurredAt: string;
}

/**
 * 文书解析消费者。
 *
 * 当前支持 UTF-8 TXT、含文字层 PDF 和 DOCX，不调用模型，也不执行 OCR。
 * 流程：读取 Java 当前任务 → 校验消息仍适用 → 下载授权文件 → 核对摘要并提取原文
 * → 生成页/段位置 → 将结果回传 Java → 手动 ACK。
 * 解析器只负责技术上的原文提取；Java 仍拥有文件授权、业务状态及结果保存权。
 */
@Controller()
export class DocumentParseConsumer {
  private readonly logger = new Logger(DocumentParseConsumer.name);

  constructor(
    private readonly callback: TaskCallbackService,
    private readonly delivery: TaskDeliveryService = new TaskDeliveryService(),
  ) {}

  /**
   * 处理一条文件解析投递，把“文件内容失败”和“基础设施/回调失败”分开处理。
   * 前者形成明确的失败回调，后者交给消息恢复层，不把暂时下载失败当成文件解析结论。
   *
   * @param data 解析事件对象，兼容字符串形式的 JSON 事件。
   * @param ctx 提供消费通道和原始消息，成功回调后才允许 ACK。
   * @returns 本次投递已结束；成功、失败与过期投递都可能正常结束。
   */
  @MessagePattern('lexflow.q.document.parse')
  async handle(
    @Payload() data: string | DocumentParsePayload,
    @Ctx() ctx: RmqContext,
  ): Promise<void> {
    const channel = ctx.getChannelRef();
    const message = ctx.getMessage() as ConsumeMessage;

    let payload: DocumentParsePayload;
    try {
      payload = typeof data === 'string' ? JSON.parse(data) : data;
      if (
        !payload ||
        !/^[1-9][0-9]*$/.test(payload.taskId) ||
        !/^[1-9][0-9]*$/.test(payload.tenantId) ||
        !Number.isSafeInteger(payload.attempt) ||
        payload.attempt < 0
      ) {
        throw new Error('invalid event');
      }
    } catch {
      // 无法安全识别任务的坏消息不立即重入队列，由队列死信配置保留。
      this.logger.error('Invalid message format, sending to DLQ');
      channel.nack(message, false, false);
      return;
    }

    const { taskId, attempt } = payload;
    this.logger.log(`[document.parse] taskId=${taskId} attempt=${attempt}`);

    try {
      // Java 接口返回当前文件元数据和状态；消息中的旧元数据不会用于直接下载对象存储。
      const task = await this.callback.getContext(taskId);
      // 比较事件租户和轮次，同时要求 Java 上下文属于仍可处理的 DOCUMENT_PARSE 任务。
      // 若任务已经完成、取消或进入新轮次，ACK 旧投递后结束，不回传旧结果覆盖新状态。
      if (
        task.tenantId !== payload.tenantId ||
        task.type !== 'DOCUMENT_PARSE' ||
        task.attempt !== attempt ||
        !['QUEUED', 'RUNNING'].includes(task.status)
      ) {
        channel.ack(message);
        return;
      }

      // 下载由 Java 按 attempt/stateVersion 再次校验权限，响应在这里仍是原始文件字节。
      const bytes = await this.callback.downloadSource(task);
      let parsed: ParsedDocument;
      let segments: SourceSegment[];
      try {
        // parseDocument 核对摘要并提取原文；sourceSegments 把页/段映射到 UTF-16 字符区间。
        parsed = await parseDocument(bytes, task.mimeType, task.sha256);
        segments = sourceSegments(parsed);
      } catch (error) {
        // 这里的失败只覆盖本地解析和位置计算，例如格式损坏、无文字层或摘要不符。
        // 失败结果同样必须由 Java 接受后才能 ACK，防止文件失败却永久停留在运行中。
        const errorMessage = error instanceof Error ? error.message : '文件解析失败';
        await this.callback.submitResult({
          taskId,
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          success: false,
          errorMessage,
        });
        channel.ack(message);
        return;
      }

      // 正文、文件摘要与段落偏移一起回传，供 Java 后续保存原文并核对引用位置。
      await this.callback.submitResult({
        taskId,
        attempt: task.attempt,
        stateVersion: task.stateVersion,
        success: true,
        parsedText: parsed.text,
        sourceSha256: parsed.sourceSha256,
        segments,
      });

      // Java 已接收幂等回调后才 ACK；回调失败留给投递恢复流程处理。
      channel.ack(message);
      this.logger.log(
        `[document.parse] taskId=${taskId} → SUCCEEDED (${parsed.mimeType} extracted)`,
      );
    } catch (error) {
      // 下载、获取上下文或回调的异常走投递分类；这里只重试明确的瞬时基础设施错误。
      // 原文解析不涉及付费模型，因此无需在本消费者维护模型调用的不确定状态。
      this.logger.warn(`Task ${taskId} delivery deferred; source identity remains fixed`);
      await this.delivery.defer(
        channel,
        message,
        'lexflow.q.document.parse',
        transientDeliveryFailure(error) ? 'TRANSIENT' : 'PERMANENT',
      );
    }
  }
}
