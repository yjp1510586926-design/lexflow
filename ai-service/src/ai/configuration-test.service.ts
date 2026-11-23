import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { HumanMessage, SystemMessage } from '@langchain/core/messages';
import { Pool } from 'mysql2/promise';
import { createHash } from 'node:crypto';
import { allowedModelHosts, workflowDatabaseUrl } from '../config/settings';
import type { ModelConfiguration } from './models/model-configuration';
import { EmbeddingModel, configuredEmbeddings } from './models/embedding-model';
import { Rank, rerankDocuments } from './models/rerank-model';
import { QuestionModel, configuredQuestionModel } from './models/legal-model';
import { workflowPool } from '../infrastructure/persistence/workflow-pool';
import {
  configurationTestThreadId,
  TenantMySqlCheckpointer,
  WorkflowBusyError,
  WorkflowOperationFailedError,
  WorkflowOperationConflictError,
} from '../infrastructure/persistence/mysql-checkpointer';

/**
 * Java 创建并授权的一次主动连接测试，继承本次连接所需的模型配置。
 * tenantId + testId 确定测试线程，purpose 决定调用哪一类接口。
 * revision 由 Java 在配置变更时更新；用版本识别配置替换，避免把密钥写入输入摘要。
 */
export interface ConfigurationTestInput extends ModelConfiguration {
  tenantId: string;
  /** Java 生成的正整数十进制 ID；与下方 UUID 格式的 revision 是两种不同标识。 */
  testId: string;
  purpose: 'LLM' | 'EMBEDDING' | 'RERANK';
  revision: string;
}
/**
 * 对外只返回连接状态、耗时与有限诊断信息，不包含供应商响应正文或向量。
 * SUCCEEDED 表示探针响应满足协议；FAILED 表示响应不合法或已知请求失败。
 * UNKNOWN 表示无法确定远程是否执行，不能据此自动重新发起付费请求。
 * PROCESSING 表示相同测试线程正在被其他执行者处理，不代表本次新发了请求。
 */
export interface ConfigurationTestResult {
  status: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'PROCESSING';
  elapsedMs: number;
  /** 仅成功的 Embedding 探针返回；不将真实向量暴露给 Java 或前端。 */
  dimension?: number;
  /** 固定本地错误码，不复制供应商异常文本。 */
  errorCode?: string;
}
const OPERATION_KEY = 'connection_probe_v1';
// UUID 只校验配置 revision；测试线程使用的 testId 由存储器按 Java Long 范围校验。
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const SYNTHETIC_TEXT = '这是连接测试的合成文本，不包含案件或个人资料。';

/**
 * 在访问工作流存储、构造模型和发送请求之前，重新校验 Java 内部请求。
 * 依次校验对象结构、租户/测试 ID、用途/版本、连接字段，再检查 HTTPS 与精确域名白名单。
 * 成功后返回只复制已知字段的新对象；规范化版本、供应商、地址和模型名，不改写 API Key。
 *
 * @param input 未信任的 HTTP 请求对象，可能含错误类型和额外字段。
 * @param allowedHosts 本次校验使用的部署域名白名单，空列表会拒绝接口地址。
 * @returns 已校验、已规范化的探针输入；密钥仅供运行时模型使用。
 * @throws 任何字段或域名不符合要求时抛出本地错误，不请求模型。
 */
export function validateConfigurationTest(
  input: unknown,
  allowedHosts: string,
): ConfigurationTestInput {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new Error('连接测试参数无效');
  }
  const value = input as ConfigurationTestInput;
  configurationTestThreadId(value.tenantId, value.testId);
  if (
    !['LLM', 'EMBEDDING', 'RERANK'].includes(value.purpose) ||
    typeof value.revision !== 'string' ||
    !UUID.test(value.revision)
  ) {
    throw new Error('连接测试参数无效');
  }
  for (const [field, maxLength] of [
    ['provider', 80],
    ['baseUrl', 500],
    ['model', 120],
    ['apiKey', 8192],
  ] as const) {
    if (
      typeof value[field] !== 'string' ||
      !value[field].trim() ||
      value[field].length > maxLength ||
      /[\u0000-\u001f\u007f]/.test(value[field])
    ) {
      throw new Error('连接测试参数无效');
    }
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
  ) {
    throw new Error('连接测试接口域名尚未获得部署环境授权');
  }
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

/**
 * 计算单次探针操作的输入指纹，用于判断已保存结果是否仍属于同一配置。
 * 数组字段和顺序是操作身份的一部分；替换密钥后由 Java 更新 revision 来使旧结果失效。
 * tenantId/testId 已通过线程身份隔离，所以此摘要仅包含用途与模型配置身份，不包含 Key。
 */
export function configurationTestInputHash(input: ConfigurationTestInput): string {
  return createHash('sha256')
    .update(
      JSON.stringify([input.purpose, input.revision, input.provider, input.baseUrl, input.model]),
    )
    .digest('hex');
}

/** 区分“收到了不兼容的响应”和“请求是否执行无法确定”，不附带原始响应正文。 */
class InvalidProbeResponse extends Error {}

/** 耗时只用于展示，将时钟回拨或异常大值限制在固定范围内。 */
function elapsed(startedAt: number): number {
  return Math.min(120000, Math.max(0, Date.now() - startedAt));
}

/**
 * LLM 的最小协议探针：只接受长度受限且恰好包含 {"ok":true} 的 JSON 对象。
 * 有额外字段、数组、非法 JSON 或错误值都视为响应不兼容；不评价实际法律回答质量。
 * @throws InvalidProbeResponse，交由 probe 转为固定的 RESPONSE_INVALID 结果。
 */
function validateLlmProbeResponse(content: unknown): void {
  if (typeof content !== 'string' || content.length > 256) {
    throw new InvalidProbeResponse();
  }
  let value: unknown;
  try {
    value = JSON.parse(content);
  } catch {
    throw new InvalidProbeResponse();
  }
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).length !== 1 ||
    (value as { ok?: unknown }).ok !== true
  ) {
    throw new InvalidProbeResponse();
  }
}

/**
 * 校验一段合成文本对应的一条向量：维度须为 2–4096，每维为有限数且向量不能全零。
 * 这些检查只能验证接口基本兼容性，不能证明模型适合真实材料检索。
 * @returns 合法向量的维度；不返回向量本身给测试接口调用者。
 * @throws InvalidProbeResponse，向量数量、维度或数值不符合协议时抛出。
 */
function validateEmbeddingProbeResponse(vectors: number[][]): number {
  if (
    !Array.isArray(vectors) ||
    vectors.length !== 1 ||
    !Array.isArray(vectors[0]) ||
    vectors[0].length < 2 ||
    vectors[0].length > 4096 ||
    vectors[0].some((value) => typeof value !== 'number' || !Number.isFinite(value)) ||
    !vectors[0].some((value) => value !== 0)
  ) {
    throw new InvalidProbeResponse();
  }
  return vectors[0].length;
}

/**
 * 两条合成文档必须恰好各出现一次，返回索引只能为 0 和 1，分数须为 0–1 的有限值。
 * 排序顺序可以不同，但重复、越界或遗漏编号不能被当作兼容响应。
 * 本函数只判断结果形状，不评价排序的实际相关性质量。
 */
function validateRerankProbeResponse(ranks: Rank[]): void {
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
  ) {
    throw new InvalidProbeResponse();
  }
}

/**
 * 把已授权输入封装成一次可延后执行的连接探针，不读取真实案件或知识索引。
 * 创建阶段校验输入并装配适配器；只有调用返回的 probe() 才会发送外部请求。
 * LLM 使用 32 输出 token，三种适配器均使用 15 秒超时，且不执行隐式重试。
 * 回调把结果转换为本地状态，既不返回供应商正文，也不根据超时推断“远程未执行”。
 *
 * @param raw 待校验的连接测试输入。
 * @param allowedHosts 同时用于校验与适配器构造的白名单，避免两步使用不同允许范围。
 * @returns 规范化输入与尚未执行的探针函数，供持久化操作日志包裹调用。
 */
export function createConfigurationProbe(
  raw: unknown,
  allowedHosts: string,
): {
  input: ConfigurationTestInput;
  probe: () => Promise<ConfigurationTestResult>;
} {
  const input = validateConfigurationTest(raw, allowedHosts);
  // 将本次校验的白名单显式传入适配器，避免后续读取环境变化而扩大允许范围。
  let llm: QuestionModel | undefined;
  let embeddings: EmbeddingModel | undefined;
  if (input.purpose === 'LLM') {
    llm = configuredQuestionModel(input, allowedHosts, { maxTokens: 32, timeout: 15000 });
  } else if (input.purpose === 'EMBEDDING') {
    embeddings = configuredEmbeddings(input, allowedHosts, { batchSize: 1, timeout: 15000 });
  }
  return {
    input,
    probe: async () => {
      const startedAt = Date.now();
      try {
        // 按用途只走一条分支；Embedding 额外返回维度，LLM/Rerank 不返回模型正文。
        if (llm) {
          const result = await llm.invoke(
            [
              new SystemMessage('仅用于模型连接测试。只返回 JSON 对象 {"ok":true}，不添加解释。'),
              new HumanMessage(SYNTHETIC_TEXT),
            ],
            { response_format: { type: 'json_object' } },
          );
          validateLlmProbeResponse(result.content);
        } else if (embeddings) {
          const vectors = await embeddings.embedDocuments([SYNTHETIC_TEXT]);
          const dimension = validateEmbeddingProbeResponse(vectors);
          return { status: 'SUCCEEDED', elapsedMs: elapsed(startedAt), dimension };
        } else {
          const ranks = await rerankDocuments(
            {
              query: '连接测试文本',
              documents: [SYNTHETIC_TEXT, '另一段连接测试合成文本。'],
              limit: 2,
            },
            input,
            allowedHosts,
            fetch,
            15000,
          );
          validateRerankProbeResponse(ranks);
        }
        return { status: 'SUCCEEDED', elapsedMs: elapsed(startedAt) };
      } catch (error) {
        // 服务商异常消息也可能含凭据，结果只返回固定状态与错误码。
        // 收到但不兼容的响应与明确 HTTP 失败归为 FAILED；其他异常保守归为 UNKNOWN。
        if (
          error instanceof InvalidProbeResponse ||
          (error instanceof Error && /^(?:排序结果|排序服务未返回结果)/.test(error.message))
        ) {
          return { status: 'FAILED', elapsedMs: elapsed(startedAt), errorCode: 'RESPONSE_INVALID' };
        }
        const status = (error as { status?: unknown })?.status;
        if (
          typeof status === 'number' &&
          Number.isInteger(status) &&
          status >= 400 &&
          status <= 599
        ) {
          return { status: 'FAILED', elapsedMs: elapsed(startedAt), errorCode: 'REQUEST_FAILED' };
        }
        return { status: 'UNKNOWN', elapsedMs: elapsed(startedAt), errorCode: 'RESULT_UNCERTAIN' };
      }
    },
  };
}

/**
 * 将探针放入独立测试线程的执行锁与 runOnce 日志中，避免重复请求直接重复付费。
 * lft 前缀与普通任务的 lf 前缀隔离；操作键固定，输入指纹阻止不同配置复用旧结果。
 * 已保存结果直接复用；遗留 STARTED 说明结果不确定，不重新请求服务商。
 * 注意 probe 返回 FAILED/UNKNOWN 也是一个已返回的业务结果，会被 runOnce 保存并复用；
 * 它与 runOnce 自身因异常抛出的“操作失败”属于不同层次。
 *
 * @param input 已校验的测试身份与模型配置。
 * @param store 绑定当前租户的持久化存储器。
 * @param probe 尚未执行的外部探针，不能在进入 runOnce 之前提前调用。
 * @returns 已保存或本次执行的探针结果；锁忙、输入冲突等转成固定的接口状态。
 */
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
    if (error instanceof WorkflowBusyError) {
      return { status: 'PROCESSING', elapsedMs: 0, errorCode: 'TEST_BUSY' };
    }
    if (error instanceof WorkflowOperationFailedError) {
      return { status: 'FAILED', elapsedMs: 0, errorCode: 'REQUEST_FAILED' };
    }
    if (error instanceof WorkflowOperationConflictError) {
      return { status: 'FAILED', elapsedMs: 0, errorCode: 'INPUT_CONFLICT' };
    }
    return { status: 'UNKNOWN', elapsedMs: 0, errorCode: 'RESULT_UNCERTAIN' };
  }
}

/**
 * NestJS 管理的连接测试执行服务，连接 HTTP 入口、模型适配器与独立 AI 数据库。
 * 连接池按需创建并复用，模块销毁时释放；每次执行都建立绑定当前租户的存储器。
 * Java 负责测试的业务领取、费用确认与配置权限，本服务负责执行边界与结果持久化。
 */
@Injectable()
export class ConfigurationTestService implements OnModuleDestroy {
  private pool?: Pool;

  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }

  async test(raw: unknown): Promise<ConfigurationTestResult> {
    // 先构造探针再访问存储；参数错误直接传播，存储阶段故障则返回结果不确定。
    const { input, probe } = createConfigurationProbe(raw, allowedModelHosts());
    try {
      this.pool ??= workflowPool(workflowDatabaseUrl());
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
