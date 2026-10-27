import { request } from './client';

export interface ContractClause {
  id: string;
  title: string;
  quote: string;
  startOffset: number;
  endOffset: number;
}
export interface ContractRisk {
  clauseId: string;
  level: 'HIGH' | 'MEDIUM' | 'LOW';
  reason: string;
  suggestion: string;
  evidenceIds: string[];
}
export interface ContractReport {
  sourceSha256: string;
  sourceText: string;
  contractType: string;
  clauses: ContractClause[];
  risks: ContractRisk[];
  missingMaterials: string[];
  evidence: { id: string; text: string }[];
  needsHuman: boolean;
  basisConflicts?: { description: string; evidenceIds: string[] }[];
}
export interface ContractSupplement {
  parseTaskId: string;
  fileVersionId: string;
  sha256: string;
  fileName: string;
  text: string;
  segments: { kind: 'PAGE' | 'PARAGRAPH'; index: number; startOffset: number; endOffset: number }[];
}
export interface ContractCandidate {
  parseTaskId: string;
  fileVersionId: string;
  fileName: string;
  fileVersion: number;
}
export interface ContractCitation {
  id: string;
  text: string;
  scope: 'CASE' | 'TENANT';
  knowledgeBaseId: string;
  fileId: string;
  fileVersionId: string;
  fileName: string;
  fileVersion: number;
  locatorKind: 'PAGE' | 'PARAGRAPH';
  locatorIndex: number;
  startOffset: number;
  endOffset: number;
}
export interface ContractView {
  taskId: string;
  status: string;
  stateVersion: number;
  stance: string;
  focus: string;
  report: ContractReport | null;
  waitingReason: 'REVIEW' | 'DELIVERY' | 'UNCERTAIN' | null;
  decisionId: string | null;
  decidedBy: string | null;
  decisionNote: string | null;
  round: number;
  supplements: ContractSupplement[];
}
export const submitContract = (input: {
  caseId: string;
  fileVersionId: string;
  stance: string;
  focus: string;
  knowledgeBaseId: string | null;
  idempotencyKey: string;
}) =>
  request<{ taskId: string; status: string; stateVersion: number }>('/api/v1/contract-reviews', {
    method: 'POST',
    body: JSON.stringify(input),
  });
export const getContract = (id: string) => request<ContractView>(`/api/v1/contract-reviews/${id}`);
export const approveContract = (
  id: string,
  stateVersion: number,
  decisionId: string,
  note: string,
) =>
  request(`/api/v1/contract-reviews/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ stateVersion, decisionId, note }),
  });
export const recoverContract = (id: string, stateVersion: number) =>
  request(`/api/v1/contract-reviews/${id}/recover`, {
    method: 'POST',
    body: JSON.stringify({ stateVersion }),
  });
export const contractCandidates = (id: string) =>
  request<ContractCandidate[]>(`/api/v1/contract-reviews/${id}/supplement-candidates`);
export const supplementContract = (
  id: string,
  stateVersion: number,
  requestId: string,
  parseTaskIds: string[],
  instructions: string,
  costConfirmed: boolean,
) =>
  request(`/api/v1/contract-reviews/${id}/supplement`, {
    method: 'POST',
    retryCsrf: false,
    body: JSON.stringify({ stateVersion, requestId, parseTaskIds, instructions, costConfirmed }),
  });
export const contractCitation = (id: string, chunkId: string) =>
  request<ContractCitation>(`/api/v1/contract-reviews/${id}/citations/${chunkId}`);
export async function downloadContractCitation(
  id: string,
  chunkId: string,
  filename: string,
  isCurrent: () => boolean = () => true,
): Promise<void> {
  const response = await fetch(`/api/v1/contract-reviews/${id}/citations/${chunkId}/download`, {
    credentials: 'include',
  });
  if (!response.ok) {
    const error = new Error(`下载失败 (${response.status})`) as Error & { status: number };
    error.status = response.status;
    throw error;
  }
  const blob = await response.blob();
  if (!isCurrent()) return;
  const url = URL.createObjectURL(blob),
    link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
