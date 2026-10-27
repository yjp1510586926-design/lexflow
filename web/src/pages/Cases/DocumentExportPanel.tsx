import React, { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  DocumentExportTask,
  getDocumentExportTask,
  recoverDocumentExport,
  downloadDocumentExport,
} from '../../api/documents';
import { cancelTask } from '../../api/tasks';

const labels: Record<string, string> = {
  QUEUED: '等待处理',
  RUNNING: '正在生成文件',
  SUCCEEDED: '文件已准备好',
  FAILED: '生成失败',
  CANCELLED: '已取消',
};
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

export const DocumentExportPanel: React.FC<{ taskId: string; onAccessLost?: () => void }> = ({
  taskId,
  onAccessLost,
}) => {
  const toast = useToast();
  const [job, setJob] = useState<DocumentExportTask | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const accessLost = useRef(onAccessLost);
  accessLost.current = onAccessLost;
  const alive = useRef(true);
  const lifecycle = useRef(0);
  // 生命周期编号保护轮询和下载，避免切换任务后旧响应或旧文件继续作用于当前页面。
  const stop = useRef(false);
  useEffect(() => {
    const cycle = ++lifecycle.current;
    const current = () => alive.current && lifecycle.current === cycle;
    alive.current = true;
    stop.current = false;
    setJob(null);
    setError('');
    setLoading(true);
    setBusy(false);
    let fetching = false;
    const load = async () => {
      if (fetching || stop.current) return;
      fetching = true;
      try {
        const value = await getDocumentExportTask(taskId);
        if (current()) {
          setJob(value);
          setError('');
          if (terminal.has(value.status)) stop.current = true;
        }
      } catch (e: any) {
        if (current()) {
          setJob(null);
          setError(e.message || '无法读取导出任务');
          stop.current = true;
          if (e.status === 401 || e.status === 403) accessLost.current?.();
        }
      } finally {
        fetching = false;
        if (current()) setLoading(false);
      }
    };
    void load();
    const timer = window.setInterval(load, 3000);
    return () => {
      alive.current = false;
      window.clearInterval(timer);
    };
  }, [taskId]);
  const act = async (kind: 'download' | 'cancel' | 'recover') => {
    if (!job || busy) return;
    setBusy(true);
    setError('');
    const cycle = lifecycle.current;
    const current = () => alive.current && lifecycle.current === cycle;
    try {
      if (kind === 'download') {
        await downloadDocumentExport(job, current);
        if (current()) toast.success(`已开始下载 v${job.versionNo}（TXT）`);
      } else {
        if (kind === 'cancel') await cancelTask(taskId);
        else await recoverDocumentExport(taskId, job.stateVersion);
        const value = await getDocumentExportTask(taskId);
        if (current()) {
          setJob(value);
          stop.current = terminal.has(value.status);
        }
      }
    } catch (e: any) {
      if (current()) {
        setError(e.message || '操作失败');
        if (e.status === 401 || e.status === 403) {
          setJob(null);
          stop.current = true;
          accessLost.current?.();
        } else if (kind === 'download' && e.status === 409) {
          try {
            const value = await getDocumentExportTask(taskId);
            if (current()) setJob(value);
          } catch (refresh: any) {
            if (current() && (refresh.status === 401 || refresh.status === 403)) {
              setJob(null);
              setError(refresh.message || '无法读取导出任务');
              stop.current = true;
              accessLost.current?.();
            }
          }
        }
      }
    } finally {
      if (current()) setBusy(false);
    }
  };
  if (loading) return <Spinner text="读取导出任务…" />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 13 }}>
          {error}
        </p>
      )}
      {job && (
        <>
          <p style={{ fontSize: 15, fontWeight: 600 }}>
            {job.title} · v{job.versionNo}
          </p>
          <p style={{ fontSize: 13 }}>
            任务 #{job.taskId} ·{' '}
            {job.expired ? '导出文件已到期' : (labels[job.status] ?? job.status)}
          </p>
          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            TXT 文件固定为提交时的已保存版本。之后的编辑不会改变这次导出；草稿仍为草稿。
          </p>
          {job.errorMessage && (
            <p style={{ color: 'var(--danger-text)', fontSize: 13 }}>{job.errorMessage}</p>
          )}
          {job.status === 'FAILED' && (
            <p style={{ fontSize: 12 }}>请返回案件文书检查后重新导出。</p>
          )}
          {job.expired ? (
            <p style={{ fontSize: 12 }}>请返回案件文书重新导出；已保存的文书和版本仍可查看。</p>
          ) : (
            job.expiresAt && (
              <p style={{ fontSize: 12 }}>
                下载截止：{new Date(job.expiresAt).toLocaleString('zh-CN')}
              </p>
            )
          )}
          <div style={{ display: 'flex', gap: 10 }}>
            {job.status === 'SUCCEEDED' && !job.expired && (
              <Button loading={busy} onClick={() => act('download')}>
                下载 TXT
              </Button>
            )}
            {!terminal.has(job.status) && (
              <Button variant="danger" loading={busy} onClick={() => act('cancel')}>
                取消导出
              </Button>
            )}
            {job.status === 'QUEUED' && (
              <Button variant="secondary" loading={busy} onClick={() => act('recover')}>
                继续处理
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
};
