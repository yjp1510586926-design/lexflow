import { request } from './client';

export interface EvidenceView {
  id: string;
  fileId: string;
  fileVersionId: string;
  fileName: string;
  fileVersion: number;
  sha256: string;
  source: string;
  collectedAt: string | null;
  description: string;
  sortOrder: number;
  version: number;
  createdBy: string;
  updatedBy: string;
  updatedAt: string;
}
export interface EvidencePage {
  items: EvidenceView[];
  total: number;
  page: number;
  size: number;
  canEdit: boolean;
}
export interface EvidenceInput {
  version: number;
  source: string;
  collectedAt: string | null;
  description: string;
  sortOrder: number;
}
const path = (caseId: string) => `/api/v1/cases/${caseId}/evidence`;
export const listEvidence = (caseId: string, page = 1) =>
  request<EvidencePage>(path(caseId), { params: { page, size: 20 } });
export const getEvidence = (caseId: string, versionId: string) =>
  request<EvidenceView | null>(`${path(caseId)}/${versionId}`);
export const saveEvidence = (caseId: string, versionId: string, input: EvidenceInput) =>
  request<EvidenceView>(`${path(caseId)}/${versionId}`, {
    method: 'PUT',
    body: JSON.stringify(input),
    retryCsrf: false,
  });

export async function downloadEvidenceOriginal(fileId: string, fileName: string): Promise<void> {
  const response = await fetch(`/api/v1/files/${fileId}/download`, {
    credentials: 'include',
    headers: { Accept: 'application/octet-stream,application/json,*/*' },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw Object.assign(new Error(body?.message || '原件下载失败'), { status: response.status });
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
