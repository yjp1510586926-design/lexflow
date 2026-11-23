import type { ModelConfiguration } from './model-configuration';

/**
 * Java 再次授权后的候选原文及查询；候选顺序是后续返回 index 的唯一映射基准。
 * 重排只重新排序现有候选，不访问新知识库、不替换原文，也不直接生成法律回答。
 */
export interface RerankInput {
  /** 去除首尾空白后发送；原始长度最多 500 个 UTF-16 码元，至少两个有效字符。 */
  query: string;
  /** 1–50 段已授权原文，每段非空且最多 1000 个 UTF-16 码元。 */
  documents: string[];
  /** 请求最多返回 1–10 条，真正 top_n 取它与候选数的较小值。 */
  limit: number;
}
/**
 * 受限的重排输出：index 是本次 documents 的零基下标，score 必须为有限的 0–1 数值。
 * 原文、权限和引用编号仍由 Java 维护，不能把供应商回传文档当成新的证据来源。
 */
export interface Rank {
  index: number;
  score: number;
}
export type ModelFetch = typeof fetch;

/**
 * 远端 HTTP 非成功状态，只有 status 可用于上层分类。
 * 供应商响应正文、头部或 URL 可能含材料与凭据，因此不复制到这个异常中。
 */
export class RerankRequestError extends Error {
  constructor(readonly status: number) {
    super('排序服务请求失败');
  }
}

/**
 * 供应商响应不是可解析 JSON 时使用的本地错误。
 * 不拼接原始 JSON 或底层解析消息，避免响应中的案件内容、Key 泄漏到上层。
 */
export class RerankResponseError extends Error {
  constructor() {
    super('排序结果格式无效');
  }
}

/**
 * 在发送付费请求之前检查查询、候选数量/长度和请求返回数。
 * 候选为空不会自动补充资料，也不会把 limit 放宽到超过 10；错误直接抛出本地固定提示。
 * 此函数只校验参数形式，不能证明候选已获业务授权，授权由 Java 在进入接口前完成。
 */
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
  ) {
    throw new Error('排序参数无效');
  }
}

/**
 * 调用 Cohere v2 兼容的 baseUrl/rerank，再把供应商结果约束到本次候选集合。
 * 请求前验证模型配置、无账号/查询/片段的 HTTPS URL 及精确域名白名单；禁止跟随重定向。
 * 默认超时 30 秒，没有自动重试；连接探针可传入更短超时，每次调用仍是独立远程请求。
 *
 * 响应按流累计最多 100000 字节并严格 UTF-8 解码；必须返回恰好 top_n 个唯一的合法下标，
 * 分数为有限的 0–1 数值。额外回传的原文不参与输出，最终按分数降序、同分按输入下标排序。
 *
 * @param input Java 已授权的原文和查询，不在本函数扩大候选范围。
 * @param config Java 本次提供的人工配置，仅用于本次请求，不持久化 Key。
 * @param allowedHosts 部署提供的精确主机白名单，不能由供应商响应重定向放宽。
 * @param send 可替换的请求函数，生产默认 fetch，便于用合成响应验证协议。
 * @param timeoutMs 单次请求超时预算；此函数不处理付费操作的恢复日志。
 * @returns 输入文档下标与分数；参数、HTTP、流读取或响应契约不合格均抛错。
 */
export async function rerankDocuments(
  input: RerankInput,
  config: ModelConfiguration,
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
  ) {
    throw new Error('排序模型配置不完整');
  }
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
  ) {
    throw new Error('排序接口域名尚未获得授权');
  }
  const topN = Math.min(input.limit, input.documents.length);
  const response = await send(`${base.toString().replace(/\/$/, '')}/rerank`, {
    method: 'POST',
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.apiKey}` },
    body: JSON.stringify({
      model: config.model,
      query: input.query.trim(),
      documents: input.documents,
      top_n: topN,
    }),
  });
  // HTTP 失败不解析供应商错误正文，先取消响应流，再抛出只含状态码的本地异常。
  if (!response.ok) {
    await response.body?.cancel();
    throw new RerankRequestError(response.status);
  }
  // 字节预算在每次 read 后立即累加，防止先把巨大响应完整读入内存才检查。
  // TextDecoder 的 stream 模式保留跨网络块的 UTF-8 多字节字符，结束时再 flush 剩余字节。
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('排序服务未返回结果');
  }
  let raw = '';
  let bytes = 0;
  const decoder = new TextDecoder('utf-8', { fatal: true });
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) {
        break;
      }
      bytes += part.value.byteLength;
      if (bytes > 100000) {
        throw new Error('排序结果过大');
      }
      raw += decoder.decode(part.value, { stream: true });
    }
    raw += decoder.decode();
  } finally {
    // 无论解码、大小检查或网络读取是否失败，都尝试取消流并释放 reader。
    await reader.cancel();
    reader.releaseLock();
  }
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new RerankResponseError();
  }
  // JSON 语法正确不代表协议正确；必须继续核对返回条数、编号和分数。
  const results = (value as { results?: unknown })?.results;
  if (!Array.isArray(results) || results.length !== topN) {
    throw new Error('排序结果数量无效');
  }
  // 只接受唯一、未越界的输入下标和有限分数，不信任供应商回传的原文。
  const usedIndices = new Set<number>();
  const ranks: Rank[] = [];
  for (const row of results) {
    if (
      !row ||
      !Number.isInteger(row.index) ||
      row.index < 0 ||
      row.index >= input.documents.length ||
      usedIndices.has(row.index) ||
      typeof row.relevance_score !== 'number' ||
      !Number.isFinite(row.relevance_score) ||
      row.relevance_score < 0 ||
      row.relevance_score > 1
    ) {
      throw new Error('排序结果编号或分数无效');
    }
    usedIndices.add(row.index);
    ranks.push({ index: row.index, score: row.relevance_score });
  }
  // 分数相同则沿用输入顺序，使结果稳定、便于核对来源。
  return ranks.sort((left, right) => right.score - left.score || left.index - right.index);
}
