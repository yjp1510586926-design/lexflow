export type FileStatus = 'UPLOADING' | 'AVAILABLE' | 'FAILED' | 'DELETED';

export interface FileView {
  id: string;
  caseId: string;
  displayName: string;
  status: FileStatus;
  mimeType: string | null;
  sizeBytes: number;
  sha256: string | null;
  versionNo: number;
  versionId: string | null;
  createdBy: string;
  createdAt: string;
}
