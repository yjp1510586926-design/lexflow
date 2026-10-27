// 数据库 long ID 在接口中使用十进制字符串，避免超过 JavaScript 的安全整数范围。
export interface DocumentView {
  id: string;
  caseId: string;
  title: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  currentVersionId: string | null;
  currentVersionNo: number;
  createdBy: string;
  createdAt: string;
  version: number;
}

// versionNo 是不可变内容版本的展示序号；DocumentView.version 是并发写入使用的乐观锁版本。
export interface VersionView {
  id: string;
  documentId: string;
  versionNo: number;
  contentText: string | null;
  source: 'MANUAL' | 'AI_DRAFT';
  reviewStatus: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | null;
  createdBy: string;
  createdAt: string;
}

// CANCEL_REQUESTED 表示已申请取消，须等待执行方确认后才能显示 CANCELLED。
export interface TaskView {
  id: string;
  caseId: string | null;
  resourceVersionId: string | null;
  type:
    | 'DOCUMENT_PARSE'
    | 'KNOWLEDGE_INGEST'
    | 'CONTRACT_REVIEW'
    | 'EXPORT'
    | 'DOCUMENT_GENERATE'
    | 'CASE_ANALYSIS';
  status:
    | 'QUEUED'
    | 'RUNNING'
    | 'WAITING_HUMAN'
    | 'SUCCEEDED'
    | 'FAILED'
    | 'CANCEL_REQUESTED'
    | 'CANCELLED';
  attempt: number;
  errorMessage: string | null;
  createdBy: string;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface TaskPage {
  items: TaskView[];
  total: number;
  page: number;
  size: number;
}
