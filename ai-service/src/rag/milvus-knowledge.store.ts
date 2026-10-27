import { createHash } from 'node:crypto';
import { ConsistencyLevelEnum, DataType, MilvusClient } from '@zilliz/milvus2-sdk-node';
import { KnowledgeScopeInput, knowledgeDecimalId, milvusKnowledgeCaseId } from './knowledge-scope';

export interface VectorScope extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentIds: string[];
  indexVersion: number;
}

export interface KnowledgeVector extends KnowledgeScopeInput {
  chunkId: string;
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  embedding: number[];
}

export interface VectorHit {
  chunkId: string;
  documentId: string;
  score: number;
}

export interface RetiredVectorLocation extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  collectionName: string;
  dimension: number;
  modelIdentity: string;
}

function validVector(vector: number[], dimension: number): void {
  if (
    !Array.isArray(vector) ||
    vector.length !== dimension ||
    vector.some((value) => !Number.isFinite(value)) ||
    !vector.some((value) => value !== 0)
  )
    throw new Error('Embedding 维度或数值无效');
}

/** Collection 按模型身份与维度隔离；向量记录仅携带标识，原文继续由 Java 授权提供。 */
export class MilvusKnowledgeStore {
  constructor(private readonly client: MilvusClient) {}

  /** 回收只接受精确结构化位置，限定到来源版本，不能执行任意过滤表达式或删除整个集合。 */
  async deleteRetired(location: RetiredVectorLocation): Promise<void> {
    if (
      !location ||
      typeof location !== 'object' ||
      Array.isArray(location) ||
      !['CASE', 'TENANT'].includes(location.scope ?? '') ||
      Object.keys(location).some(
        (key) =>
          ![
            'tenantId',
            'knowledgeBaseId',
            'documentId',
            'fileVersionId',
            'indexVersion',
            'scope',
            'caseId',
            'collectionName',
            'dimension',
            'modelIdentity',
          ].includes(key),
      )
    )
      throw new Error('向量回收范围无效');
    const tenant = knowledgeDecimalId(location.tenantId),
      base = knowledgeDecimalId(location.knowledgeBaseId);
    const document = knowledgeDecimalId(location.documentId),
      version = knowledgeDecimalId(location.fileVersionId);
    const caseId = milvusKnowledgeCaseId(location);
    if (
      !Number.isInteger(location.indexVersion) ||
      location.indexVersion < 1 ||
      location.indexVersion > 2147483647 ||
      MilvusKnowledgeStore.collectionName(location.modelIdentity, location.dimension) !==
        location.collectionName
    )
      throw new Error('向量回收位置无效');
    const filter = `tenant_id == "${tenant}" && case_id == "${caseId}" && knowledge_base_id == "${base}" && document_id == "${document}" && file_version_id == "${version}" && index_version == ${location.indexVersion}`;
    const present = await this.client.hasCollection({
      collection_name: location.collectionName,
      timeout: 3000,
    });
    if (
      (present.status?.code !== undefined && present.status.code !== 0) ||
      (present.status?.error_code !== undefined &&
        present.status.error_code !== 'Success' &&
        present.status.error_code !== 0)
    )
      throw new Error('向量回收暂不可用');
    if (typeof present.value !== 'boolean') throw new Error('向量回收暂不可用');
    if (!present.value) return;
    const result = await this.client.deleteEntities({
      collection_name: location.collectionName,
      filter,
      timeout: 3000,
    });
    if (
      (result.status.code !== undefined && result.status.code !== 0) ||
      (result.status.error_code !== 'Success' && result.status.error_code !== 0)
    )
      throw new Error('向量回收暂不可用');
  }

  static collectionName(modelIdentity: string, dimension: number): string {
    if (
      !modelIdentity ||
      modelIdentity.length > 1000 ||
      !Number.isInteger(dimension) ||
      dimension < 2 ||
      dimension > 4096
    )
      throw new Error('模型版本或向量维度无效');
    const digest = createHash('sha256').update(modelIdentity).digest('hex').slice(0, 16);
    return `lexflow_chunks_${digest}_${dimension}_v1`;
  }

  async ensureCollection(modelIdentity: string, dimension: number): Promise<string> {
    const collectionName = MilvusKnowledgeStore.collectionName(modelIdentity, dimension);
    const present = await this.client.hasCollection({ collection_name: collectionName });
    if (!present.value) {
      const fields = [
        { name: 'chunk_id', data_type: DataType.VarChar, is_primary_key: true, max_length: 32 },
        { name: 'tenant_id', data_type: DataType.VarChar, max_length: 32 },
        { name: 'case_id', data_type: DataType.VarChar, max_length: 32 },
        { name: 'knowledge_base_id', data_type: DataType.VarChar, max_length: 32 },
        { name: 'document_id', data_type: DataType.VarChar, max_length: 32 },
        { name: 'file_version_id', data_type: DataType.VarChar, max_length: 32 },
        { name: 'index_version', data_type: DataType.Int32 },
        { name: 'embedding', data_type: DataType.FloatVector, dim: dimension },
      ];
      const status = await this.client.createCollection({
        collection_name: collectionName,
        schema: fields,
        enable_dynamic_field: false,
        consistency_level: 'Strong',
        index_params: [
          { field_name: 'embedding', index_type: 'AUTOINDEX', metric_type: 'COSINE', params: {} },
        ],
      });
      if (status.code !== undefined && status.code !== 0)
        throw new Error('Milvus 创建 Collection 失败');
    }
    const description = await this.client.describeCollection({ collection_name: collectionName });
    const vectorField = description.schema.fields.find((field) => field.name === 'embedding');
    const storedDim = Number(
      vectorField?.type_params?.find((param) => param.key === 'dim')?.value ?? vectorField?.dim,
    );
    if (storedDim !== dimension) throw new Error('Milvus Collection 维度与模型不一致');
    return collectionName;
  }

  async upsert(collectionName: string, records: KnowledgeVector[]): Promise<void> {
    if (
      records.length === 0 ||
      records.length > 100 ||
      !/^lexflow_chunks_[a-f0-9]{16}_[0-9]+_v1$/.test(collectionName)
    )
      throw new Error('向量批次或 Collection 无效');
    const dimension = Number(collectionName.match(/_([0-9]+)_v1$/)?.[1]);
    for (const item of records) {
      knowledgeDecimalId(item.chunkId);
      knowledgeDecimalId(item.tenantId);
      milvusKnowledgeCaseId(item);
      knowledgeDecimalId(item.knowledgeBaseId);
      knowledgeDecimalId(item.documentId);
      knowledgeDecimalId(item.fileVersionId);
      if (!Number.isInteger(item.indexVersion) || item.indexVersion < 1)
        throw new Error('索引版本无效');
      validVector(item.embedding, dimension);
    }
    const result = await this.client.upsert({
      collection_name: collectionName,
      data: records.map((item) => ({
        chunk_id: item.chunkId,
        tenant_id: item.tenantId,
        case_id: milvusKnowledgeCaseId(item),
        knowledge_base_id: item.knowledgeBaseId,
        document_id: item.documentId,
        file_version_id: item.fileVersionId,
        index_version: item.indexVersion,
        embedding: item.embedding,
      })),
    });
    if (result.status.code !== undefined && result.status.code !== 0)
      throw new Error('Milvus 写入失败');
    if (result.status.error_code !== 'Success' && result.status.error_code !== 0)
      throw new Error('Milvus 写入失败');
  }

  async search(
    collectionName: string,
    scope: VectorScope,
    embedding: number[],
    limit = 10,
  ): Promise<VectorHit[]> {
    if (!/^lexflow_chunks_[a-f0-9]{16}_[0-9]+_v1$/.test(collectionName))
      throw new Error('Collection 无效');
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('检索条数无效');
    const dimension = Number(collectionName.match(/_([0-9]+)_v1$/)?.[1]);
    validVector(embedding, dimension);
    const tenantId = knowledgeDecimalId(scope.tenantId);
    const caseId = milvusKnowledgeCaseId(scope);
    const knowledgeBaseId = knowledgeDecimalId(scope.knowledgeBaseId);
    if (!Number.isInteger(scope.indexVersion) || scope.indexVersion < 1)
      throw new Error('索引版本无效');
    if (!Array.isArray(scope.documentIds)) throw new Error('授权文档范围无效');
    if (scope.documentIds.length === 0) return [];
    if (scope.documentIds.length > 100) throw new Error('授权文档数量超过检索上限');
    const ids = scope.documentIds.map(knowledgeDecimalId);
    // 检索范围由 Java 的授权文档列表限定；同时过滤租户、案件/公共范围、知识库和索引版本。
    const filter = `tenant_id == "${tenantId}" && case_id == "${caseId}" && knowledge_base_id == "${knowledgeBaseId}" && index_version == ${scope.indexVersion} && document_id in [${ids.map((id) => `"${id}"`).join(',')}]`;
    const result = await this.client.search({
      collection_name: collectionName,
      data: embedding,
      anns_field: 'embedding',
      filter,
      limit,
      consistency_level: ConsistencyLevelEnum.Strong,
      output_fields: ['chunk_id', 'document_id'],
    });
    if (result.status.code !== undefined && result.status.code !== 0)
      throw new Error('Milvus 检索失败');
    return result.results.map((row) => ({
      chunkId: String(row.chunk_id),
      documentId: String(row.document_id),
      score: Number(row.score),
    }));
  }
}
