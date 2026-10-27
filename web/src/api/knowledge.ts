import { request } from './client';

export interface KnowledgeBase {
  id: string;
  caseId: string;
  name: string;
  scope: 'CASE';
  status: 'ACTIVE' | 'ARCHIVED';
}

export interface KnowledgeDocument {
  id: string;
  fileVersionId: string;
  parseTaskId: string;
  fileName: string;
  fileVersion: number;
  status: 'PREPARED' | 'INDEXING' | 'ACTIVE' | 'FAILED' | 'SUPERSEDED';
  chunkCount: number;
  pendingTaskId: string | null;
}

export interface KnowledgeChunk {
  id: string;
  chunkNo: number;
  locatorKind: 'PAGE' | 'PARAGRAPH';
  locatorIndex: number;
  startOffset: number;
  endOffset: number;
  text: string;
}

export const listKnowledgeBases = (caseId: string) =>
  request<KnowledgeBase[]>('/api/v1/knowledge-bases', { params: { caseId } });

export const createKnowledgeBase = (caseId: string, name: string) =>
  request<KnowledgeBase>('/api/v1/knowledge-bases', {
    method: 'POST',
    body: JSON.stringify({ caseId, name }),
  });

export const listKnowledgeDocuments = (baseId: string) =>
  request<KnowledgeDocument[]>(`/api/v1/knowledge-bases/${baseId}/documents`);

export const prepareKnowledgeDocument = (baseId: string, parseTaskId: string) =>
  request<KnowledgeDocument>(`/api/v1/knowledge-bases/${baseId}/documents`, {
    method: 'POST',
    body: JSON.stringify({ parseTaskId }),
  });

export const listKnowledgeChunks = (baseId: string, documentId: string) =>
  request<KnowledgeChunk[]>(`/api/v1/knowledge-bases/${baseId}/documents/${documentId}/chunks`);

export const startKnowledgeIndex = (baseId: string, documentId: string) =>
  request<{ taskId: string; status: string }>(
    `/api/v1/knowledge-bases/${baseId}/documents/${documentId}/index`,
    { method: 'POST' },
  );

export interface KnowledgeCitation {
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
  score: number;
}

export interface KnowledgeSearchResult {
  citations: KnowledgeCitation[];
  outdatedDocuments: number;
  reranked: boolean;
}

export const searchKnowledge = (baseId: string, question: string) =>
  request<KnowledgeSearchResult>(`/api/v1/knowledge-bases/${baseId}/search`, {
    method: 'POST',
    body: JSON.stringify({ question }),
  });
