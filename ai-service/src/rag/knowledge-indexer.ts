import { OpenAIEmbeddings } from '@langchain/openai';
import { MilvusKnowledgeStore, KnowledgeVector } from './milvus-knowledge.store';
import { KnowledgeScopeInput, knowledgeScope, knowledgeDecimalId } from './knowledge-scope';

export interface EmbeddingConfiguration {
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
}

export interface IndexChunk {
  id: string;
  text: string;
}

export interface IndexSource extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  chunks: IndexChunk[];
}

export interface EmbeddingModel {
  embedDocuments(texts: string[]): Promise<number[][]>;
}

export interface IndexReceipt {
  collectionName: string;
  dimension: number;
  indexedChunks: number;
  modelIdentity: string;
}

/** 模型域名需在部署白名单中；关闭 SDK 自动重试，付费请求由上层日志管理。 */
export function configuredEmbeddings(
  config: EmbeddingConfiguration,
  allowedHosts: string,
  limits: { batchSize: number; timeout: number } = { batchSize: 50, timeout: 30000 },
): OpenAIEmbeddings {
  if (!config.apiKey?.trim() || !config.model?.trim() || !config.provider?.trim())
    throw new Error('未配置 Embedding 服务商、模型或 API Key');
  let url: URL;
  try {
    url = new URL(config.baseUrl);
  } catch {
    throw new Error('Embedding 接口地址无效');
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash)
    throw new Error('Embedding 接口必须使用无账号参数的 HTTPS 地址');
  const hosts = allowedHosts
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  if (!hosts.includes(url.hostname.toLowerCase()))
    throw new Error('Embedding 接口域名尚未获得部署环境授权');
  return new OpenAIEmbeddings({
    model: config.model,
    apiKey: config.apiKey,
    batchSize: limits.batchSize,
    timeout: limits.timeout,
    maxRetries: 0,
    configuration: {
      baseURL: url.toString().replace(/\/$/, ''),
      fetch: (input, init) => fetch(input, { ...init, redirect: 'error' }),
    },
  });
}

export function embeddingIdentity(
  config: Pick<EmbeddingConfiguration, 'provider' | 'baseUrl' | 'model'>,
): string {
  if (!config.provider?.trim() || !config.baseUrl?.trim() || !config.model?.trim())
    throw new Error('Embedding 模型标识缺失');
  return JSON.stringify([config.provider.trim(), config.baseUrl.trim(), config.model.trim()]);
}

/** 完成向量写入只返回收据，Java 接收结果后才将知识文档发布为 ACTIVE。 */
export async function indexKnowledgeDocument(
  source: IndexSource,
  config: EmbeddingConfiguration,
  embeddings: EmbeddingModel,
  store: MilvusKnowledgeStore,
): Promise<IndexReceipt> {
  if (
    !source.chunks?.length ||
    source.chunks.length > 10000 ||
    !Number.isInteger(source.indexVersion) ||
    source.indexVersion < 1
  )
    throw new Error('知识切片或索引版本无效');
  const scope = knowledgeScope(source.scope, source.caseId);
  [source.tenantId, source.knowledgeBaseId, source.documentId, source.fileVersionId].forEach(
    knowledgeDecimalId,
  );
  const ids = new Set<string>();
  for (const chunk of source.chunks) {
    knowledgeDecimalId(chunk.id);
    if (!chunk.text?.trim() || chunk.text.length > 1000 || ids.has(chunk.id))
      throw new Error('知识切片编号或正文无效');
    ids.add(chunk.id);
  }
  const modelIdentity = embeddingIdentity(config);
  let collectionName = '';
  let dimension = 0;
  let indexedChunks = 0;
  for (let offset = 0; offset < source.chunks.length; offset += 50) {
    const batch = source.chunks.slice(offset, offset + 50);
    const vectors = await embeddings.embedDocuments(batch.map((chunk) => chunk.text));
    if (!Array.isArray(vectors) || vectors.length !== batch.length)
      throw new Error('Embedding 返回数量与切片不符');
    for (const vector of vectors) {
      if (
        !Array.isArray(vector) ||
        vector.length < 2 ||
        vector.length > 4096 ||
        vector.some((value) => !Number.isFinite(value)) ||
        !vector.some((value) => value !== 0) ||
        (dimension !== 0 && vector.length !== dimension)
      )
        throw new Error('Embedding 返回无效向量');
      dimension = vector.length;
    }
    if (!collectionName) collectionName = await store.ensureCollection(modelIdentity, dimension);
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
    await store.upsert(collectionName, records);
    indexedChunks += batch.length;
  }
  return { collectionName, dimension, indexedChunks, modelIdentity };
}
