import { request, fetchCsrfToken } from './client';
import { FileView } from '../types/file';
import { CsrfTokenData } from '../types/auth';

export async function listCaseFiles(caseId: string): Promise<FileView[]> {
  return request<FileView[]>(`/api/v1/cases/${caseId}/files`);
}

/**
 * 上传文件（multipart）。
 * 不能用 request() 包装 JSON body，需要直接发 FormData + 手动附加 CSRF token。
 */
export async function uploadCaseFile(
  caseId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<FileView> {
  // 获取 CSRF token（复用 client 中的缓存）
  const csrf: CsrfTokenData | null = await fetchCsrfToken();

  const formData = new FormData();
  formData.append('file', file);

  return new Promise<FileView>((resolve, reject) => {
    const rejectResponse = (message: string, status: number) => {
      reject(Object.assign(new Error(message), { status }));
    };
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/v1/cases/${caseId}/files`);
    xhr.withCredentials = true;

    if (csrf) {
      xhr.setRequestHeader(csrf.headerName, csrf.token);
    }

    if (onProgress) {
      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      });
    }

    xhr.addEventListener('load', () => {
      try {
        const body = JSON.parse(xhr.responseText);
        if (xhr.status >= 200 && xhr.status < 300 && body.data) {
          resolve(body.data as FileView);
        } else {
          rejectResponse(body.message || `上传失败 (${xhr.status})`, xhr.status);
        }
      } catch {
        rejectResponse(`上传失败 (${xhr.status})`, xhr.status);
      }
    });

    xhr.addEventListener('error', () => reject(new Error('网络错误，上传失败')));
    xhr.addEventListener('abort', () => reject(new Error('上传已取消')));

    xhr.send(formData);
  });
}

/** 通过代理重新校验权限；失败会抛出带 HTTP status 的错误供页面清理缓存。 */
export async function downloadFile(
  fileId: string,
  filename = '文件',
  isCurrent?: () => boolean,
): Promise<void> {
  const response = await fetch(`/api/v1/files/${fileId}/download`, {
    credentials: 'include',
    headers: { Accept: 'application/octet-stream, application/json' },
  });
  if (!response.ok) {
    let message = `下载失败 (${response.status})`;
    if (response.headers.get('content-type')?.includes('application/json')) {
      const body = await response.json().catch(() => null);
      if (body?.message) message = body.message;
    }
    throw Object.assign(new Error(message), { status: response.status });
  }
  const blob = await response.blob();
  if (isCurrent && !isCurrent()) return;
  const disposition = response.headers.get('content-disposition') ?? '';
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition.match(/filename="([^"\r\n]+)"/i)?.[1];
  let downloadName = plainName ?? filename;
  if (encodedName) {
    try {
      downloadName = decodeURIComponent(encodedName);
    } catch {
      /* 使用可显示的文件名 */
    }
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = downloadName;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function deleteFile(fileId: string): Promise<void> {
  return request<void>(`/api/v1/files/${fileId}`, { method: 'DELETE' });
}
