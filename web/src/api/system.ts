import { request } from './client';

export interface HealthStatus {
  status: string;
  [key: string]: any;
}

export async function getHealth(): Promise<HealthStatus> {
  return request<HealthStatus>('/api/health');
}
