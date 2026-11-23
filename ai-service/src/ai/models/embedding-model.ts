import { OpenAIEmbeddings } from '@langchain/openai';
import type { ModelConfiguration } from './model-configuration';

/**
 * 向量生成的最小依赖，既可由真实 SDK 实现，也可由持久化包装器返回已保存的批次结果。
 * 返回数组需与输入顺序对应；调用方校验数量、维度和数值，但无法仅靠数值证明语义正确。
 */
export interface EmbeddingModel {
  embedDocuments(texts: string[]): Promise<number[][]>;
}

// 例如 51 段按 50+1 分为两个批次，对应操作键末尾的 0、1。
// 批次边界影响摘要与缓存复用；执行和恢复检查必须共用该值，不能单独调整一端。
export const KNOWLEDGE_EMBEDDING_BATCH_SIZE = 50;

/**
 * 校验部署允许的地址并创建 Embedding 客户端；创建本身不发起模型请求。
 * 要求 Key、模型和服务商非空，基础地址为无账号、查询和片段参数的 HTTPS URL，
 * 且 hostname 与 allowedHosts 中某项精确匹配，不自动授权其子域名或重定向目标。
 *
 * 默认每批 50 段、30 秒超时，禁用 SDK 自动重试；上层 runOnce 决定已付费结果能否复用。
 * 客户端禁止跟随重定向，避免合法白名单地址将携带凭据的请求转到其他地址。
 *
 * @param config Java 重新授权的运行时配置，apiKey 只保留在客户端实例中。
 * @param allowedHosts 部署环境提供的逗号分隔域名，不由模型配置自行放宽。
 * @param limits 默认调用预算，连接探针等专用入口可传入更小的批次或超时。
 * @returns 配好的 SDK 实例；配置不完整、地址不合法或域名未授权均抛错。
 */
export function configuredEmbeddings(
  config: ModelConfiguration,
  allowedHosts: string,
  limits: { batchSize: number; timeout: number } = {
    batchSize: KNOWLEDGE_EMBEDDING_BATCH_SIZE,
    timeout: 30000,
  },
): OpenAIEmbeddings {
  if (!config.apiKey?.trim() || !config.model?.trim() || !config.provider?.trim()) {
    throw new Error('未配置 Embedding 服务商、模型或 API Key');
  }
  let url: URL;
  try {
    url = new URL(config.baseUrl);
  } catch {
    throw new Error('Embedding 接口地址无效');
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
    throw new Error('Embedding 接口必须使用无账号参数的 HTTPS 地址');
  }
  const hosts = allowedHosts
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  if (!hosts.includes(url.hostname.toLowerCase())) {
    throw new Error('Embedding 接口域名尚未获得部署环境授权');
  }
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
