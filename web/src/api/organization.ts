import { request } from './client';
import { OrgUserView, CreateOrgUserParams, UpdateOrgUserParams } from '../types/organization';

export async function listOrgUsers(): Promise<OrgUserView[]> {
  return request<OrgUserView[]>('/api/v1/organization/users');
}

export async function createOrgUser(params: CreateOrgUserParams): Promise<{ id: string }> {
  return request<{ id: string }>('/api/v1/organization/users', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function updateOrgUser(id: string, params: UpdateOrgUserParams): Promise<void> {
  return request<void>(`/api/v1/organization/users/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(params),
  });
}
