import { request } from './client';
import { TaskPage, TaskView } from '../types/document';

export async function listTasks(
  params: {
    page?: number;
    size?: number;
    caseId?: string | number;
    type?: string;
    status?: string;
  } = {},
): Promise<TaskPage> {
  return request<TaskPage>('/api/v1/tasks', { params });
}

export async function submitTask(payload: {
  caseId?: string | number | null;
  type: 'DOCUMENT_PARSE' | 'KNOWLEDGE_INGEST' | 'CONTRACT_REVIEW' | 'EXPORT' | string;
  resourceVersionId?: string | number | null;
  idempotencyKey?: string;
}): Promise<TaskView> {
  return request<TaskView>('/api/v1/tasks', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function getTask(taskId: string): Promise<TaskView> {
  return request<TaskView>(`/api/v1/tasks/${taskId}`);
}

export async function getParsedText(
  taskId: string,
): Promise<{ text: string; sourceSha256: string }> {
  return request(`/api/v1/tasks/${taskId}/parsed-text`);
}

export async function cancelTask(taskId: string): Promise<TaskView> {
  return request<TaskView>(`/api/v1/tasks/${taskId}/cancel`, { method: 'POST' });
}

export interface TaskDeliveryRecoveryView {
  taskId: string;
  type: TaskView['type'];
  status: TaskView['status'];
  attempt: number;
  stateVersion: number;
  deliveryStatus: 'NONE' | 'DEAD' | 'RESOLVED';
  retryCount: number;
  failureKind: 'TRANSIENT' | 'PERMANENT' | 'UNCERTAIN' | null;
  deadLetteredAt: string | null;
  canRecover: boolean;
  recoveryMessage: string | null;
}

export const isDeliveryRecoveryType = (type: string) =>
  [
    'DOCUMENT_PARSE',
    'KNOWLEDGE_INGEST',
    'CONTRACT_REVIEW',
    'DOCUMENT_GENERATE',
    'CASE_ANALYSIS',
  ].includes(type);

export const hasRecoveryModelCosts = (type: string) =>
  ['KNOWLEDGE_INGEST', 'CONTRACT_REVIEW', 'DOCUMENT_GENERATE', 'CASE_ANALYSIS'].includes(type);

export async function getTaskDeliveryRecovery(taskId: string): Promise<TaskDeliveryRecoveryView> {
  return request<TaskDeliveryRecoveryView>(`/api/v1/tasks/${taskId}/delivery-recovery`);
}

export async function recoverTaskDelivery(
  taskId: string,
  payload: {
    stateVersion: number;
    requestId: string;
    confirmModelCosts: boolean;
  },
): Promise<TaskDeliveryRecoveryView> {
  // 恢复可能触发付费步骤，由页面保留 requestId 处理结果不明的重试，禁用通用 CSRF 自动重发。
  return request<TaskDeliveryRecoveryView>(`/api/v1/tasks/${taskId}/delivery-recovery/recover`, {
    method: 'POST',
    retryCsrf: false,
    body: JSON.stringify(payload),
  });
}
