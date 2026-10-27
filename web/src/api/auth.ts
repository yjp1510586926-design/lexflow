import { request, clearCsrfTokenCache } from './client';
import { Identity, LoginParams, CsrfTokenData } from '../types/auth';

export async function fetchCsrf(): Promise<CsrfTokenData> {
  return request<CsrfTokenData>('/api/v1/auth/csrf');
}

export async function login(params: LoginParams): Promise<Identity> {
  // 登录会替换服务端会话；登录前的缓存和登录请求使用的 token 都不能留给首次已登录写操作。
  clearCsrfTokenCache();
  const identity = await request<Identity>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify(params),
  });
  clearCsrfTokenCache();
  return identity;
}

export async function getMe(): Promise<Identity> {
  return request<Identity>('/api/v1/auth/me');
}

export async function logout(): Promise<void> {
  try {
    await request<void>('/api/v1/auth/logout', {
      method: 'POST',
    });
  } finally {
    clearCsrfTokenCache();
  }
}
