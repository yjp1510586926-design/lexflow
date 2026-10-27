import { EmbeddingConfiguration } from './knowledge-indexer';

export interface RerankInput {
  query: string;
  documents: string[];
  limit: number;
}
export interface Rank {
  index: number;
  score: number;
}
export type ModelFetch = typeof fetch;

/** 异常只携带状态码，服务商响应正文与头部可能包含材料或凭据，不向上透传。 */
export class RerankRequestError extends Error {
  constructor(readonly status: number) {
    super('排序服务请求失败');
  }
}

export class RerankResponseError extends Error {
  constructor() {
    super('排序结果格式无效');
  }
}

export function validateRerankInput(input: RerankInput): void {
  if (
    !input ||
    typeof input.query !== 'string' ||
    input.query.trim().length < 2 ||
    input.query.length > 500 ||
    !Array.isArray(input.documents) ||
    input.documents.length < 1 ||
    input.documents.length > 50 ||
    input.documents.some(
      (text) => typeof text !== 'string' || !text.trim() || text.length > 1000,
    ) ||
    !Number.isInteger(input.limit) ||
    input.limit < 1 ||
    input.limit > 10
  )
    throw new Error('排序参数无效');
}

/** 兼容 Cohere v2 的排序接口；仅返回输入文档的索引与分数，引用仍使用已授权原文。 */
export async function rerankDocuments(
  input: RerankInput,
  config: EmbeddingConfiguration,
  allowedHosts: string,
  send: ModelFetch = fetch,
  timeoutMs = 30000,
): Promise<Rank[]> {
  validateRerankInput(input);
  if (
    !config ||
    typeof config.apiKey !== 'string' ||
    !config.apiKey.trim() ||
    typeof config.model !== 'string' ||
    !config.model.trim() ||
    typeof config.provider !== 'string' ||
    !config.provider.trim()
  )
    throw new Error('排序模型配置不完整');
  let base: URL;
  try {
    base = new URL(config.baseUrl);
  } catch {
    throw new Error('排序接口地址无效');
  }
  if (
    base.protocol !== 'https:' ||
    base.username ||
    base.password ||
    base.search ||
    base.hash ||
    !allowedHosts
      .split(',')
      .map((host) => host.trim().toLowerCase())
      .filter(Boolean)
      .includes(base.hostname.toLowerCase())
  )
    throw new Error('排序接口域名尚未获得授权');
  const top = Math.min(input.limit, input.documents.length);
  const response = await send(`${base.toString().replace(/\/$/, '')}/rerank`, {
    method: 'POST',
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.apiKey}` },
    body: JSON.stringify({
      model: config.model,
      query: input.query.trim(),
      documents: input.documents,
      top_n: top,
    }),
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw new RerankRequestError(response.status);
  }
  const reader = response.body?.getReader();
  if (!reader) throw new Error('排序服务未返回结果');
  let raw = '';
  let bytes = 0;
  const decoder = new TextDecoder('utf-8', { fatal: true });
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      bytes += part.value.byteLength;
      if (bytes > 100000) throw new Error('排序结果过大');
      raw += decoder.decode(part.value, { stream: true });
    }
    raw += decoder.decode();
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new RerankResponseError();
  }
  const results = (value as { results?: unknown })?.results;
  if (!Array.isArray(results) || results.length !== top) throw new Error('排序结果数量无效');
  const used = new Set<number>();
  const ranks: Rank[] = [];
  for (const row of results) {
    if (
      !row ||
      !Number.isInteger(row.index) ||
      row.index < 0 ||
      row.index >= input.documents.length ||
      used.has(row.index) ||
      typeof row.relevance_score !== 'number' ||
      !Number.isFinite(row.relevance_score) ||
      row.relevance_score < 0 ||
      row.relevance_score > 1
    )
      throw new Error('排序结果编号或分数无效');
    used.add(row.index);
    ranks.push({ index: row.index, score: row.relevance_score });
  }
  return ranks.sort((a, b) => b.score - a.score || a.index - b.index);
}
