import { request } from './client';
import { FileView } from '../types/file';
import { TaskView } from '../types/document';
import { KnowledgeBase, KnowledgeSearchResult } from './knowledge';

export interface TenantKnowledgeBase extends Omit<KnowledgeBase, 'caseId' | 'scope'> {
  caseId: null;
  scope: 'TENANT';
}

export interface TenantKnowledgeFile extends Omit<FileView, 'caseId'> {
  caseId: null;
  parseTaskId?: string | null;
  parseStatus?: TaskView['status'] | null;
}

export interface TenantKnowledgeAccess {
  items: TenantKnowledgeBase[];
  canManage: boolean;
}

export const listTenantKnowledgeBases = () =>
  request<TenantKnowledgeAccess>('/api/v1/tenant-knowledge-bases');

export const createTenantKnowledgeBase = (name: string) =>
  request<TenantKnowledgeBase>('/api/v1/tenant-knowledge-bases', {
    method: 'POST',
    body: JSON.stringify({ name }),
    retryCsrf: false,
  });

export const listTenantKnowledgeFiles = (baseId: string) =>
  request<TenantKnowledgeFile[]>(`/api/v1/tenant-knowledge-bases/${baseId}/files`);

export const uploadTenantKnowledgeFile = (baseId: string, file: File) => {
  const form = new FormData();
  form.append('file', file);
  return request<TenantKnowledgeFile>(`/api/v1/tenant-knowledge-bases/${baseId}/files`, {
    method: 'POST',
    body: form,
    retryCsrf: false,
  });
};

export const parseTenantKnowledgeFile = (
  baseId: string,
  fileId: string,
  fileVersionId: string,
  idempotencyKey: string,
) =>
  request<TaskView>(`/api/v1/tenant-knowledge-bases/${baseId}/files/${fileId}/parse`, {
    method: 'POST',
    body: JSON.stringify({ fileVersionId, idempotencyKey }),
    retryCsrf: false,
  });

export const indexTenantKnowledgeDocument = (baseId: string, documentId: string) =>
  request<{ taskId: string; status: string }>(
    `/api/v1/knowledge-bases/${baseId}/documents/${documentId}/index`,
    {
      method: 'POST',
      retryCsrf: false,
    },
  );

export const searchTenantKnowledge = (baseId: string, question: string) =>
  request<KnowledgeSearchResult>(`/api/v1/knowledge-bases/${baseId}/search`, {
    method: 'POST',
    body: JSON.stringify({ question }),
    retryCsrf: false,
  });

export const removeTenantKnowledgeFile = (baseId: string, fileId: string) =>
  request<void>(`/api/v1/tenant-knowledge-bases/${baseId}/files/${fileId}`, {
    method: 'DELETE',
    retryCsrf: false,
  });

export async function downloadTenantKnowledgeFile(
  baseId: string,
  fileId: string,
  filename: string,
  isCurrent?: () => boolean,
): Promise<void> {
  const response = await fetch(
    `/api/v1/tenant-knowledge-bases/${baseId}/files/${fileId}/download`,
    {
      credentials: 'include',
      headers: { Accept: 'application/octet-stream, application/json' },
    },
  );
  if (!response.ok) {
    const error = new Error(`下载失败 (${response.status})`) as Error & { status: number };
    error.status = response.status;
    throw error;
  }
  const blob = await response.blob();
  if (isCurrent && !isCurrent()) return;
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
