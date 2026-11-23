import { ChatOpenAI } from '@langchain/openai';
import type { SystemMessage, HumanMessage } from '@langchain/core/messages';
import type { ModelConfiguration } from './model-configuration';

/**
 * 图工作流与问答共用的最小模型接口，只声明业务流程需要的调用与返回字段。
 * content 保持 unknown，因为供应商响应可能不是字符串，不能依赖编译期类型。
 * response_metadata 供案件分析识别输出截断等停止原因，不能直接当作可信业务结果。
 */
export interface QuestionModel {
  invoke(
    messages: (SystemMessage | HumanMessage)[],
    options: { response_format: { type: 'json_object' } },
  ): Promise<{ content: unknown; response_metadata?: unknown }>;
}

/**
 * 根据本次运行时配置构造 ChatOpenAI，创建实例本身不会发送模型请求。
 * 先校验连接信息，再要求 HTTPS 与精确域名白名单匹配；不接受 URL 内嵌账号、查询或片段。
 * 网络层拒绝重定向，避免获准域名将携带凭据的请求转交到其他地址。
 * SDK 自动重试关闭；有状态任务是否可以复用或重发，由上层持久化操作日志判断。
 *
 * @param config Java 提供的当前模型连接参数。
 * @param allowedHosts 部署环境明确允许的域名列表，以逗号分隔；空列表拒绝所有域名。
 * @param limits 输出 token 预算和超时毫秒数，主动连接探针会传入更小的预算。
 * @returns 可由问答或工作流调用的模型实例，API Key 仅保留在运行时配置中。
 */
export function configuredQuestionModel(
  config: ModelConfiguration,
  allowedHosts: string,
  limits: { maxTokens: number; timeout: number } = { maxTokens: 1800, timeout: 60000 },
): ChatOpenAI {
  if (!config || !config.apiKey?.trim() || !config.model?.trim() || !config.provider?.trim()) {
    throw new Error('未配置 LLM 服务商、模型或 API Key');
  }
  let url: URL;
  try {
    url = new URL(config.baseUrl);
  } catch {
    throw new Error('LLM 接口地址无效');
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
      .includes(url.hostname.toLowerCase())
  ) {
    throw new Error('LLM 接口域名尚未获得部署环境授权');
  }
  return new ChatOpenAI({
    model: config.model,
    apiKey: config.apiKey,
    maxTokens: limits.maxTokens,
    // DeepSeek 默认思考模式会占用输出预算，关闭它以满足连接探针的 32 token 上限。
    ...(url.hostname.toLowerCase() === 'api.deepseek.com'
      ? { modelKwargs: { thinking: { type: 'disabled' } } }
      : {}),
    // 关闭 SDK 隐式重试，工作流以持久化操作日志控制付费调用的复用与不确定状态。
    timeout: limits.timeout,
    maxRetries: 0,
    configuration: {
      baseURL: url.toString().replace(/\/$/, ''),
      fetch: (input, init) => fetch(input, { ...init, redirect: 'error' }),
    },
  });
}
