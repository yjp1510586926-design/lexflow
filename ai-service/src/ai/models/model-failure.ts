/**
 * 模型失败的本地分类表：调用层可以保存错误码并展示提示，无需保留供应商异常。
 * as const 让键名形成 ModelFailureCode 联合类型，避免业务层自由拼接错误种类。
 * 提示固定在本地，供应商返回的 URL、响应体、Key 或原文不会被拼入这些消息。
 */
const MODEL_FAILURE_MESSAGES = {
  CALL_FAILED: '模型调用未完成，请检查模型服务配置。',
  AUTH_FAILED: '模型服务认证失败，请检查 API Key 是否有效。',
  INSUFFICIENT_BALANCE: '模型服务余额不足，请检查服务商账户额度。',
  MODEL_UNAVAILABLE: '模型或接口不可用，请检查模型名称和接口地址。',
  REQUEST_INVALID: '模型服务不接受本次请求，请检查接口兼容性及模型配置。',
  RATE_LIMITED: '模型服务请求受到限流，请稍后再主动提交。',
  PROVIDER_UNAVAILABLE: '模型服务暂时不可用，请稍后检查服务状态。',
  MODEL_TIMEOUT: '模型请求超时，未能取得有效结果。',
  NETWORK_FAILED: '无法连接模型服务，请检查服务端网络。',
  OUTPUT_LIMIT: '模型输出达到长度上限，未能生成完整结果。',
  OUTPUT_INVALID: '模型输出格式无效，无法生成分析结果。',
  CITATION_INVALID: '模型引用未通过原文核对，无法生成可信分析结果。',
} as const;

export type ModelFailureCode = keyof typeof MODEL_FAILURE_MESSAGES;

/**
 * 把运行时未知值收敛为固定错误码，适用于 HTTP 输入、操作日志或第三方异常字段。
 * Object.hasOwn 仅接受分类表自身的属性，拒绝 toString、__proto__ 等继承属性名。
 * 未知值统一降级为 CALL_FAILED，不能直接把传入字符串当成可展示消息。
 */
export function sanitizeModelFailureCode(value: unknown): ModelFailureCode {
  return typeof value === 'string' && Object.hasOwn(MODEL_FAILURE_MESSAGES, value)
    ? (value as ModelFailureCode)
    : 'CALL_FAILED';
}

/** 先清洗错误码再取本地提示，因此即使调用者传入异常对象，也不会读取其 message。 */
export function modelFailureMessage(code: unknown): string {
  return MODEL_FAILURE_MESSAGES[sanitizeModelFailureCode(code)];
}

/**
 * 本地模型输出校验失败，与网络失败或供应商 HTTP 失败分开处理。
 * 例如输出被截断、JSON 结构无效、引用未通过原文核对，都可使用明确的固定分类。
 * 构造时再次清洗 code，只保存安全分类及本地消息，不附加模型内容或供应商 cause。
 */
export class ModelOutputError extends Error {
  readonly code: ModelFailureCode;

  constructor(code: ModelFailureCode) {
    const safeCode = sanitizeModelFailureCode(code);
    super(modelFailureMessage(safeCode));
    this.name = 'ModelOutputError';
    this.code = safeCode;
  }
}

/**
 * 防御性地读取第三方错误对象的单个分类字段。
 * JavaScript 对象的属性可能由 getter 实现，访问 status 本身也可能抛异常。
 * 这里只在异常发生时返回 undefined，让上层继续降级分类，不记录或透传读取错误。
 * @returns 字段原值或 undefined；不执行字符串转换，不读取无关字段。
 */
function readErrorField(value: unknown, key: string): unknown {
  if (!value || (typeof value !== 'object' && typeof value !== 'function')) {
    return undefined;
  }
  try {
    return (value as Record<string, unknown>)[key];
  } catch {
    return undefined;
  }
}

// 不通过匹配供应商 message 猜测原因；只接受已知的标准错误名称与网络代码。
const timeoutCodes = new Set([
  'ETIMEDOUT',
  'ESOCKETTIMEDOUT',
  'UND_ERR_CONNECT_TIMEOUT',
  'UND_ERR_HEADERS_TIMEOUT',
  'UND_ERR_BODY_TIMEOUT',
  'ABORT_ERR',
]);
const timeoutNames = new Set(['AbortError', 'TimeoutError', 'APIConnectionTimeoutError']);
const networkCodes = new Set([
  'ECONNRESET',
  'ECONNREFUSED',
  'ENOTFOUND',
  'EAI_AGAIN',
  'EPIPE',
  'ERR_NETWORK',
  'UND_ERR_SOCKET',
  'CERT_HAS_EXPIRED',
  'UNABLE_TO_VERIFY_LEAF_SIGNATURE',
  'DEPTH_ZERO_SELF_SIGNED_CERT',
]);
const networkNames = new Set(['APIConnectionError', 'FetchError']);

/**
 * 将任意模型异常归入固定错误分类，不读取原始 message、body、请求 URL 或响应正文。
 * 优先级：本地 ModelOutputError → 常见 HTTP 状态 → 当前错误及 cause 中的超时/网络代码。
 * HTTP 状态必须本身就是整数，字符串 "401" 不会被自动转换成认证失败。
 * 未识别的状态继续检查网络信息，最终统一降级为 CALL_FAILED。
 *
 * @param error SDK、fetch 或本地输出校验抛出的未知值。
 * @returns 可以持久化和展示的本地分类；分类本身不表示允许重试。
 */
export function classifyModelFailure(error: unknown): ModelFailureCode {
  if (error instanceof ModelOutputError) {
    return sanitizeModelFailureCode(error.code);
  }

  const statuses = [
    readErrorField(error, 'status'),
    readErrorField(error, 'statusCode'),
    readErrorField(readErrorField(error, 'response'), 'status'),
  ];
  for (const status of statuses) {
    // 先处理服务商已经明确返回的状态，例如 401 不应被嵌套超时代码覆盖。
    if (typeof status !== 'number' || !Number.isInteger(status)) {
      continue;
    }
    if (status === 401) {
      return 'AUTH_FAILED';
    }
    if (status === 402) {
      return 'INSUFFICIENT_BALANCE';
    }
    if (status === 404) {
      return 'MODEL_UNAVAILABLE';
    }
    if (status === 400 || status === 422) {
      return 'REQUEST_INVALID';
    }
    if (status === 429) {
      return 'RATE_LIMITED';
    }
    if (status >= 500 && status <= 599) {
      return 'PROVIDER_UNAVAILABLE';
    }
  }

  let current = error;
  // fetch 常把网络错误放在 cause 中；最多检查三层，循环引用也不会导致死循环。
  for (let depth = 0; depth < 3 && current; depth += 1) {
    const name = readErrorField(current, 'name');
    const code = readErrorField(current, 'code');
    if (
      (typeof name === 'string' && timeoutNames.has(name)) ||
      (typeof code === 'string' && timeoutCodes.has(code))
    ) {
      return 'MODEL_TIMEOUT';
    }
    if (
      (typeof name === 'string' && networkNames.has(name)) ||
      (typeof code === 'string' && networkCodes.has(code))
    ) {
      return 'NETWORK_FAILED';
    }
    current = readErrorField(current, 'cause');
  }
  return 'CALL_FAILED';
}
