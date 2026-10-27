import { request } from './client';
import { AuditPage } from '../types/audit';

export interface AuditFilterParams {
  page?: number;
  size?: number;
  action?: string;
}

export async function listAuditLogs(filters: AuditFilterParams = {}): Promise<AuditPage> {
  const params: Record<string, any> = {
    page: filters.page || 1,
    size: filters.size || 20,
  };
  if (filters.action && filters.action.trim()) {
    params.action = filters.action.trim();
  }
  return request<AuditPage>('/api/v1/audit-logs', { params });
}
