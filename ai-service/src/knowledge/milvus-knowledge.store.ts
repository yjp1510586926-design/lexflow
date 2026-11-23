import { createHash } from 'node:crypto';
import { ConsistencyLevelEnum, DataType, MilvusClient } from '@zilliz/milvus2-sdk-node';
import { KnowledgeScopeInput, knowledgeDecimalId, milvusKnowledgeCaseId } from './knowledge-scope';

/**
 * 单次检索的结构化范围，由 Java 根据当前权限和已发布索引计算。
 * 检索会同时过滤租户、案件/公共范围、知识库、索引版本和 documentIds，不能省略任一边界。
 */
export interface VectorScope extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  /** 最多 100 个已授权文档 ID；空列表直接返回空命中，不向量检索整个知识库。 */
  documentIds: string[];
  indexVersion: number;
}

/**
 * Milvus 中的一条切片向量记录，只携带来源身份和向量，不保存案件原文或模型 Key。
 * chunkId 是集合主键；文档/文件/索引版本由 Java 分配，重建时必须保留这些版本边界。
 */
export interface KnowledgeVector extends KnowledgeScopeInput {
  /** Java 的稳定切片 ID；命中后由 Java 映射并重新授权对应原文。 */
  chunkId: string;
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  /** 维度与集合一致的有限、非全零向量；不以向量本身证明来源真实。 */
  embedding: number[];
}

/**
 * 只返回命中标识与相似分数，Java 需重新核对当前文档、版本及权限，再取原文组成引用。
 * score 来自 Milvus 的 COSINE 检索结果，不是法律结论可信度，也不等同于 Rerank 的分数契约。
 */
export interface VectorHit {
  /** Java 的稳定切片 ID；命中后由 Java 映射并重新授权对应原文。 */
  chunkId: string;
  documentId: string;
  score: number;
}

/**
 * Java 在向量写入前登记的精确位置，用于回收取消、失败或被替代版本的遗留向量。
 * 回收同时绑定集合/模型/维度和租户、范围、知识库、文档、文件版本、索引版本；
 * 接口不接受任意过滤表达式，也不能仅凭 documentId 删除其他版本。
 */
export interface RetiredVectorLocation extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  /** 必须能由 modelIdentity 与 dimension 重新算出，不能随意指定其他集合。 */
  collectionName: string;
  dimension: number;
  /** 建索引时记录的不含 Key 的模型身份，用于交叉验证集合位置。 */
  modelIdentity: string;
}

/**
 * 写入和查询共用的向量校验，防止错误维度、NaN/Infinity 或全零向量参与相似度计算。
 * 此处 dimension 来自集合名，建集合阶段另有限制 2–4096 维；不会修剪、补零或重归一化输入。
 * 校验通过不返回新向量，失败直接抛错，避免静默纠正后改变检索空间。
 */
function validateVector(vector: number[], dimension: number): void {
  if (
    !Array.isArray(vector) ||
    vector.length !== dimension ||
    vector.some((value) => !Number.isFinite(value)) ||
    !vector.some((value) => value !== 0)
  ) {
    throw new Error('Embedding 维度或数值无效');
  }
}

/**
 * Milvus 访问适配器，负责集合结构、向量数值校验和精确范围过滤。
 * 集合按模型身份摘要及维度隔离，但同一集合内仍可能有多个租户，必须继续按来源字段过滤。
 * 本类没有用户会话或 Java 业务权限视图；调用方负责提供已授权范围，Java 在返回原文前再复核。
 * 不保存正文、不调用 LLM，也不把向量写入成功解释成业务发布成功。
 */
export class MilvusKnowledgeStore {
  constructor(private readonly client: MilvusClient) {}

  /**
   * 删除 Java 已确认退役的一个精确来源版本，不删除集合，也不影响同文档其他版本。
   * 先限制位置字段白名单和 CASE/TENANT 声明，再验证各 Long 标识、索引版本及集合身份。
   * 所有过滤值均由结构化校验结果构造，不能由请求体传入一段可执行的过滤表达式。
   *
   * 集合确认不存在时视为已无可删内容并返回；存在时用 3 秒预算检查和删除。
   * 成功只表示本次删除请求被服务接受，不能保证已发出的迟到写入不会再次出现，
   * 因此 Java 的退役记录与后续复查仍不可省略。
   *
   * @param location Java 已登记并授权回收的精确位置。
   * @returns 无正文结果；位置错误、状态异常或向量服务故障均抛错交给上层处理。
   */
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
    ) {
      throw new Error('向量回收范围无效');
    }
    const tenantId = knowledgeDecimalId(location.tenantId);
    const knowledgeBaseId = knowledgeDecimalId(location.knowledgeBaseId);
    const documentId = knowledgeDecimalId(location.documentId);
    const fileVersionId = knowledgeDecimalId(location.fileVersionId);
    const caseId = milvusKnowledgeCaseId(location);
    if (
      !Number.isInteger(location.indexVersion) ||
      location.indexVersion < 1 ||
      location.indexVersion > 2147483647 ||
      MilvusKnowledgeStore.collectionName(location.modelIdentity, location.dimension) !==
        location.collectionName
    ) {
      throw new Error('向量回收位置无效');
    }
    // 精确绑定原件版本与索引版本：同一文档旧版本退役不能误删当前已发布版本。
    const filter = [
      `tenant_id == "${tenantId}"`,
      `case_id == "${caseId}"`,
      `knowledge_base_id == "${knowledgeBaseId}"`,
      `document_id == "${documentId}"`,
      `file_version_id == "${fileVersionId}"`,
      `index_version == ${location.indexVersion}`,
    ].join(' && ');
    const present = await this.client.hasCollection({
      collection_name: location.collectionName,
      timeout: 3000,
    });
    if (
      (present.status?.code !== undefined && present.status.code !== 0) ||
      (present.status?.error_code !== undefined &&
        present.status.error_code !== 'Success' &&
        present.status.error_code !== 0)
    ) {
      throw new Error('向量回收暂不可用');
    }
    if (typeof present.value !== 'boolean') {
      throw new Error('向量回收暂不可用');
    }
    if (!present.value) {
      return;
    }
    const result = await this.client.deleteEntities({
      collection_name: location.collectionName,
      filter,
      timeout: 3000,
    });
    if (
      (result.status.code !== undefined && result.status.code !== 0) ||
      (result.status.error_code !== 'Success' && result.status.error_code !== 0)
    ) {
      throw new Error('向量回收暂不可用');
    }
  }

  /**
   * 用模型身份的 SHA-256 前 16 位和维度生成稳定的 v1 集合名。
   * 模型身份应来自 embeddingIdentity，维度只接受 2–4096 的整数；无效参数直接拒绝。
   * 同维但不同服务商/地址/模型会按身份摘要分开，Key 不参与命名，不能从集合名还原凭据。
   * 名称只定位向量空间，不能代替记录级租户过滤或远端模型版本验证。
   */
  static collectionName(modelIdentity: string, dimension: number): string {
    if (
      !modelIdentity ||
      modelIdentity.length > 1000 ||
      !Number.isInteger(dimension) ||
      dimension < 2 ||
      dimension > 4096
    ) {
      throw new Error('模型版本或向量维度无效');
    }
    const digest = createHash('sha256').update(modelIdentity).digest('hex').slice(0, 16);
    return `lexflow_chunks_${digest}_${dimension}_v1`;
  }

  /**
   * 根据模型身份和实际返回维度创建或复用集合，并再次核验 embedding 字段的真实维度。
   * 新集合采用固定字段、切片 ID 主键、COSINE 自动索引和 Strong 一致性，禁止动态字段。
   * 已有集合也不能只凭名字相信维度正确；描述信息不匹配时抛错，避免错误空间继续写入。
   *
   * @returns 可供本次批次 upsert 的确定集合名；不改变 Java 的知识发布状态。
   */
  async ensureCollection(modelIdentity: string, dimension: number): Promise<string> {
    const collectionName = MilvusKnowledgeStore.collectionName(modelIdentity, dimension);
    const present = await this.client.hasCollection({ collection_name: collectionName });
    if (!present.value) {
      // 只保存来源定位字段：后续检索、版本过滤和精确退役都依赖这些字段。
      // 这里没有原文列，向量命中后必须回到 Java 才能读取授权材料。
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
      if (status.code !== undefined && status.code !== 0) {
        throw new Error('Milvus 创建 Collection 失败');
      }
    }
    const description = await this.client.describeCollection({ collection_name: collectionName });
    const vectorField = description.schema.fields.find((field) => field.name === 'embedding');
    const storedDim = Number(
      vectorField?.type_params?.find((param) => param.key === 'dim')?.value ?? vectorField?.dim,
    );
    if (storedDim !== dimension) {
      throw new Error('Milvus Collection 维度与模型不一致');
    }
    return collectionName;
  }

  /**
   * 整批校验通过后把来源元数据和向量写入集合，单批允许 1–100 条记录。
   * 每项都要验证十进制标识、明确的范围映射、索引版本以及与集合一致的向量维度。
   * 任一项无效时不发出本批 upsert；服务端报错时抛错，不把部分/未知结果伪装成成功。
   *
   * upsert 以 chunk_id 为主键，重复相同任务可覆盖同一主键；各版本 ID 的分配由 Java 负责。
   * 本函数不控制跨批事务、不重发模型调用，也不切换业务发布指针。
   */
  async upsert(collectionName: string, records: KnowledgeVector[]): Promise<void> {
    if (
      records.length === 0 ||
      records.length > 100 ||
      !/^lexflow_chunks_[a-f0-9]{16}_[0-9]+_v1$/.test(collectionName)
    ) {
      throw new Error('向量批次或 Collection 无效');
    }
    const dimension = Number(collectionName.match(/_([0-9]+)_v1$/)?.[1]);
    for (const item of records) {
      knowledgeDecimalId(item.chunkId);
      knowledgeDecimalId(item.tenantId);
      milvusKnowledgeCaseId(item);
      knowledgeDecimalId(item.knowledgeBaseId);
      knowledgeDecimalId(item.documentId);
      knowledgeDecimalId(item.fileVersionId);
      if (!Number.isInteger(item.indexVersion) || item.indexVersion < 1) {
        throw new Error('索引版本无效');
      }
      validateVector(item.embedding, dimension);
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
    if (result.status.code !== undefined && result.status.code !== 0) {
      throw new Error('Milvus 写入失败');
    }
    if (result.status.error_code !== 'Success' && result.status.error_code !== 0) {
      throw new Error('Milvus 写入失败');
    }
  }

  /**
   * 在 Java 给出的已授权文档范围内做向量召回，默认最多 10 条，可显式请求 1–50 条。
   * 先检查集合格式、查询维度、范围和版本；文档列表必须是数组，空数组直接返回 []。
   * 真正发送给 Milvus 的过滤条件是租户、案件/公共范围、知识库、索引版本和文档 ID 列表；
   * 本方法不额外按 file_version_id 过滤，原件版本与当前发布有效性仍由 Java 复核。
   *
   * @param collectionName 已按模型身份和维度定位的集合，调用控制器另会核对完整模型身份。
   * @param scope Java 授权且有界的文档列表，不支持调用方自由指定过滤表达式。
   * @param embedding 与索引模型同一空间的查询向量。
   * @param limit 命中上限，不代表一定存在这么多匹配材料。
   * @returns 切片/文档 ID 与相似分数；无匹配结果可为空，不返回原文或自动扩大范围。
   */
  async search(
    collectionName: string,
    scope: VectorScope,
    embedding: number[],
    limit = 10,
  ): Promise<VectorHit[]> {
    if (!/^lexflow_chunks_[a-f0-9]{16}_[0-9]+_v1$/.test(collectionName)) {
      throw new Error('Collection 无效');
    }
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
      throw new Error('检索条数无效');
    }
    const dimension = Number(collectionName.match(/_([0-9]+)_v1$/)?.[1]);
    validateVector(embedding, dimension);
    const tenantId = knowledgeDecimalId(scope.tenantId);
    const caseId = milvusKnowledgeCaseId(scope);
    const knowledgeBaseId = knowledgeDecimalId(scope.knowledgeBaseId);
    if (!Number.isInteger(scope.indexVersion) || scope.indexVersion < 1) {
      throw new Error('索引版本无效');
    }
    if (!Array.isArray(scope.documentIds)) {
      throw new Error('授权文档范围无效');
    }
    if (scope.documentIds.length === 0) {
      return [];
    }
    if (scope.documentIds.length > 100) {
      throw new Error('授权文档数量超过检索上限');
    }
    const documentIds = scope.documentIds.map(knowledgeDecimalId);
    // CASE 与 TENANT 通过 case_id 区分，但 TENANT 的内部值 0 仍必须与 tenant_id 一起过滤。
    // 每个 ID 已经限制为正十进制字符串，不能把任意文本插入过滤语法。
    const filter = [
      `tenant_id == "${tenantId}"`,
      `case_id == "${caseId}"`,
      `knowledge_base_id == "${knowledgeBaseId}"`,
      `index_version == ${scope.indexVersion}`,
      `document_id in [${documentIds.map((id) => `"${id}"`).join(',')}]`,
    ].join(' && ');
    const result = await this.client.search({
      collection_name: collectionName,
      data: embedding,
      anns_field: 'embedding',
      filter,
      limit,
      consistency_level: ConsistencyLevelEnum.Strong,
      output_fields: ['chunk_id', 'document_id'],
    });
    if (result.status.code !== undefined && result.status.code !== 0) {
      throw new Error('Milvus 检索失败');
    }
    return result.results.map((row) => ({
      chunkId: String(row.chunk_id),
      documentId: String(row.document_id),
      score: Number(row.score),
    }));
  }
}
