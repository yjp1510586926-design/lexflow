/**
 * 标记模型操作日志之外的基础设施或实时授权检查故障。
 * transient=true 表示投递层可以按有限重试队列恢复；false 表示不能据此自动重放。
 * runOnce 内部的模型失败/不确定状态使用专门异常，即使底层是超时，也不能直接归入普通基础设施重试。
 * message 必须是本地通用提示，不能携带原始 HTTP 请求、响应正文、模型 Key 或私有材料。
 */
export class WorkflowInfrastructureError extends Error {
  constructor(
    readonly transient: boolean,
    message: string,
  ) {
    super(message);
  }
}

/**
 * 按已知 HTTP 状态及网络/MySQL 错误码判断是否属于瞬时基础设施故障。
 * 已包装异常优先使用其 transient 值；否则只识别 429、5xx 以及明确的连接/超时/死锁等错误码。
 * 这是错误类别判断，不证明远程操作未执行；调用方必须先排除操作日志的不确定或失败状态。
 *
 * @param error 本地捕获的异常，原始对象不被保存或加入新的异常链。
 * @returns 是否允许由投递层按既有次数上限重试基础设施步骤。
 */
export function transientInfrastructureFailure(error: unknown): boolean {
  const value = error as { code?: string; response?: { status?: number } } | undefined;
  const status = value?.response?.status;
  return error instanceof WorkflowInfrastructureError
    ? error.transient
    : (typeof status === 'number' && (status === 429 || (status >= 500 && status <= 599))) ||
        [
          'ECONNREFUSED',
          'ECONNRESET',
          'ETIMEDOUT',
          'EAI_AGAIN',
          'PROTOCOL_CONNECTION_LOST',
          'ER_LOCK_DEADLOCK',
          'ER_LOCK_WAIT_TIMEOUT',
        ].includes(value?.code ?? '');
}

/**
 * 将底层错误缩减为是否瞬时故障和本地提示，供消费者安全分类。
 * 不设置 cause，也不透传服务商响应、请求配置或原始 message，避免凭据和材料随异常传播。
 *
 * @param error 仅用于提取类别的原异常。
 * @param message 调用者提供的固定本地提示，不能含供应商私有内容。
 * @returns 不保留原始异常对象的 WorkflowInfrastructureError。
 */
export function infrastructureError(error: unknown, message: string): WorkflowInfrastructureError {
  return new WorkflowInfrastructureError(transientInfrastructureFailure(error), message);
}
