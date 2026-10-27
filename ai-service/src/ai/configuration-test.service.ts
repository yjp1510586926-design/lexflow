import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { HumanMessage, SystemMessage } from '@langchain/core/messages';
import { Pool } from 'mysql2/promise';
import { createHash } from 'node:crypto';
import {
  EmbeddingConfiguration,
  EmbeddingModel,
  configuredEmbeddings,
} from '../rag/knowledge-indexer';
import { Rank, RerankInput, rerankDocuments } from '../rag/knowledge-reranker';
import { QuestionModel, configuredQuestionModel } from './legal-question';
import { workflowPool } from '../graphs/persistence/workflow-pool';
import {
  configurationTestThreadId,
  TenantMySqlCheckpointer,
  WorkflowBusyError,
  WorkflowOperationFailedError,
  WorkflowOperationConflictError,
} from '../graphs/persistence/mysql-checkpointer';

export interface ConfigurationTestInput extends EmbeddingConfiguration {
  tenantId: string;
  testId: string;
  purpose: 'LLM' | 'EMBEDDING' | 'RERANK';
  revision: string;
}
export interface ConfigurationTestResult {
  status: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'PROCESSING';
  elapsedMs: number;
  dimension?: number;
  errorCode?: string;
}
export interface ConfigurationProbeAdapters {
  llm: (config: EmbeddingConfiguration) => QuestionModel;
  embeddings: (config: EmbeddingConfiguration) => EmbeddingModel;
  rerank: (input: RerankInput, config: EmbeddingConfiguration, hosts: string) => Promise<Rank[]>;
}

const OPERATION_KEY = 'connection_probe_v1';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const SYNTHETIC_TEXT = '这是连接测试的合成文本，不包含案件或个人资料。';
function currentHosts(): string {
  return process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '';
}

/** 内部 Java 请求也需再次校验，验证通过后才访问日志存储或发起付费探针。 */
export function validateConfigurationTest(
  input: unknown,
  allowedHosts: string,
): ConfigurationTestInput {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    throw new Error('连接测试参数无效');
  const value = input as ConfigurationTestInput;
  configurationTestThreadId(value.tenantId, value.testId);
  if (
    !['LLM', 'EMBEDDING', 'RERANK'].includes(value.purpose) ||
    typeof value.revision !== 'string' ||
    !UUID.test(value.revision)
  )
    throw new Error('连接测试参数无效');
  for (const [field, max] of [
    ['provider', 80],
    ['baseUrl', 500],
    ['model', 120],
    ['apiKey', 8192],
  ] as const) {
    if (
      typeof value[field] !== 'string' ||
      !value[field].trim() ||
      value[field].length > max ||
      /[\u0000-\u001f\u007f]/.test(value[field])
    )
      throw new Error('连接测试参数无效');
  }
  let url: URL;
  try {
    url = new URL(value.baseUrl);
  } catch {
    throw new Error('连接测试参数无效');
  }
  if (
    url.protocol !== 'https:' ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    !allowedHosts
      .split(',')
      .map((host) => host.trim().toLowerCase())
      .filter(Boolean)
      .includes(url.hostname.toLowerCase())
  )
    throw new Error('连接测试接口域名尚未获得部署环境授权');
  // 仅复制已知字段，密钥只保留在探针闭包中，不写入日志。
  return {
    tenantId: value.tenantId,
    testId: value.testId,
    purpose: value.purpose,
    revision: value.revision.toLowerCase(),
    provider: value.provider.trim(),
    baseUrl: url.toString().replace(/\/$/, ''),
    model: value.model.trim(),
    apiKey: value.apiKey,
  };
}

/** 替换密钥时 Java 会更新配置版本，指纹使用版本辨别配置变化，不包含密钥本身。 */
export function configurationTestInputHash(input: ConfigurationTestInput): string {
  return createHash('sha256')
    .update(
      JSON.stringify([input.purpose, input.revision, input.provider, input.baseUrl, input.model]),
    )
    .digest('hex');
}

class InvalidProbeResponse extends Error {}
function elapsed(started: number): number {
  return Math.min(120000, Math.max(0, Date.now() - started));
}

/** 探针使用合成文本与受限预算，只验证连接及输出格式，不读取业务材料或索引。 */
export function createConfigurationProbe(
  raw: unknown,
  allowedHosts: string,
  overrides: Partial<ConfigurationProbeAdapters> = {},
): {
  input: ConfigurationTestInput;
  probe: () => Promise<ConfigurationTestResult>;
} {
  const input = validateConfigurationTest(raw, allowedHosts);
  // 将本次校验的白名单显式传入适配器，避免后续读取环境变化而扩大允许范围。
  const llm =
    input.purpose === 'LLM'
      ? overrides.llm
        ? overrides.llm(input)
        : configuredQuestionModel(input, allowedHosts, { maxTokens: 32, timeout: 15000 })
      : undefined;
  const embeddings =
    input.purpose === 'EMBEDDING'
      ? overrides.embeddings
        ? overrides.embeddings(input)
        : configuredEmbeddings(input, allowedHosts, { batchSize: 1, timeout: 15000 })
      : undefined;
  const rank =
    overrides.rerank ??
    ((query, config, hosts) => rerankDocuments(query, config, hosts, fetch, 15000));
  return {
    input,
    probe: async () => {
      const started = Date.now();
      try {
        if (llm) {
          const result = await llm.invoke(
            [
              new SystemMessage('仅用于模型连接测试。只返回 JSON 对象 {"ok":true}，不添加解释。'),
              new HumanMessage(SYNTHETIC_TEXT),
            ],
            { response_format: { type: 'json_object' } },
          );
          if (typeof result.content !== 'string' || result.content.length > 256)
            throw new InvalidProbeResponse();
          let value: unknown;
          try {
            value = JSON.parse(result.content);
          } catch {
            throw new InvalidProbeResponse();
          }
          if (
            !value ||
            typeof value !== 'object' ||
            Array.isArray(value) ||
            Object.keys(value).length !== 1 ||
            (value as { ok?: unknown }).ok !== true
          )
            throw new InvalidProbeResponse();
        } else if (embeddings) {
          const vectors = await embeddings.embedDocuments([SYNTHETIC_TEXT]);
          if (
            !Array.isArray(vectors) ||
            vectors.length !== 1 ||
            !Array.isArray(vectors[0]) ||
            vectors[0].length < 2 ||
            vectors[0].length > 4096 ||
            vectors[0].some((value) => typeof value !== 'number' || !Number.isFinite(value)) ||
            !vectors[0].some((value) => value !== 0)
          )
            throw new InvalidProbeResponse();
          return { status: 'SUCCEEDED', elapsedMs: elapsed(started), dimension: vectors[0].length };
        } else {
          const ranks = await rank(
            {
              query: '连接测试文本',
              documents: [SYNTHETIC_TEXT, '另一段连接测试合成文本。'],
              limit: 2,
            },
            input,
            allowedHosts,
          );
          if (
            !Array.isArray(ranks) ||
            ranks.length !== 2 ||
            new Set(ranks.map((rank) => rank.index)).size !== 2 ||
            ranks.some(
              (rank) =>
                ![0, 1].includes(rank.index) ||
                !Number.isFinite(rank.score) ||
                rank.score < 0 ||
                rank.score > 1,
            )
          )
            throw new InvalidProbeResponse();
        }
        return { status: 'SUCCEEDED', elapsedMs: elapsed(started) };
      } catch (error) {
        // 服务商异常消息也可能含凭据，结果只返回固定状态与错误码。
        if (
          error instanceof InvalidProbeResponse ||
          (error instanceof Error && /^(?:排序结果|排序服务未返回结果)/.test(error.message))
        )
          return { status: 'FAILED', elapsedMs: elapsed(started), errorCode: 'RESPONSE_INVALID' };
        const status = (error as { status?: unknown })?.status;
        if (
          typeof status === 'number' &&
          Number.isInteger(status) &&
          status >= 400 &&
          status <= 599
        )
          return { status: 'FAILED', elapsedMs: elapsed(started), errorCode: 'REQUEST_FAILED' };
        return { status: 'UNKNOWN', elapsedMs: elapsed(started), errorCode: 'RESULT_UNCERTAIN' };
      }
    },
  };
}

/** 重复投递复用已保存结果；遗留 STARTED 视为不确定，不再次请求服务商。 */
export async function executeConfigurationTest(
  input: ConfigurationTestInput,
  store: TenantMySqlCheckpointer,
  probe: () => Promise<ConfigurationTestResult>,
): Promise<ConfigurationTestResult> {
  const config = {
    configurable: {
      tenant_id: input.tenantId,
      thread_id: configurationTestThreadId(input.tenantId, input.testId),
      checkpoint_ns: '',
    },
  };
  try {
    return await store.withThreadLock(config, () =>
      store.runOnce(config, OPERATION_KEY, configurationTestInputHash(input), probe),
    );
  } catch (error) {
    if (error instanceof WorkflowBusyError)
      return { status: 'PROCESSING', elapsedMs: 0, errorCode: 'TEST_BUSY' };
    if (error instanceof WorkflowOperationFailedError)
      return { status: 'FAILED', elapsedMs: 0, errorCode: 'REQUEST_FAILED' };
    if (error instanceof WorkflowOperationConflictError)
      return { status: 'FAILED', elapsedMs: 0, errorCode: 'INPUT_CONFLICT' };
    return { status: 'UNKNOWN', elapsedMs: 0, errorCode: 'RESULT_UNCERTAIN' };
  }
}

@Injectable()
export class ConfigurationTestService implements OnModuleDestroy {
  private pool?: Pool;
  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }
  async test(raw: unknown): Promise<ConfigurationTestResult> {
    const { input, probe } = createConfigurationProbe(raw, currentHosts());
    try {
      this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
      return await executeConfigurationTest(
        input,
        new TenantMySqlCheckpointer(this.pool, input.tenantId),
        probe,
      );
    } catch {
      return { status: 'UNKNOWN', elapsedMs: 0, errorCode: 'RESULT_UNCERTAIN' };
    }
  }
}
