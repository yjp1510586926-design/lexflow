import {
  WorkflowOperationConflictError,
  WorkflowOperationUncertainError,
} from '../persistence/mysql-checkpointer';
import { WorkflowInfrastructureError } from '../errors/workflow-infrastructure-error';
import { DeliveryFailure, transientDeliveryFailure } from './task-delivery.service';

/**
 * 把工作流异常转换为投递恢复层的三类失败，判断顺序本身就是恢复边界。
 * 先识别操作日志的不确定或输入冲突，再识别明确瞬时基础设施故障；
 * 其余情况下，已认领任务可能已经发送付费请求，未知错误必须保守视为 UNCERTAIN。
 *
 * @param error 工作流或 Java 内部接口抛出的异常。
 * @param claimed 是否已收到 Java 认领完成的通知，而不是仅仅收到队列消息。
 * @returns 消息应延时重投、直接死信，还是以不确定结果进入死信等待人工处理。
 */
export function workflowDeliveryFailure(error: unknown, claimed: boolean): DeliveryFailure {
  // 已有 STARTED 等不确定记录不能因伴随网络错误而降级为自动重试。
  if (error instanceof WorkflowOperationUncertainError) {
    return 'UNCERTAIN';
  }
  if (error instanceof WorkflowOperationConflictError) {
    // 同一操作键对应不同输入，等待时间不会消除冲突，不能继续复用该操作记录。
    return 'PERMANENT';
  }
  if (transientDeliveryFailure(error)) {
    return 'TRANSIENT';
  }
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  if (
    error instanceof WorkflowInfrastructureError ||
    (typeof status === 'number' && status >= 400 && status < 500)
  ) {
    // 已知的非瞬时基础设施错误与剩余 HTTP 4xx 都停止自动投递；429 已在前面单独判断。
    return 'PERMANENT';
  }
  // 认领后发生未分类故障时，无法证明外部调用是否完成，必须保留为不确定结果。
  return claimed ? 'UNCERTAIN' : 'PERMANENT';
}

/**
 * 校验队列事件中的 Java 正 long 编号，保持字符串形式，避免 JavaScript number 精度损失。
 * 正则先限制正十进制与位数，再用 BigInt 检查不超过 Java Long.MAX_VALUE。
 * 先做正则检查也保证后续 BigInt 不会因非法字符串抛异常。
 *
 * @param value 来自未验证消息的任意值。
 * @returns 是否为允许的字符串编号；类型谓词使后续代码能够按 string 使用。
 */
export function deliveryId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9][0-9]{0,18}$/.test(value) &&
    BigInt(value) <= 9223372036854775807n
  );
}
