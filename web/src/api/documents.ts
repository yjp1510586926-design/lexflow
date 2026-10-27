import { request } from './client';
import { DocumentView, VersionView } from '../types/document';

export async function listDocuments(caseId: string): Promise<DocumentView[]> {
  return request<DocumentView[]>(`/api/v1/cases/${caseId}/documents`);
}

export async function createDocument(
  caseId: string,
  title: string,
  content: string,
): Promise<DocumentView> {
  return request<DocumentView>(`/api/v1/cases/${caseId}/documents`, {
    method: 'POST',
    body: JSON.stringify({ title, content }),
  });
}

export async function listVersions(caseId: string, docId: string): Promise<VersionView[]> {
  return request<VersionView[]>(`/api/v1/cases/${caseId}/documents/${docId}/versions`);
}

function expectedDocument(doc: DocumentView) {
  // 同时提交文书锁版本和当前内容指针，防止过期页面覆盖另一编辑者或后台生成的最新版本。
  if (!doc.currentVersionId || !Number.isSafeInteger(doc.version) || doc.version < 0)
    throw new Error('请重新读取文书版本后再操作');
  return { expectedVersion: doc.version, expectedCurrentVersionId: doc.currentVersionId };
}

export async function addVersion(
  caseId: string,
  doc: DocumentView,
  content: string,
): Promise<VersionView> {
  return request<VersionView>(`/api/v1/cases/${caseId}/documents/${doc.id}/versions`, {
    method: 'POST',
    body: JSON.stringify({ content, ...expectedDocument(doc) }),
  });
}

export async function publishDocument(caseId: string, doc: DocumentView): Promise<DocumentView> {
  return request<DocumentView>(`/api/v1/cases/${caseId}/documents/${doc.id}/publish`, {
    method: 'POST',
    body: JSON.stringify(expectedDocument(doc)),
  });
}

export async function restoreVersion(
  caseId: string,
  doc: DocumentView,
  fromVersionId: string,
): Promise<VersionView> {
  return request<VersionView>(
    `/api/v1/cases/${caseId}/documents/${doc.id}/restore/${fromVersionId}`,
    {
      method: 'POST',
      body: JSON.stringify(expectedDocument(doc)),
    },
  );
}

export async function reviewVersion(
  caseId: string,
  docId: string,
  versionId: string,
  status: 'APPROVED' | 'REJECTED',
): Promise<VersionView> {
  return request<VersionView>(
    `/api/v1/cases/${caseId}/documents/${docId}/versions/${versionId}/review`,
    {
      method: 'POST',
      body: JSON.stringify({ status }),
    },
  );
}

/** 下载当前已保存版本；服务端检查版本指针，防止页面过期时下载错版本。 */
export async function exportDocument(caseId: string, doc: DocumentView): Promise<void> {
  if (!doc.currentVersionId) throw new Error('文书尚无可导出的当前版本');
  const query = new URLSearchParams({ expectedVersionId: doc.currentVersionId });
  return downloadTxtFile(
    `/api/v1/cases/${caseId}/documents/${doc.id}/export?${query}`,
    `document_v${doc.currentVersionNo}.txt`,
  );
}

export interface DocumentExportTask {
  taskId: string;
  status: string;
  stateVersion: number;
  attempt: number;
  documentId: string;
  versionId: string;
  versionNo: number;
  title: string;
  documentStatus: string;
  errorMessage: string | null;
  expiresAt?: string | null;
  expired?: boolean;
}
export async function requestDocumentExport(
  caseId: string,
  doc: DocumentView,
  idempotencyKey: string,
): Promise<DocumentExportTask> {
  if (!doc.currentVersionId) throw new Error('文书尚无可导出的当前版本');
  return request(`/api/v1/cases/${caseId}/documents/${doc.id}/exports`, {
    method: 'POST',
    body: JSON.stringify({ expectedVersionId: doc.currentVersionId, idempotencyKey }),
  });
}
export function getDocumentExportTask(id: string): Promise<DocumentExportTask> {
  return request(`/api/v1/tasks/${id}/export`);
}
export function recoverDocumentExport(
  id: string,
  stateVersion: number,
): Promise<DocumentExportTask> {
  return request(`/api/v1/tasks/${id}/export/recover`, {
    method: 'POST',
    body: JSON.stringify({ stateVersion }),
  });
}
export function downloadDocumentExport(
  job: DocumentExportTask,
  shouldContinue: () => boolean = () => true,
): Promise<void> {
  return downloadTxtFile(
    `/api/v1/tasks/${job.taskId}/export/download`,
    `document_v${job.versionNo}.txt`,
    shouldContinue,
  );
}
async function downloadTxtFile(
  endpoint: string,
  fallbackName: string,
  shouldContinue: () => boolean = () => true,
): Promise<void> {
  const response = await fetch(endpoint, {
    credentials: 'include',
    cache: 'no-store',
    headers: { Accept: 'text/plain, application/json' },
  });
  if (!response.ok) {
    let message =
      response.status === 401 ? '登录已失效，请重新登录' : `导出失败 (${response.status})`;
    try {
      const body = await response.json();
      if (typeof body.message === 'string') message = body.message;
    } catch {
      /* 使用通用提示 */
    }
    throw Object.assign(new Error(message), { status: response.status });
  }
  const disposition = response.headers.get('Content-Disposition') ?? '';
  if (
    !response.headers.get('Content-Type')?.startsWith('text/plain') ||
    !disposition.startsWith('attachment;')
  ) {
    throw new Error('导出响应无效，请刷新后再试');
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  let filename = fallbackName;
  if (encoded) {
    try {
      filename = decodeURIComponent(encoded).replace(/[\\/:*?"<>|\x00-\x1f\x7f]/g, '_');
    } catch {
      /* 使用备用文件名 */
    }
  }
  const blob = await response.blob();
  // 网络请求结束后再次检查页面生命周期；切换任务或撤权后不触发旧任务的浏览器下载。
  if (!shouldContinue()) return;
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.hidden = true;
  document.body.appendChild(link);
  try {
    link.click();
  } finally {
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
