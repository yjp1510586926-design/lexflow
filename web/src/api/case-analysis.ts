import { request } from './client';

export interface AnalysisSource {
  parseTaskId: string;
  fileVersionId: string;
  fileId: string;
  fileName: string;
  fileVersion: number;
  sha256: string;
  text: string;
  segments: { kind: 'PAGE' | 'PARAGRAPH'; index: number; startOffset: number; endOffset: number }[];
}
export interface AnalysisCitation {
  id: string;
  parseTaskId: string;
  fileVersionId: string;
  sha256: string;
  kind: 'PAGE' | 'PARAGRAPH';
  index: number;
  startOffset: number;
  endOffset: number;
  quote: string;
}
export interface CaseAnalysisReport {
  facts: { statement: string; citationIds: string[] }[];
  pendingVerification: string[];
  issues: {
    title: string;
    level: 'LOW' | 'MEDIUM' | 'HIGH';
    analysis: string;
    suggestion: string;
    citationIds: string[];
  }[];
  missingMaterials: string[];
  citations: AnalysisCitation[];
  recommendationBoundary: string;
  insufficientEvidence: boolean;
  needsHuman: boolean;
}
export interface CaseAnalysisView {
  taskId: string;
  caseId: string;
  status: string;
  stateVersion: number;
  background: string;
  requirements: string;
  sources: AnalysisSource[];
  report: CaseAnalysisReport | null;
  waitingReason: 'REVIEW' | 'DELIVERY' | 'UNCERTAIN' | null;
  decisionId: string | null;
  decidedBy: string | null;
  decisionNote: string | null;
}
export interface AnalysisRequested {
  taskId: string;
  status: string;
  stateVersion: number;
}
export const analysisSources = (caseId: string) =>
  request<AnalysisSource[]>('/api/v1/case-analyses/sources', { params: { caseId } });
export const submitAnalysis = (input: {
  caseId: string;
  parseTaskIds: string[];
  background: string;
  requirements: string;
  idempotencyKey: string;
  confirmModelCosts: boolean;
}) =>
  request<AnalysisRequested>('/api/v1/case-analyses', {
    method: 'POST',
    body: JSON.stringify(input),
  });
export const getAnalysis = (id: string) => request<CaseAnalysisView>(`/api/v1/case-analyses/${id}`);
export const approveAnalysis = (
  id: string,
  stateVersion: number,
  decisionId: string,
  note: string,
) =>
  request<AnalysisRequested>(`/api/v1/case-analyses/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ stateVersion, decisionId, note }),
  });
export const recoverAnalysis = (id: string, stateVersion: number) =>
  request<AnalysisRequested>(`/api/v1/case-analyses/${id}/recover`, {
    method: 'POST',
    body: JSON.stringify({ stateVersion }),
  });
