import { request } from './client';
export interface AiSession {
  id: string;
  caseId: string | null;
  scope: 'CASE' | 'TENANT';
  baseId: string;
  title: string;
  createdAt: string;
}
export interface AiCitation {
  id: string;
  chunkId: string;
  documentId: string;
  fileId: string;
  fileVersionId: string;
  fileName: string;
  fileVersion: number;
  locatorKind: 'PAGE' | 'PARAGRAPH';
  locatorIndex: number;
  startOffset: number;
  endOffset: number;
  text: string;
}
export interface AiQuestion {
  id: string;
  requestId: string;
  question: string;
  status: 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
  answer: string | null;
  insufficientEvidence: boolean;
  citations: AiCitation[];
  sourcesUnavailable: boolean;
  error: string | null;
  createdAt: string;
}
export const listAiSessions = (baseId: string) =>
  request<AiSession[]>('/api/v1/ai/sessions', { params: { baseId } });
export const createAiSession = (baseId: string, title: string) =>
  request<AiSession>('/api/v1/ai/sessions', {
    method: 'POST',
    body: JSON.stringify({ baseId, title }),
  });
export const listAiQuestions = (sessionId: string) =>
  request<AiQuestion[]>(`/api/v1/ai/sessions/${sessionId}/messages`);
export const askAiQuestion = (sessionId: string, requestId: string, question: string) =>
  request<AiQuestion>(`/api/v1/ai/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ requestId, question }),
  });
