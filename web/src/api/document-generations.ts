import { request } from './client';
import { VersionView } from '../types/document';
export interface GenerationRequested {
  taskId: string;
  status: string;
  stateVersion: number;
}
export interface GenerationView extends GenerationRequested {
  documentId: string;
  sourceVersionId: string;
  instructions: string;
  errorMessage: string | null;
  result: VersionView | null;
  missingInformation: string[];
}
export function requestGeneration(
  caseId: string,
  documentId: string,
  sourceVersionId: string,
  instructions: string,
  key: string,
  confirmModelCost: boolean,
) {
  // 固定源文书版本和幂等键；付费生成需要显式费用确认，网络层不自动重发。
  return request<GenerationRequested>(
    `/api/v1/cases/${caseId}/documents/${documentId}/generations`,
    {
      method: 'POST',
      retryCsrf: false,
      body: JSON.stringify({
        sourceVersionId,
        instructions,
        idempotencyKey: key,
        confirmModelCost,
      }),
    },
  );
}
export function getGeneration(taskId: string) {
  return request<GenerationView>(`/api/v1/tasks/${taskId}/generation`);
}
export function recoverGeneration(taskId: string, stateVersion: number) {
  return request<GenerationRequested>(`/api/v1/tasks/${taskId}/generation/recover`, {
    method: 'POST',
    retryCsrf: false,
    body: JSON.stringify({ stateVersion }),
  });
}
