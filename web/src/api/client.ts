import { ApiResponse } from '../types/api';
import { CsrfTokenData } from '../types/auth';

let csrfTokenCache: CsrfTokenData | null = null;
// 同一时刻的写请求共用一次 token 获取，避免并发请求重复初始化会话中的 CSRF。
let csrfPromise: Promise<CsrfTokenData | null> | null = null;

export async function fetchCsrfToken(): Promise<CsrfTokenData | null> {
  if (csrfPromise) return csrfPromise;

  csrfPromise = (async () => {
    try {
      const res = await fetch('/api/v1/auth/csrf', {
        method: 'GET',
        headers: { Accept: 'application/json' },
        credentials: 'include',
      });
      if (!res.ok) {
        csrfTokenCache = null;
        return null;
      }
      const data: ApiResponse<CsrfTokenData> = await res.json();
      if (data && data.data && data.data.token) {
        csrfTokenCache = data.data;
        return data.data;
      }
      return null;
    } catch {
      csrfTokenCache = null;
      return null;
    } finally {
      csrfPromise = null;
    }
  })();

  return csrfPromise;
}

export function clearCsrfTokenCache(): void {
  csrfTokenCache = null;
}

export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | undefined | null>;
  retryCsrf?: boolean;
}

export async function request<T = any>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const { params, retryCsrf = true, headers: customHeaders, method = 'GET', ...rest } = options;

  let url = endpoint;
  if (params) {
    const searchParams = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        searchParams.append(key, String(value));
      }
    });
    const qs = searchParams.toString();
    if (qs) {
      url += (url.includes('?') ? '&' : '?') + qs;
    }
  }

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(customHeaders as Record<string, string>),
  };

  const isMutation = ['POST', 'PATCH', 'PUT', 'DELETE'].includes(method.toUpperCase());

  if (isMutation) {
    if (!csrfTokenCache) {
      await fetchCsrfToken();
    }
    if (csrfTokenCache) {
      headers[csrfTokenCache.headerName] = csrfTokenCache.token;
    }
  }

  if (rest.body && typeof rest.body === 'string' && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(url, {
    method,
    headers,
    credentials: 'include',
    ...rest,
  });

  // 403 可能来自过期 CSRF，刷新 token 后只重试一次；敏感操作可用 retryCsrf: false 禁止自动重发。
  if (response.status === 403 && isMutation && retryCsrf) {
    clearCsrfTokenCache();
    const freshCsrf = await fetchCsrfToken();
    if (freshCsrf) {
      headers[freshCsrf.headerName] = freshCsrf.token;
      const retryResponse = await fetch(url, {
        method,
        headers,
        credentials: 'include',
        ...rest,
      });
      return handleResponse<T>(retryResponse);
    }
  }

  return handleResponse<T>(response);
}

async function handleResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    let errorMessage = `请求失败 (${response.status})`;
    let traceId = response.headers.get('X-Trace-Id') || undefined;
    if (isJson) {
      try {
        const body: ApiResponse<any> = await response.json();
        if (body.message) {
          errorMessage = body.message;
        }
        if (body.traceId) traceId = body.traceId;
      } catch {
        // use default status message
      }
    } else {
      try {
        const text = await response.text();
        if (text) errorMessage = text;
      } catch {
        // ignore
      }
    }
    const err = new Error(errorMessage) as Error & { status: number; traceId?: string };
    // 页面据 HTTP 状态处理撤权和状态冲突，traceId 用于关联服务端审计，不能只保留提示文本。
    err.status = response.status;
    err.traceId = traceId;
    throw err;
  }

  if (isJson) {
    const result: ApiResponse<T> = await response.json();
    return result.data;
  }

  return null as unknown as T;
}
