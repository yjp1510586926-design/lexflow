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
import { configuredEmbeddings } from '../ai/models/embedding-model';
import type { ModelConfiguration } from '../ai/models/model-configuration';
import { embeddingIdentity } from './knowledge-indexer';
import { MilvusKnowledgeStore, VectorHit } from './milvus-knowledge.store';
import { KnowledgeScopeInput, knowledgeScope, knowledgeDecimalId } from './knowledge-scope';
import { allowedModelHosts, internalSecret, milvusAddress } from '../config/settings';

/**
 * Java 内部检索请求，将模型配置、查询文本与当前授权范围一起传入。
 * 不从前端接受任意集合或文档列表；Java 负责根据已发布版本和实时权限构造这些字段。
 * ModelConfiguration 中含运行时 Key，因此整个请求不能进入持久化状态或日志。
 */
interface SearchRequest extends ModelConfiguration, KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  /** 1–100 个当前允许检索的文档 ID；空列表在此入口被拒绝，不能表示“查全部”。 */
  documentIds: string[];
  /** Java 选定的正整数索引版本，和已发布材料保持对应。 */
  indexVersion: number;
  /** 必须匹配本次服务商/地址/模型及 dimension 推导出的集合名称。 */
  collectionName: string;
  /** 已发布索引实际采用的向量维度，用于核对集合与查询向量。 */
  dimension: number;
  query: string;
  /** 召回条数为 1–50，由 Java 决定普通召回还是有界扩大检索。 */
  limit: number;
}

/**
 * 内部知识召回入口，只返回命中标识和相似分数，原文读取与最终授权仍由 Java 完成。
 * 控制器先验证共享内部凭据，再检查请求边界与模型身份，最后调用 Embedding 和 Milvus。
 * 查询模型必须与索引模型身份一致，仅仅维度相同不足以认定向量可比较。
 */
@Controller('/internal/v1/knowledge')
export class KnowledgeSearchController {
  /**
   * 根据本次授权范围执行一次召回，不自行扩大文档范围或更换模型。
   * query 去除首尾空白后生成向量，Milvus 继续按租户、范围、知识库、索引版本和文档过滤。
   * 无命中时返回 hits=[]，由 Java 决定是否进行下一次有预算的检索或明确拒答。
   * 内部凭据错误返回 401；模型/向量调用失败转换为固定 503 提示，不返回原始供应商异常。
   *
   * @param supplied Java 提供的 X-Internal-Secret，先比较字节长度，再做恒定时间比较。
   * @param input 已授权范围、查询和本次模型凭据，参数和索引模型不匹配时直接拒绝。
   * @returns 命中切片/文档 ID 及分数，不代表这些来源在最终返回时仍有效，Java 必须复核。
   */
  @Post('/search')
  @HttpCode(200)
  async search(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: SearchRequest,
  ): Promise<{ hits: VectorHit[] }> {
    const expected = internalSecret();
    const actual = Buffer.from(supplied ?? '');
    const reference = Buffer.from(expected);
    if (
      !reference.length ||
      actual.length !== reference.length ||
      !timingSafeEqual(actual, reference)
    ) {
      throw new UnauthorizedException();
    }
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
    ) {
      throw new Error('知识检索参数无效');
    }
    knowledgeScope(input.scope, input.caseId);
    [input.tenantId, input.knowledgeBaseId, ...input.documentIds].forEach(knowledgeDecimalId);
    if (!Number.isInteger(input.indexVersion) || input.indexVersion < 1) {
      throw new Error('知识检索范围无效');
    }
    // 查询必须使用建索引时的模型与维度，单纯维度相同不代表向量空间兼容。
    const expectedCollection = MilvusKnowledgeStore.collectionName(
      embeddingIdentity(input),
      input.dimension,
    );
    if (input.collectionName !== expectedCollection) {
      throw new Error('索引模型与查询模型不一致');
    }
    const embeddings = configuredEmbeddings(input, allowedModelHosts());
    try {
      // 查询必须转换到建库模型的同一向量空间，再按授权范围过滤召回。
      const vector = await embeddings.embedQuery(input.query.trim());
      // 模型成功返回后才创建本次查询的 Milvus 连接，内层 finally 负责释放。
      const client = new MilvusClient({ address: milvusAddress() });
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
