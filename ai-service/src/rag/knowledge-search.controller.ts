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
import {
  configuredEmbeddings,
  embeddingIdentity,
  EmbeddingConfiguration,
} from './knowledge-indexer';
import { MilvusKnowledgeStore, VectorHit } from './milvus-knowledge.store';
import { KnowledgeScopeInput, knowledgeScope, knowledgeDecimalId } from './knowledge-scope';

interface SearchRequest extends EmbeddingConfiguration, KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentIds: string[];
  indexVersion: number;
  collectionName: string;
  dimension: number;
  query: string;
  limit: number;
}

@Controller('/internal/v1/knowledge')
export class KnowledgeSearchController {
  @Post('/search')
  @HttpCode(200)
  async search(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: SearchRequest,
  ): Promise<{ hits: VectorHit[] }> {
    const expected = process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod';
    const actual = Buffer.from(supplied ?? '');
    const reference = Buffer.from(expected);
    if (
      !reference.length ||
      actual.length !== reference.length ||
      !timingSafeEqual(actual, reference)
    )
      throw new UnauthorizedException();
    if (
      !input ||
      typeof input.query !== 'string' ||
      input.query.trim().length < 2 ||
      input.query.length > 500 ||
      !Number.isInteger(input.dimension) ||
      !Number.isInteger(input.limit) ||
      input.limit < 1 ||
      input.limit > 50 ||
      !Array.isArray(input.documentIds) ||
      input.documentIds.length === 0 ||
      input.documentIds.length > 100
    )
      throw new Error('知识检索参数无效');
    knowledgeScope(input.scope, input.caseId);
    [input.tenantId, input.knowledgeBaseId, ...input.documentIds].forEach(knowledgeDecimalId);
    if (!Number.isInteger(input.indexVersion) || input.indexVersion < 1)
      throw new Error('知识检索范围无效');
    // 查询必须使用建索引时的模型与维度，单纯维度相同不代表向量空间兼容。
    const expectedCollection = MilvusKnowledgeStore.collectionName(
      embeddingIdentity(input),
      input.dimension,
    );
    if (input.collectionName !== expectedCollection) throw new Error('索引模型与查询模型不一致');
    const embeddings = configuredEmbeddings(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '');
    try {
      const vector = await embeddings.embedQuery(input.query.trim());
      const client = new MilvusClient({ address: process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530' });
      try {
        const store = new MilvusKnowledgeStore(client);
        return { hits: await store.search(input.collectionName, input, vector, input.limit) };
      } finally {
        await client.closeConnection();
      }
    } catch {
      throw new ServiceUnavailableException('知识检索暂不可用，请检查模型与向量服务');
    }
  }
}
