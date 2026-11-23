import {
  Body,
  Controller,
  Headers,
  HttpCode,
  Post,
  UnauthorizedException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import { MilvusClient } from '@zilliz/milvus2-sdk-node';
import { MilvusKnowledgeStore, RetiredVectorLocation } from './milvus-knowledge.store';
import { internalSecret, milvusAddress } from '../config/settings';

/**
 * Java 已提交退役记录后调用的精确回收入口，不构建模型，也不产生 Embedding 请求。
 * AI 服务根据结构化位置删除 Milvus 向量；哪个版本可以退役由 Java 业务事务决定。
 * 共享内部凭据只证明调用方身份，本控制器不会自行查询 Java 发布指针或重算退役资格。
 */
@Controller('/internal/v1/knowledge-vectors')
export class KnowledgeVectorRetirementController {
  /**
   * 校验内部凭据后，以本次独立 Milvus 连接执行精确版本删除，并在 finally 尝试关闭连接。
   * 位置的字段白名单、Long ID、范围、集合/模型/维度一致性由 deleteRetired 再次校验。
   * 集合已不存在同样返回 deleted=true，它是本次回收完成标记，不是实际删除行数。
   * 存储或位置错误转换为固定 503 提示；Java 仍需保留墓碑并复查，以覆盖迟到向量写入。
   *
   * @param supplied Java 提供的内部共享凭据，错误时返回 401。
   * @param location Java 已登记并允许回收的精确来源位置，不接受任意过滤表达式。
   * @returns 本次请求已完成的标记，不包含原文、向量内容或供应商异常。
   */
  @Post('/retire')
  @HttpCode(200)
  async retire(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() location: RetiredVectorLocation,
  ): Promise<{ deleted: true }> {
    const actual = Buffer.from(supplied ?? '');
    const expected = Buffer.from(internalSecret());
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    const client = new MilvusClient({
      address: milvusAddress(),
      timeout: 3000,
    });
    try {
      await new MilvusKnowledgeStore(client).deleteRetired(location);
      return { deleted: true };
    } catch {
      throw new ServiceUnavailableException('向量回收暂不可用');
    } finally {
      await client.closeConnection().catch(() => undefined);
    }
  }
}
