/**
 * LLM、Embedding 和 Rerank 共用的供应商连接参数，沿用内部请求的字段名称。
 * API Key 只用于建立运行时调用，输入指纹应使用供应商身份和配置版本。
 */
export interface ModelConfiguration {
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
}
