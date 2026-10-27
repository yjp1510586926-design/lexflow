import { Injectable } from '@nestjs/common';
import { HttpService } from '@nestjs/axios';
import { ConfigService } from '@nestjs/config';
import { firstValueFrom } from 'rxjs';
import { SourceSegment } from '../parsers/source-segments';
import type {
  ContractInput,
  ContractReport,
  ContractEvidence,
} from '../graphs/contract-review.graph';
import type { KnowledgeScopeInput } from '../rag/knowledge-scope';
import type { GenerationInput, GeneratedDraft } from '../graphs/document-generation.graph';
import type { CaseAnalysisInput, CaseAnalysisReport } from '../graphs/case-analysis.graph';

export interface TaskContext extends KnowledgeScopeInput {
  taskId: string;
  tenantId: string;
  knowledgeBaseId?: string | null;
  type: string;
  status: string;
  attempt: number;
  stateVersion: number;
  resourceVersionId?: string | null;
  idempotencyKey: string;
  fileObjectKey: string;
  mimeType: string;
  sha256: string;
}

export interface KnowledgeIndexInput extends KnowledgeScopeInput {
  tenantId: string;
  knowledgeBaseId: string;
  documentId: string;
  fileVersionId: string;
  indexVersion: number;
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  configurationRevision?: string;
  chunks: { id: string; text: string }[];
}

/**
 * 与 Java Core 内部接口通信的服务：
 * - 获取任务上下文（含文件元数据）
 * - 回传处理结果（幂等，attempt+stateVersion 校验）
 */
@Injectable()
export class TaskCallbackService {
  private readonly baseUrl: string;
  private readonly secret: string;

  constructor(
    private readonly http: HttpService,
    private readonly config: ConfigService,
  ) {
    this.baseUrl = config.get<string>('JAVA_CORE_URL', 'http://127.0.0.1:8080');
    this.secret = config.get<string>('INTERNAL_SECRET', 'dev-internal-secret-change-in-prod');
  }

  async getContext(taskId: string): Promise<TaskContext> {
    const res = await firstValueFrom(
      this.http.get<TaskContext>(`${this.baseUrl}/internal/v1/tasks/${taskId}/context`, {
        headers: { 'X-Internal-Secret': this.secret },
      }),
    );
    return res.data;
  }

  // 通过任务专用接口取固定版本的原文，由 Java 再次校验 attempt 和 stateVersion。
  async downloadSource(task: TaskContext): Promise<Buffer> {
    const res = await firstValueFrom(
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
    return Buffer.from(res.data);
  }

  async submitResult(params: {
    taskId: string;
    attempt: number;
    stateVersion: number;
    success: boolean;
    resultObjectKey?: string;
    errorMessage?: string;
    parsedText?: string;
    sourceSha256?: string;
    segments?: SourceSegment[];
  }): Promise<void> {
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

  async getIndexInput(task: TaskContext): Promise<KnowledgeIndexInput> {
    const res = await firstValueFrom(
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
    return res.data;
  }

  // 写向量前先登记准确位置，即使后续任务失败也能按该位置回收遗留向量。
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
    if (response.data?.registered !== true) throw new Error('向量位置未登记');
  }

  // begin 请求在 Java 侧认领任务；返回的新 stateVersion 必须用于后续授权检查和结果回调。
  async contractInput(task: TaskContext, begin = false): Promise<ContractInput> {
    const url = `${this.baseUrl}/internal/v1/contract-reviews/${encodeURIComponent(task.taskId)}/${begin ? 'begin' : 'input'}`;
    const options = {
      headers: { 'X-Internal-Secret': this.secret },
      maxRedirects: 0,
      timeout: 30000,
      maxContentLength: 8 * 1024 * 1024,
    };
    const params = { attempt: task.attempt, stateVersion: task.stateVersion };
    const res = await firstValueFrom(
      begin
        ? this.http.post<ContractInput>(url, params, options)
        : this.http.get<ContractInput>(url, { ...options, params }),
    );
    return res.data;
  }

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
  async analysisResult(
    task: TaskContext,
    outcome: 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED' | 'UNCERTAIN',
    report?: CaseAnalysisReport,
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
        },
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }

  async contractEvidence(task: TaskContext, query: string): Promise<ContractEvidence[]> {
    const res = await firstValueFrom(
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
    return res.data;
  }

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

  async submitIndexResult(params: {
    taskId: string;
    attempt: number;
    stateVersion: number;
    success: boolean;
    collectionName?: string;
    dimension?: number;
    indexedChunks?: number;
    modelIdentity?: string;
  }): Promise<void> {
    await firstValueFrom(
      this.http.post(
        `${this.baseUrl}/internal/v1/knowledge-index/${encodeURIComponent(params.taskId)}/result`,
        params,
        { headers: { 'X-Internal-Secret': this.secret }, maxRedirects: 0, timeout: 30000 },
      ),
    );
  }

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
