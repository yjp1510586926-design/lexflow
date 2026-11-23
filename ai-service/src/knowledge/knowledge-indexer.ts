import { EmbeddingModel, KNOWLEDGE_EMBEDDING_BATCH_SIZE } from '../ai/models/embedding-model';
import type { ModelConfiguration } from '../ai/models/model-configuration';
import { MilvusKnowledgeStore, KnowledgeVector } from './milvus-knowledge.store';
import { KnowledgeScopeInput, knowledgeScope, knowledgeDecimalId } from './knowledge-scope';

/** Java 预先切分并分配稳定 ID 的原文片段，索引端不自行重新切片。 */
export interface IndexChunk {
  /** Java 分配的切片主键，以字符串保留 Long 精度。 */
  id: string;
  /** 单段原文最多 1000 个 UTF-16 码元，发送给 Embedding 模型但不写入 Milvus。 */
  text: string;
}

/**
 * Java 已授权并固定的索引来源，所有批次均使用同一来源身份与索引版本。
 * 范围、版本和切片编号由 Java 分配；AI 服务负责生成及写入向量，不决定哪个版本对用户发布。
 */
export interface IndexSource extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  /** 原始文件版本，与 documentId 和 indexVersion 一起绑定具体材料。 */
  fileVersionId: string;
  /** Java 分配的正整数索引版本；重建时不覆盖旧的发布指针。 */
  indexVersion: number;
  /** 顺序固定的 1–10000 段原文；批次操作身份依赖这个顺序。 */
  chunks: IndexChunk[];
}

/**
 * 所有批次已写入后的技术收据，供消费者向 Java 回调。
 * 它描述实际向量位置和模型身份；只有 Java 接受成功回调并切换发布版本后，知识才可检索。
 */
export interface IndexReceipt {
  /** 由模型身份摘要和向量维度确定的 Milvus 集合。 */
  collectionName: string;
  /** 从首个有效返回向量读取，后续所有向量都必须一致。 */
  dimension: number;
  /** 已完成 upsert 的切片总数，不是成功模型调用次数。 */
  indexedChunks: number;
  /** 不含 Key 的模型身份，Java 可据此核对查询与索引模型。 */
  modelIdentity: string;
}

/**
 * 生成可比较的模型身份，固定顺序为去除首尾空白后的服务商、基础地址、模型名。
 * 同样的向量维度不代表同一向量空间，因此不能仅凭 dimension 复用索引集合。
 * 身份不包含 Key，也不查询远端模型版本；完整配置修订与来源指纹由工作流另外绑定。
 *
 * @returns 三项组成的 JSON 字符串，任一项缺失时抛错。
 */
export function embeddingIdentity(
  config: Pick<ModelConfiguration, 'provider' | 'baseUrl' | 'model'>,
): string {
  if (!config.provider?.trim() || !config.baseUrl?.trim() || !config.model?.trim()) {
    throw new Error('Embedding 模型标识缺失');
  }
  return JSON.stringify([config.provider.trim(), config.baseUrl.trim(), config.model.trim()]);
}

/**
 * 执行一个固定来源版本的索引：校验切片 → 分批生成向量 → 校验返回值 → 写入 Milvus。
 * 每批最多 50 段，返回数量必须与本批相同；每个向量为 2–4096 维、有限且非全零，
 * 并要求整个文档的各批维度一致，防止把不可比较的向量混在同一集合。
 *
 * 此函数只编排向量生成和写入，不保存付费操作日志，也不跨批次开启数据库事务。
 * 生产调用通过 runPersistedKnowledgeIndex 注入持久化模型包装器；某批失败后可能已有
 * 前序向量写入，Java 的发布指针和精确退役位置负责控制可见性与遗留数据回收。
 *
 * @param source Java 固定的范围、版本和切片，校验失败时不会开始生成向量。
 * @param config 仅用其非凭据字段计算模型身份。
 * @param embeddings 真实客户端或可复用已保存结果的包装器。
 * @param store 按模型和维度隔离集合的存储适配器。
 * @returns 全部批次写入后的收据；模型或存储失败直接上抛，不在此处自动重试。
 */
export async function indexKnowledgeDocument(
  source: IndexSource,
  config: ModelConfiguration,
  embeddings: EmbeddingModel,
  store: MilvusKnowledgeStore,
): Promise<IndexReceipt> {
  if (
    !source.chunks?.length ||
    source.chunks.length > 10000 ||
    !Number.isInteger(source.indexVersion) ||
    source.indexVersion < 1
  ) {
    throw new Error('知识切片或索引版本无效');
  }
  const scope = knowledgeScope(source.scope, source.caseId);
  [source.tenantId, source.knowledgeBaseId, source.documentId, source.fileVersionId].forEach(
    knowledgeDecimalId,
  );
  const chunkIds = new Set<string>();
  for (const chunk of source.chunks) {
    knowledgeDecimalId(chunk.id);
    if (!chunk.text?.trim() || chunk.text.length > 1000 || chunkIds.has(chunk.id)) {
      throw new Error('知识切片编号或正文无效');
    }
    chunkIds.add(chunk.id);
  }
  const modelIdentity = embeddingIdentity(config);
  let collectionName = '';
  let dimension = 0;
  let indexedChunks = 0;
  for (let offset = 0; offset < source.chunks.length; offset += KNOWLEDGE_EMBEDDING_BATCH_SIZE) {
    const batch = source.chunks.slice(offset, offset + KNOWLEDGE_EMBEDDING_BATCH_SIZE);
    const vectors = await embeddings.embedDocuments(batch.map((chunk) => chunk.text));
    if (!Array.isArray(vectors) || vectors.length !== batch.length) {
      throw new Error('Embedding 返回数量与切片不符');
    }
    // 同一文档的所有批次必须维度一致，且每个向量都应有限、非零。
    for (const vector of vectors) {
      if (
        !Array.isArray(vector) ||
        vector.length < 2 ||
        vector.length > 4096 ||
        vector.some((value) => !Number.isFinite(value)) ||
        !vector.some((value) => value !== 0) ||
        (dimension !== 0 && vector.length !== dimension)
      ) {
        throw new Error('Embedding 返回无效向量');
      }
      dimension = vector.length;
    }
    // 首批有效向量确定维度后才建/验集合；不能在模型返回前猜测维度。
    if (!collectionName) {
      collectionName = await store.ensureCollection(modelIdentity, dimension);
    }
    // Milvus 只保存向量及版本标识，检索命中后仍由 Java 返回授权原文。
    const records: KnowledgeVector[] = batch.map((chunk, index) => ({
      chunkId: chunk.id,
      tenantId: source.tenantId,
      ...scope,
      knowledgeBaseId: source.knowledgeBaseId,
      documentId: source.documentId,
      fileVersionId: source.fileVersionId,
      indexVersion: source.indexVersion,
      embedding: vectors[index],
    }));
    // upsert 成功才累计数量；“已写入”与“已发布”是两个阶段，此处不修改 Java 状态。
    await store.upsert(collectionName, records);
    indexedChunks += batch.length;
  }
  return { collectionName, dimension, indexedChunks, modelIdentity };
}
