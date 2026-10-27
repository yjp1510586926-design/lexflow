export type CaseStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

export type CaseMemberRole = 'OWNER' | 'EDITOR' | 'READER';

export interface CaseView {
  id: string;
  caseNo: string;
  title: string;
  type: string;
  description: string;
  ownerId: string;
  status: CaseStatus;
  version: number;
  filedOn: string | null;
  archivedAt: string | null;
}

export interface CasePage {
  items: CaseView[];
  total: number;
  page: number;
  size: number;
}

export interface CaseMemberView {
  id: string;
  name: string;
  role: CaseMemberRole;
}

export interface TenantUserView {
  id: string;
  name: string;
}

export interface CreateCaseParams {
  caseNo: string;
  title: string;
  type: string;
  description: string;
  filedOn?: string | null;
}

export interface UpdateCaseParams {
  version: number;
  title: string;
  description: string;
  status: CaseStatus;
  filedOn?: string | null;
}

export interface AddMemberParams {
  userId: number;
  role: 'EDITOR' | 'READER';
}

export interface ChangeRoleParams {
  role: 'EDITOR' | 'READER';
}

export interface TransferOwnerParams {
  userId: number;
  version: number;
}

export interface CaseFilterParams {
  page?: number;
  size?: number;
  keyword?: string;
  status?: CaseStatus;
  type?: string;
  ownerId?: string;
}
