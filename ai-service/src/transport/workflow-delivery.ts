import {
  WorkflowOperationConflictError,
  WorkflowOperationUncertainError,
} from '../graphs/persistence/mysql-checkpointer';
import { WorkflowInfrastructureError } from '../graphs/workflow-infrastructure-error';
import { DeliveryFailure, transientDeliveryFailure } from './task-delivery.service';

/** 操作日志的不确定状态优先于 HTTP/数据库故障分类，防止重复触发可能已计费的调用。 */
export function workflowDeliveryFailure(error: unknown, claimed: boolean): DeliveryFailure {
  if (error instanceof WorkflowOperationUncertainError) return 'UNCERTAIN';
  if (error instanceof WorkflowOperationConflictError) return 'PERMANENT';
  if (transientDeliveryFailure(error)) return 'TRANSIENT';
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  if (
    error instanceof WorkflowInfrastructureError ||
    (typeof status === 'number' && status >= 400 && status < 500)
  )
    return 'PERMANENT';
  // 认领后发生未分类故障时，无法证明外部调用是否完成，必须保留为不确定结果。
  return claimed ? 'UNCERTAIN' : 'PERMANENT';
}

export function deliveryId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9][0-9]{0,18}$/.test(value) &&
    BigInt(value) <= 9223372036854775807n
  );
}
