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

/** Only Java's committed retirement reservations call this endpoint. No model is constructed. */
@Controller('/internal/v1/knowledge-vectors')
export class KnowledgeVectorRetirementController {
  @Post('/retire')
  @HttpCode(200)
  async retire(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() location: RetiredVectorLocation,
  ): Promise<{ deleted: true }> {
    const actual = Buffer.from(supplied ?? ''),
      expected = Buffer.from(process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod');
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    const client = new MilvusClient({
      address: process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530',
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
