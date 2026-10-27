import { request } from './client';
import {
  CasePage,
  CaseView,
  CaseMemberView,
  TenantUserView,
  CreateCaseParams,
  UpdateCaseParams,
  AddMemberParams,
  ChangeRoleParams,
  TransferOwnerParams,
  CaseFilterParams,
} from '../types/case';

export async function listCases(filters: CaseFilterParams = {}): Promise<CasePage> {
  const params: Record<string, any> = {
    page: filters.page || 1,
    size: filters.size || 20,
  };
  if (filters.keyword && filters.keyword.trim()) {
    params.keyword = filters.keyword.trim();
  }
  if (filters.status) {
    params.status = filters.status;
  }
  if (filters.type && filters.type.trim()) {
    params.type = filters.type.trim();
  }
  if (filters.ownerId) {
    params.ownerId = filters.ownerId;
  }

  return request<CasePage>('/api/v1/cases', { params });
}

export async function getCase(id: string): Promise<CaseView> {
  return request<CaseView>(`/api/v1/cases/${id}`);
}

export async function createCase(params: CreateCaseParams): Promise<CaseView> {
  return request<CaseView>('/api/v1/cases', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function updateCase(id: string, params: UpdateCaseParams): Promise<CaseView> {
  return request<CaseView>(`/api/v1/cases/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(params),
  });
}

export async function getCaseMembers(id: string): Promise<CaseMemberView[]> {
  return request<CaseMemberView[]>(`/api/v1/cases/${id}/members`);
}

export async function addCaseMember(id: string, params: AddMemberParams): Promise<void> {
  return request<void>(`/api/v1/cases/${id}/members`, {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function removeCaseMember(id: string, userId: string): Promise<void> {
  return request<void>(`/api/v1/cases/${id}/members/${userId}`, {
    method: 'DELETE',
  });
}

export async function changeMemberRole(
  id: string,
  userId: string,
  params: ChangeRoleParams,
): Promise<void> {
  return request<void>(`/api/v1/cases/${id}/members/${userId}`, {
    method: 'PATCH',
    body: JSON.stringify(params),
  });
}

export async function transferCaseOwner(
  id: string,
  params: TransferOwnerParams,
): Promise<CaseView> {
  return request<CaseView>(`/api/v1/cases/${id}/owner`, {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function getTenantUsers(): Promise<TenantUserView[]> {
  return request<TenantUserView[]>('/api/v1/tenant/users');
}

export interface CaseOwner {
  id: string;
  name: string;
}

export async function getCaseOwners(): Promise<CaseOwner[]> {
  return request<CaseOwner[]>('/api/v1/cases/owners');
}
