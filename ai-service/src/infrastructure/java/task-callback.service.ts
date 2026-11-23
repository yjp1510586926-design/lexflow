import { Injectable } from '@nestjs/common';
import { HttpService } from '@nestjs/axios';
import { ConfigService } from '@nestjs/config';
import { firstValueFrom } from 'rxjs';
import { DEFAULT_INTERNAL_SECRET, DEFAULT_JAVA_CORE_URL } from '../../config/settings';
import type { SourceSegment } from '../../documents/parsers/source-segments';
import type {
  ContractInput,
  ContractReport,
  ContractEvidence,
} from '../../workflows/contract-review/contract-review.state';
import type { KnowledgeScopeInput } from '../../knowledge/knowledge-scope';
import type {
  GenerationInput,
  GeneratedDraft,
} from '../../workflows/document-generation/document-generation.state';
import type {
  CaseAnalysisInput,
  CaseAnalysisReport,
} from '../../workflows/case-analysis/case-analysis.state';

/**
 * Java 返回的当前业务上下文，是消费者核对消息是否仍适用的依据。
 * AI 服务不直接读取 Java 业务表；文件、知识范围和任务状态的授权由 Java 接口检查。
 * 该类型只约束编译期结构，调用方仍需按具体任务核对编号、轮次及状态。
 */
export interface TaskContext extends KnowledgeScopeInput {
  /** 编号保留为字符串，避免 Java long 转为 JavaScript number 后丢失精度。 */
  taskId: string;
  tenantId: string;
  knowledgeBaseId?: string | null;
  type: string;
  status: string;
  /** 业务执行轮次；消息重投沿用原 attempt，状态流转由 stateVersion 区分。 */
  attempt: number;
  /** Java 的状态版本；begin 认领后应使用返回的新版本继续授权复查与结果回调。 */
  stateVersion: number;
  /** 任务绑定的固定来源版本，不等同于用户当前正在编辑或最新上传的版本。 */
  resourceVersionId?: string | null;
  idempotencyKey: string;
  /** 仅是 Java 返回的文件元数据，AI 端下载仍走任务专用授权接口。 */
  fileObjectKey: string;
  mimeType: string;
  /** 原始文件字节的 SHA-256，解析器需用它核对下载内容是否仍是授权来源。 */
  sha256: string;
}

/**
 * Java 本次授权的固定索引输入，绑定来源版本、索引版本、切片编号及模型配置。
 * API Key 只用于运行时调用，不得进入图检查点、消息或操作日志。
 * 与 KnowledgeIndexResult 不同，这里包含原文切片和凭据，不能作为结果回执透传。
 */
export interface KnowledgeIndexInput extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  /** Java 分配的索引版本，新版本写入期间不覆盖已有发布指针。 */
  indexVersion: number;
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  /** 配置变更身份；尽管类型兼容缺省字段，持久化索引执行前仍要求有效版本。 */
  configurationRevision?: string;
  /** 切片编号由 Java 分配，正文仅用于当前授权下的 Embedding 请求。 */
  chunks: { id: string; text: string }[];
}

/**
 * 原文解析回调的数据契约，submitResult 会把未提供的可选字段显式写为 null。
 * taskId 定位任务，attempt/stateVersion 供 Java 判断结果是否仍属于当前可接受的状态。
 * 成功时携带正文、原文件摘要与页/段偏移；失败时提供解析失败说明。
 */
export interface DocumentParseResult {
  taskId: string;
  attempt: number;
  stateVersion: number;
  success: boolean;
  resultObjectKey?: string;
  errorMessage?: string;
  parsedText?: string;
  sourceSha256?: string;
  segments?: SourceSegment[];
}

/**
 * 向量写入回执，只描述执行是否成功及写入位置，不携带 API Key 或原文切片。
 * Java 还需核对任务轮次、状态和发布资格，才会切换当前可检索的索引版本。
 * collectionName、dimension、modelIdentity 共同描述向量空间，不能只凭维度复用模型。
 */
export interface KnowledgeIndexResult {
  taskId: string;
  attempt: number;
  stateVersion: number;
  success: boolean;
  collectionName?: string;
  dimension?: number;
  indexedChunks?: number;
  modelIdentity?: string;
}

/**
 * Java Core 内部 HTTP 客户端，集中封装任务上下文、授权输入、来源下载和结果回调。
 * X-Internal-Secret 用于服务间鉴权；具体业务权限、来源版本和状态资格仍由 Java 检查。
 * 本类将 HttpService 的 Observable 转成可 await 的 Promise，HTTP 失败继续向调用方抛出，
 * 由消费者区分计算失败与投递失败，而不是在客户端里隐式重试或吞掉回调错误。
 * 本类也不替代业务输入校验，调用方须继续核对 Java 返回的数据是否匹配当前任务。
 */
@Injectable()
export class TaskCallbackService {
  private readonly baseUrl: string;
  /** 服务间共享凭据，与律所配置的模型 API Key 是两种不同用途的凭据。 */
  private readonly secret: string;

  /**
   * 读取 Java 内部接口地址与服务间凭据；这里只保存运行时配置，不发起网络请求。
   * HttpService 使用 NestJS 注入的公共配置，某些接口会按数据大小再显式覆盖请求限制。
   */
  constructor(
    private readonly http: HttpService,
    private readonly config: ConfigService,
  ) {
    this.baseUrl = config.get<string>('JAVA_CORE_URL', DEFAULT_JAVA_CORE_URL);
    this.secret = config.get<string>('INTERNAL_SECRET', DEFAULT_INTERNAL_SECRET);
  }

  /**
   * 读取 Java 的当前任务上下文，不通过此请求认领执行或获取模型输入。
   * 消费者把结果与事件中的租户、业务轮次和来源身份比较，以丢弃已过期的消息。
   *
   * @param taskId 已由上层校验的任务编号。
   * @returns Java 返回的当前状态与文件元数据，仍需按具体任务类型做一致性检查。
   */
  async getContext(taskId: string): Promise<TaskContext> {
    const response = await firstValueFrom(
      this.http.get<TaskContext>(`${this.baseUrl}/internal/v1/tasks/${taskId}/context`, {
        headers: { 'X-Internal-Secret': this.secret },
      }),
    );
    return response.data;
  }

  /**
   * 通过任务专用接口下载已授权的固定来源，不直接按 fileObjectKey 访问对象存储。
   * Java 按 attempt/stateVersion 再次校验任务资格；客户端禁止重定向，并限制为 50 MB。
   * 下载本身不验证文件摘要，调用方需将返回字节交给解析器与 task.sha256 核对。
   *
   * @param task 当前任务上下文，必须使用本轮仍有效的状态版本。
   * @returns 原始文件 Buffer，保留字节供解析器判断类型、摘要及正文。
   */
  async downloadSource(task: TaskContext): Promise<Buffer> {
    const response = await firstValueFrom(
      this.http.get<ArrayBuffer>(
        `${this.baseUrl}/internal/v1/tasks/${encodeURIComponent(task.taskId)}/source`,
        {
          headers: { 'X-Internal-Secret': this.secret },
          params: { attempt: task.attempt, stateVersion: task.stateVersion },
          responseType: 'arraybuffer',
          maxContentLength: 50 * 1024 * 1024,
          maxRedirects: 0,
          timeout: 30000,
        },
      ),
    );
    return Buffer.from(response.data);
  }

  /**
   * 回传原文解析成功或失败结果；是否接受迟到、重复或版本不匹配的结果由 Java 决定。
   * 可选字段统一写为 null，使 HTTP 数据契约不依赖 JavaScript 的 undefined 序列化行为。
   * 等待请求完成后消费者才能 ACK；HTTP 异常继续抛出，不能把回调丢失当作任务完成。
   *
   * @param params 解析结果以及对应任务轮次、状态版本。
   * @returns Java 接口已正常响应，不在本地另存业务结果。
   */
  async submitResult(params: DocumentParseResult): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/tasks/${params.taskId}/results`,
        {
          attempt: params.attempt,
          stateVersion: params.stateVersion,
          success: params.success,
          resultObjectKey: params.resultObjectKey ?? null,
          errorMessage: params.errorMessage ?? null,
          parsedText: params.parsedText ?? null,
          sourceSha256: params.sourceSha256 ?? null,
          segments: params.segments ?? null,
        },
        { headers: { 'X-Internal-Secret': this.secret } },
      ),
    );
  }

  /**
   * 获取 Java 本次重新授权的索引切片与模型配置，不信任队列里可能过期的输入快照。
   * 输入较大，单独允许最多 16 MB 响应；禁止重定向以免内部凭据流向其他地址。
   * 返回内容含 API Key，调用方只能在本次执行中使用，持久化指纹必须排除密钥。
   *
   * @param task 当前任务上下文，轮次和状态版本随请求交给 Java 再校验。
   * @returns 固定来源、索引版本、切片和运行时模型配置。
   */
  async getIndexInput(task: TaskContext): Promise<KnowledgeIndexInput> {
    const response = await firstValueFrom(
      this.http.get<KnowledgeIndexInput>(
        `${this.baseUrl}/internal/v1/knowledge-index/${encodeURIComponent(task.taskId)}/input`,
        {
          headers: { 'X-Internal-Secret': this.secret },
          params: { attempt: task.attempt, stateVersion: task.stateVersion },
          maxRedirects: 0,
          maxContentLength: 16 * 1024 * 1024,
          timeout: 30000,
        },
      ),
    );
    return response.data;
  }

  /**
   * 在实际 upsert 之前向 Java 登记精确向量位置，避免任务失败后无法定位遗留向量。
   * 只有响应明确标记 registered=true 才继续写向量，正常 HTTP 状态本身不足以证明登记完成。
   * 该登记不等于发布索引；成功回调之后仍由 Java 决定新版本是否可检索。
   *
   * @param task 当前索引任务及其业务版本。
   * @param collectionName 本次实际要写入的 Milvus 集合。
   * @param dimension 已校验的向量维度。
   * @param modelIdentity 不包含 API Key 的模型身份，用于定位正确向量空间。
   * @returns Java 已明确接受位置登记；否则抛错阻止后续写入。
   */
  async registerIndexVectors(
    task: TaskContext,
    collectionName: string,
    dimension: number,
    modelIdentity: string,
  ): Promise<void> {
    const response = await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/knowledge-index/${encodeURIComponent(task.taskId)}/vectors`,
        {
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          collectionName,
          dimension,
          modelIdentity,
        },
        {
          headers: { 'X-Internal-Secret': this.secret },
          maxRedirects: 0,
          timeout: 10000,
          maxContentLength: 1024,
        },
      ),
    );
    if (response.data?.registered !== true) {
      throw new Error('向量位置未登记');
    }
  }

  /**
   * 读取或认领合同审查输入，共用同一组轮次与状态版本约束。
   * begin=false 使用 GET 复查当前授权输入；begin=true 使用 POST 在 Java 侧认领任务。
   * 认领可能更新 stateVersion，调用方应使用返回版本继续检查权限并回传结果。
   *
   * @param task 当前审查任务身份及版本。
   * @param begin 是否执行认领，默认只读取输入。
   * @returns 固定合同来源、审查要求、当前复核决定与运行时模型配置。
   */
  async contractInput(task: TaskContext, begin = false): Promise<ContractInput> {
    const url = `${this.baseUrl}/internal/v1/contract-reviews/${encodeURIComponent(task.taskId)}/${begin ? 'begin' : 'input'}`;
    const options = {
      headers: { 'X-Internal-Secret': this.secret },
      maxRedirects: 0,
      timeout: 30000,
      maxContentLength: 8 * 1024 * 1024,
    };
    const params = { attempt: task.attempt, stateVersion: task.stateVersion };
    const response = await firstValueFrom(
      begin
        ? this.http.post<ContractInput>(url, params, options)
        : this.http.get<ContractInput>(url, { ...options, params }),
    );
    return response.data;
  }

  /**
   * 读取或认领案件分析输入；消息只定位任务，多份材料及分析要求由 Java 统一授权。
   * 与合同接口一样，begin=true 会在 Java 侧认领，后续请求必须沿用返回的当前版本。
   * 此方法不解析材料或校验引用，工作流层仍须验证返回输入与任务身份一致。
   *
   * @param task 当前案件分析任务及其执行版本。
   * @param begin 是否认领执行，默认只读取以复查权限和固定输入。
   * @returns 授权材料、分析要求、复核决定和本次运行的模型配置。
   */
  async analysisInput(task: TaskContext, begin = false): Promise<CaseAnalysisInput> {
    const url = `${this.baseUrl}/internal/v1/case-analyses/${encodeURIComponent(task.taskId)}/${begin ? 'begin' : 'input'}`;
    const options = {
      headers: { 'X-Internal-Secret': this.secret },
      maxRedirects: 0,
      timeout: 30000,
      maxContentLength: 1000000,
    };
    const params = { attempt: task.attempt, stateVersion: task.stateVersion };
    const response = await firstValueFrom(
      begin
        ? this.http.post<CaseAnalysisInput>(url, params, options)
        : this.http.get<CaseAnalysisInput>(url, { ...options, params }),
    );
    return response.data;
  }

  /**
   * 将案件分析结论交给 Java 保存，使用认领后的版本避免覆盖另一轮任务。
   * 等待人工复核、计算成功、明确失败及不确定结果具有不同业务含义，不能混用。
   * errorCode 应由调用方提供固定本地分类码；本接口不应接收供应商原始异常或正文。
   *
   * @param task 已认领并更新版本的任务上下文。
   * @param outcome 本次工作流的业务结果类别。
   * @param report 可选的经引用校验的分析报告，缺省时发送 null。
   * @param errorCode 可选的固定模型失败分类，供 Java 映射本地提示。
   * @returns Java 结果接口已正常响应，消费者随后才可 ACK。
   */
  async analysisResult(
    task: TaskContext,
    outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED' | 'UNCERTAIN',
    report?: CaseAnalysisReport,
    errorCode?: string,
  ): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/case-analyses/${encodeURIComponent(task.taskId)}/result`,
        {
          tenantId: task.tenantId,
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          outcome,
          report: report ?? null,
          errorCode,
        },
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }

  /**
   * 为合同审查取得参考知识，查询范围由 Java 按当前任务及用户选择重新授权。
   * AI 端只提交检索词与任务版本，不在这个请求里自行指定任意案件或文档范围。
   * 返回的参考原文还会由合同图约束引用编号，不能把模型自带来源混入报告。
   *
   * @param task 当前合同任务及状态版本。
   * @param query 从本次合同与审查要求中提取的检索词。
   * @returns Java 授权的参考原文集合，可能为空。
   */
  async contractEvidence(task: TaskContext, query: string): Promise<ContractEvidence[]> {
    const response = await firstValueFrom(
      this.http.post<ContractEvidence[]>(
        `${this.baseUrl}/internal/v1/contract-reviews/${encodeURIComponent(task.taskId)}/evidence`,
        { attempt: task.attempt, stateVersion: task.stateVersion, query },
        {
          headers: { 'X-Internal-Secret': this.secret },
          maxRedirects: 0,
          timeout: 180000,
          maxContentLength: 100000,
        },
      ),
    );
    return response.data;
  }

  /**
   * 回传合同审查的报告与执行状态，Java 负责保存报告并开放相应的人工复核入口。
   * WAITING_HUMAN 表示图已正常停在人工节点，不应按网络错误自动重复审查。
   * 结果接口正常返回后才可确认消息；回调失败仍由消费者安排投递恢复。
   *
   * @param task 认领后的合同任务版本。
   * @param outcome 当前图执行或模型操作的业务结果。
   * @param report 可选的已校验报告，缺省时明确发送 null。
   * @returns Java 已正常响应本次结果提交。
   */
  async contractResult(
    task: TaskContext,
    outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED' | 'UNCERTAIN',
    report?: ContractReport,
  ): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/contract-reviews/${encodeURIComponent(task.taskId)}/result`,
        {
          tenantId: task.tenantId,
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          outcome,
          report: report ?? null,
        },
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }

  /**
   * 提交索引失败或向量写入回执，不在此处操作 Milvus 或发布指针。
   * Java 依据当前任务资格决定是否发布新索引；失效任务的向量可按已登记位置回收。
   * 将成功回调放在消费者的计算异常分支之外，可避免回调失败时把已算结果误报为失败。
   *
   * @param params 索引结果、任务版本以及可选向量位置与统计。
   * @returns Java 接口正常响应；消费者收到响应后再确认消息。
   */
  async submitIndexResult(params: KnowledgeIndexResult): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/knowledge-index/${encodeURIComponent(params.taskId)}/result`,
        params,
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }

  /**
   * 读取或认领文书生成的固定输入，绑定不可变来源、案件背景及本次生成要求。
   * begin=false 用于读取或复查，begin=true 在 Java 侧认领；认领返回的新版本用于结果回调。
   * 返回值含运行时模型凭据，不应把整个 GenerationInput 保存到图状态中。
   *
   * @param task 任务编号、业务轮次和当前状态版本。
   * @param begin 是否认领执行，默认仅读取。
   * @returns 当前授权的生成输入，调用方还需核对来源与消息是否匹配。
   */
  async generationInput(
    task: { taskId: string; attempt: number; stateVersion: number },
    begin = false,
  ): Promise<GenerationInput> {
    const url = `${this.baseUrl}/internal/v1/document-generations/${encodeURIComponent(task.taskId)}/${begin ? 'begin' : 'input'}`;
    const options = {
      headers: { 'X-Internal-Secret': this.secret },
      maxRedirects: 0,
      timeout: 30000,
      maxContentLength: 400000,
    };
    const params = { attempt: task.attempt, stateVersion: task.stateVersion };
    const response = await firstValueFrom(
      begin
        ? this.http.post<GenerationInput>(url, params, options)
        : this.http.get<GenerationInput>(url, { ...options, params }),
    );
    return response.data;
  }

  /**
   * 把生成结果提交给 Java，保存为独立待复核草稿，不直接覆盖当前人工正文。
   * 草稿正文和待补信息分别传输；没有草稿时两者均为 null，仍可提交明确失败状态。
   * 本方法只回传结果，不调用模型，也不把输入中的 API Key 发回业务结果接口。
   *
   * @param task 已认领的生成输入，只从中取任务身份和版本用于回调。
   * @param outcome 生成成功、明确失败或结果不确定的业务状态。
   * @param draft 可选的已校验草稿与待补信息。
   * @returns Java 结果接口正常响应，之后消费者才可 ACK。
   */
  async generationResult(
    task: GenerationInput,
    outcome: 'SUCCEEDED' | 'FAILED' | 'UNCERTAIN',
    draft?: GeneratedDraft,
  ): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/document-generations/${encodeURIComponent(task.taskId)}/result`,
        {
          tenantId: task.tenantId,
          attempt: task.attempt,
          stateVersion: task.stateVersion,
          outcome,
          content: draft?.content ?? null,
          missingInformation: draft?.missingInformation ?? null,
        },
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }
}
