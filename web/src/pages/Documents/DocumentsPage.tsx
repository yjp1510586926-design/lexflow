import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import { Modal } from '../../components/common/Modal';
import {
  FileTextIcon,
  ShieldIcon,
  ActivityIcon,
  RefreshCwIcon,
  PlusIcon,
  TrashIcon,
  AlertCircleIcon,
  SearchIcon,
} from '../../components/common/Icons';
import { listCaseFiles, uploadCaseFile, downloadFile, deleteFile } from '../../api/files';
import { listAuditLogs } from '../../api/audit';
import { listCases } from '../../api/cases';
import { FileView, FileStatus } from '../../types/file';
import { AuditLogView } from '../../types/audit';
import { CaseView } from '../../types/case';

// ── 工具函数 ──────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function mimeLabel(mime: string | null): string {
  if (!mime) return '—';
  const map: Record<string, string> = {
    'application/pdf': 'PDF',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'DOCX',
    'text/plain': 'TXT',
    'image/jpeg': 'JPEG',
    'image/png': 'PNG',
  };
  return map[mime] ?? mime.split('/')[1]?.toUpperCase() ?? '—';
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    'case:create': '创建案件',
    'case:update': '更新案件',
    'case:member:add': '添加成员',
    'case:member:remove': '移除成员',
    'case:member:role': '调整成员角色',
    'case:owner:transfer': '转交负责人',
    'file:upload': '上传文件',
    'file:download': '下载文件',
    'file:delete': '删除文件',
    'user:create': '创建用户',
    'user:status': '调整用户状态',
    'auth:login': '登录',
    'auth:logout': '退出登录',
    'request:failure': '请求被拒绝或失败',
    'organization:user:create': '创建律所用户',
    'organization:membership:update': '调整律所成员权限',
    'organization:initialize': '初始化律所管理员',
    'case:read': '查看案件',
    'case:member': '管理案件成员',
    'file:read': '读取文件',
    'document:read': '查看文书',
    'document:write': '编辑文书',
    'task:read': '查看任务',
    'task:cancel': '取消任务',
    'knowledge:manage': '管理知识库',
    'knowledge:read': '读取知识库',
    'ai:query': 'AI 问答',
    'ai:review': 'AI 审查',
    'audit:read': '查看审计',
    'tenant:user:manage': '管理律所用户',
    'tenant:role:manage': '管理律所角色',
  };
  return map[action] ?? action;
}

const FILE_STATUS_CONFIG: Record<FileStatus, { variant: any; label: string }> = {
  AVAILABLE: { variant: 'emerald', label: '已就绪' },
  UPLOADING: { variant: 'amber', label: '上传中' },
  FAILED: { variant: 'rose', label: '失败' },
  DELETED: { variant: 'slate', label: '已删除' },
};

const ALLOWED_EXTS = '.pdf,.docx,.txt,.png,.jpeg,.jpg';
const MAX_SIZE = 50 * 1024 * 1024;

// ── 子组件：文件列表 Tab ───────────────────────────────────────────────────────

interface FilesWithCase {
  file: FileView;
  caseTitle: string;
  caseNo: string;
}

const GlobalFilesTab: React.FC = () => {
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [cases, setCases] = useState<CaseView[]>([]);
  const [selectedCaseId, setSelectedCaseId] = useState<string>('');
  const [files, setFiles] = useState<FilesWithCase[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingCases, setLoadingCases] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [deleteTarget, setDeleteTarget] = useState<FileView | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [denied, setDenied] = useState(false);
  const alive = useRef(true),
    deniedRef = useRef(false),
    generation = useRef(0),
    listRead = useRef(0);
  const currentCase = useRef(selectedCaseId);
  const uploadInFlight = useRef(false),
    deleteInFlight = useRef(false);
  currentCase.current = selectedCaseId;
  const current = useCallback(
    (access: number, caseId?: string) =>
      alive.current &&
      !deniedRef.current &&
      access === generation.current &&
      (caseId === undefined || caseId === currentCase.current),
    [],
  );
  const clearFiles = useCallback(() => {
    listRead.current++;
    setFiles([]);
    setDeleteTarget(null);
    setLoading(false);
    setUploading(false);
    setUploadProgress(0);
    setDeleting(false);
    uploadInFlight.current = false;
    deleteInFlight.current = false;
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);
  const handleError = useCallback(
    (error: any, fallback: string) => {
      if (error.status === 401 || error.status === 403) {
        deniedRef.current = true;
        generation.current++;
        clearFiles();
        setCases([]);
        setSelectedCaseId('');
        setLoadingCases(false);
        setDenied(true);
      }
      toast.error(error instanceof Error ? error.message : fallback);
    },
    [clearFiles, toast],
  );
  const selectCase = useCallback(
    (id: string) => {
      generation.current++;
      currentCase.current = id;
      clearFiles();
      setSelectedCaseId(id);
    },
    [clearFiles],
  );

  useEffect(() => {
    alive.current = true;
    const access = generation.current;
    setLoadingCases(true);
    listCases({ size: 100 })
      .then((result) => {
        if (!current(access)) return;
        setCases(result.items);
        selectCase(result.items[0]?.id ?? '');
        setLoadingCases(false);
      })
      .catch((error) => {
        if (current(access)) {
          handleError(error, '加载案件失败');
          setLoadingCases(false);
        }
      });
    return () => {
      alive.current = false;
      generation.current++;
      listRead.current++;
    };
  }, [current, handleError, selectCase]);

  const fetchFiles = useCallback(async () => {
    if (!selectedCaseId || deniedRef.current) return;
    const access = generation.current,
      caseId = selectedCaseId,
      round = ++listRead.current;
    setLoading(true);
    try {
      const data = await listCaseFiles(caseId);
      if (!current(access, caseId) || round !== listRead.current) return;
      const caseInfo = cases.find((c) => c.id === caseId);
      setFiles(
        data.map((file) => ({
          file,
          caseTitle: caseInfo?.title ?? '未知案件',
          caseNo: caseInfo?.caseNo ?? '',
        })),
      );
    } catch (error: any) {
      if (
        current(access, caseId) &&
        (error.status === 401 || error.status === 403 || round === listRead.current)
      )
        handleError(error, '加载文件失败');
    } finally {
      if (current(access, caseId) && round === listRead.current) setLoading(false);
    }
  }, [selectedCaseId, cases, current, handleError]);

  useEffect(() => {
    void fetchFiles();
  }, [fetchFiles]);

  const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file || !selectedCaseId || deniedRef.current || uploadInFlight.current) return;
    event.target.value = '';
    const ext = '.' + (file.name.split('.').pop()?.toLowerCase() ?? '');
    if (!ALLOWED_EXTS.split(',').includes(ext)) {
      toast.error(`不支持的文件类型：${ext}`);
      return;
    }
    if (file.size > MAX_SIZE) {
      toast.error(`文件超过 50MB 限制（${formatBytes(file.size)}）`);
      return;
    }
    const access = generation.current,
      caseId = selectedCaseId;
    uploadInFlight.current = true;
    setUploading(true);
    setUploadProgress(0);
    try {
      const newFile = await uploadCaseFile(caseId, file, (percent) => {
        if (current(access, caseId)) setUploadProgress(percent);
      });
      if (!current(access, caseId)) return;
      listRead.current++;
      setLoading(false);
      const caseInfo = cases.find((c) => c.id === caseId);
      setFiles((items) => [
        { file: newFile, caseTitle: caseInfo?.title ?? '', caseNo: caseInfo?.caseNo ?? '' },
        ...items,
      ]);
      toast.success(`《${newFile.displayName}》上传成功`);
    } catch (error) {
      if (current(access, caseId)) handleError(error, '上传失败');
    } finally {
      if (current(access, caseId)) {
        uploadInFlight.current = false;
        setUploading(false);
        setUploadProgress(0);
      }
    }
  };

  const handleDeleteConfirm = async () => {
    if (!deleteTarget || deniedRef.current || deleteInFlight.current) return;
    const access = generation.current,
      caseId = selectedCaseId;
    deleteInFlight.current = true;
    setDeleting(true);
    try {
      await deleteFile(deleteTarget.id);
      if (!current(access, caseId)) return;
      listRead.current++;
      setLoading(false);
      toast.success(`《${deleteTarget.displayName}》已删除`);
      setFiles((items) => items.filter((item) => item.file.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (error) {
      if (current(access, caseId)) handleError(error, '删除失败');
    } finally {
      if (current(access, caseId)) {
        deleteInFlight.current = false;
        setDeleting(false);
      }
    }
  };

  const download = async (file: FileView) => {
    if (deniedRef.current) return;
    const access = generation.current,
      caseId = selectedCaseId;
    try {
      await downloadFile(file.id, file.displayName, () => current(access, caseId));
    } catch (error) {
      if (current(access, caseId)) handleError(error, '下载失败');
    }
  };

  if (denied)
    return (
      <p role="alert" style={{ color: 'var(--danger-text)' }}>
        登录状态或文件权限已失效。旧资料已清空，请重新打开页面或联系管理员。
      </p>
    );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* 案件选择器 + 上传工具栏 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
          padding: '14px 20px',
          backgroundColor: 'var(--bg-subtle)',
          borderRadius: 'var(--radius-md)',
          border: '1px solid var(--border-default)',
        }}
      >
        <div style={{ flex: 1, minWidth: 220 }}>
          <label
            style={{ fontSize: 12, color: 'var(--text-muted)', display: 'block', marginBottom: 4 }}
          >
            选择案件
          </label>
          {loadingCases ? (
            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>加载案件中...</div>
          ) : (
            <select
              className="form-select"
              value={selectedCaseId}
              onChange={(e) => selectCase(e.target.value)}
              style={{ height: 32, fontSize: 12 }}
            >
              {cases.length === 0 && <option value="">暂无可访问案件</option>}
              {cases.map((c) => (
                <option key={c.id} value={c.id}>
                  [{c.caseNo}] {c.title}
                </option>
              ))}
            </select>
          )}
        </div>

        <div style={{ display: 'flex', gap: 8, paddingTop: 18 }}>
          <Button
            variant="ghost"
            size="sm"
            icon={<RefreshCwIcon size={13} />}
            onClick={fetchFiles}
            disabled={loading || !selectedCaseId}
          >
            刷新
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept={ALLOWED_EXTS}
            style={{ display: 'none' }}
            onChange={handleFileChange}
          />
          <Button
            variant="primary"
            size="sm"
            icon={<PlusIcon size={13} />}
            loading={uploading}
            disabled={!selectedCaseId || loadingCases}
            onClick={() => fileInputRef.current?.click()}
          >
            {uploading ? `${uploadProgress}%` : '上传文件'}
          </Button>
        </div>
      </div>

      {/* 进度条 */}
      {uploading && (
        <div
          style={{
            height: 4,
            backgroundColor: 'var(--border-default)',
            borderRadius: 2,
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${uploadProgress}%`,
              backgroundColor: 'var(--brand-blue)',
              transition: 'width 0.2s ease',
            }}
          />
        </div>
      )}

      {/* 文件表格 */}
      {loading ? (
        <div style={{ padding: '32px 0' }}>
          <Spinner text="加载文件列表..." />
        </div>
      ) : files.length === 0 ? (
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 10,
            padding: '40px 24px',
            border: '1px dashed var(--border-strong)',
            borderRadius: 'var(--radius-md)',
            backgroundColor: 'var(--bg-subtle)',
          }}
        >
          <FileTextIcon size={36} style={{ color: 'var(--text-tertiary)' }} />
          <p style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-main)' }}>
            {selectedCaseId ? '该案件暂无文件' : '请先选择案件'}
          </p>
        </div>
      ) : (
        <div className="table-responsive">
          <table className="data-table">
            <thead>
              <tr>
                <th>文件名</th>
                <th>格式</th>
                <th>大小</th>
                <th>状态</th>
                <th style={{ maxWidth: 160 }}>SHA-256</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {files.map(({ file: f }) => {
                const { variant, label } = FILE_STATUS_CONFIG[f.status] ?? {
                  variant: 'slate',
                  label: f.status,
                };
                return (
                  <tr key={f.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <div
                          style={{
                            width: 28,
                            height: 28,
                            backgroundColor: 'var(--bg-subtle)',
                            borderRadius: 'var(--radius-xs)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            flexShrink: 0,
                          }}
                        >
                          <FileTextIcon size={14} style={{ color: 'var(--text-secondary)' }} />
                        </div>
                        <span
                          style={{
                            fontSize: 13,
                            fontWeight: 500,
                            maxWidth: 220,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                          }}
                          title={f.displayName}
                        >
                          {f.displayName}
                        </span>
                      </div>
                    </td>
                    <td>
                      <Badge variant="slate">{mimeLabel(f.mimeType)}</Badge>
                    </td>
                    <td style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                      {formatBytes(f.sizeBytes)}
                    </td>
                    <td>
                      <Badge variant={variant}>{label}</Badge>
                    </td>
                    <td>
                      {f.sha256 ? (
                        <code
                          style={{
                            fontSize: 10,
                            fontFamily: 'var(--font-mono)',
                            color: 'var(--text-muted)',
                            background: 'var(--bg-subtle)',
                            padding: '2px 5px',
                            borderRadius: 'var(--radius-xs)',
                            display: 'inline-block',
                            maxWidth: 140,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            verticalAlign: 'middle',
                          }}
                          title={f.sha256}
                        >
                          {f.sha256}
                        </code>
                      ) : (
                        <span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>—</span>
                      )}
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <Button
                          variant="ghost"
                          size="sm"
                          icon={<ShieldIcon size={12} />}
                          disabled={f.status !== 'AVAILABLE'}
                          onClick={() => void download(f)}
                        >
                          下载
                        </Button>
                        <Button
                          variant="danger"
                          size="sm"
                          icon={<TrashIcon size={12} />}
                          onClick={() => setDeleteTarget(f)}
                        >
                          删除
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* 删除确认弹窗 */}
      <Modal isOpen={!!deleteTarget} onClose={() => setDeleteTarget(null)} title="确认删除文件">
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 20 }}>
          即将永久删除文件 <strong>《{deleteTarget?.displayName}》</strong>，此操作不可撤销。
        </p>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
          <Button variant="secondary" size="md" onClick={() => setDeleteTarget(null)}>
            取消
          </Button>
          <Button
            variant="danger"
            size="md"
            loading={deleting}
            icon={<TrashIcon size={14} />}
            onClick={handleDeleteConfirm}
          >
            确认删除
          </Button>
        </div>
      </Modal>
    </div>
  );
};

// ── 子组件：审计日志 Tab ──────────────────────────────────────────────────────

const PermissionState: React.FC<{ value: unknown }> = ({ value }) => {
  if (!value || typeof value !== 'object') return <p>无成员资格</p>;
  const state = value as Record<string, unknown>;
  const roleNames: Record<string, string> = {
    TENANT_ADMIN: '律所管理员',
    LAWYER: '律师',
    READER: '只读成员',
    OWNER: '案件负责人',
    EDITOR: '编辑成员',
  };
  const strings = (items: unknown) =>
    Array.isArray(items) ? items.filter((item): item is string => typeof item === 'string') : [];
  return (
    <div style={{ display: 'grid', gap: 6, overflowWrap: 'anywhere' }}>
      {typeof state.userId === 'string' && <p>用户 #{state.userId}</p>}
      {typeof state.ownerId === 'string' && <p>负责人 #{state.ownerId}</p>}
      {typeof state.status === 'string' && (
        <p>
          状态：
          {state.status === 'ACTIVE'
            ? '有效'
            : state.status === 'DISABLED'
              ? '已禁用'
              : state.status}
        </p>
      )}
      {'role' in state && (
        <p>
          案件角色：
          {typeof state.role === 'string' ? roleNames[state.role] || state.role : '未加入'}
        </p>
      )}
      {Array.isArray(state.roles) && (
        <p>
          律所角色：
          {strings(state.roles)
            .map((role) => roleNames[role] || role)
            .join('、') || '无'}
        </p>
      )}
      {Array.isArray(state.permissions) && (
        <p>权限：{strings(state.permissions).map(actionLabel).join('、') || '无'}</p>
      )}
      {Array.isArray(state.members) &&
        state.members.map((member, index) => <PermissionState key={index} value={member} />)}
    </div>
  );
};

const AuditLogsTab: React.FC = () => {
  const [logs, setLogs] = useState<AuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [actionFilter, setActionFilter] = useState('');
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<AuditLogView | null>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const SIZE = 20;

  const fetch = useCallback(
    async (p = 1, action = actionFilter) => {
      const current = ++generation.current;
      setLoading(true);
      setError('');
      setSelected(null);
      try {
        const result = await listAuditLogs({ page: p, size: SIZE, action });
        if (!mounted.current || current !== generation.current) return;
        setLogs(result.items);
        setTotal(result.total);
        setPage(p);
      } catch (err: any) {
        if (!mounted.current || current !== generation.current) return;
        setLogs([]);
        setTotal(0);
        setSelected(null);
        setError(err.message || '加载审计日志失败');
      } finally {
        if (mounted.current && current === generation.current) setLoading(false);
      }
    },
    [actionFilter],
  );

  useEffect(() => {
    mounted.current = true;
    void fetch(1);
    return () => {
      mounted.current = false;
      generation.current++;
    };
  }, []);

  const totalPages = Math.ceil(total / SIZE);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 过滤栏 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div className="search-box" style={{ maxWidth: 260 }}>
          <SearchIcon size={14} className="search-icon" />
          <input
            className="form-input"
            placeholder="过滤操作类型，如 file:upload"
            maxLength={64}
            value={actionFilter}
            onChange={(e) => setActionFilter(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && fetch(1, actionFilter)}
            style={{ paddingLeft: 30 }}
          />
        </div>
        <Button variant="secondary" size="sm" onClick={() => fetch(1, actionFilter)}>
          查询
        </Button>
        <Button
          variant="ghost"
          size="sm"
          icon={<RefreshCwIcon size={13} />}
          onClick={() => {
            setActionFilter('');
            fetch(1, '');
          }}
        >
          重置
        </Button>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--text-muted)' }}>
          共 {total} 条记录
        </span>
      </div>

      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {error}
        </p>
      )}
      {loading ? (
        <div style={{ padding: '32px 0' }}>
          <Spinner text="加载审计日志..." />
        </div>
      ) : error ? null : logs.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '40px 24px',
            border: '1px dashed var(--border-strong)',
            borderRadius: 'var(--radius-md)',
            backgroundColor: 'var(--bg-subtle)',
          }}
        >
          <ActivityIcon size={36} style={{ color: 'var(--text-tertiary)', marginBottom: 10 }} />
          <p style={{ fontSize: 14, color: 'var(--text-muted)' }}>暂无审计记录</p>
        </div>
      ) : (
        <>
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th>操作类型</th>
                  <th>对象</th>
                  <th>执行人</th>
                  <th>结果</th>
                  <th>时间</th>
                  <th>追踪 ID</th>
                  <th>详情</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => (
                  <tr key={log.id}>
                    <td>
                      <code
                        style={{
                          fontSize: 11,
                          background: 'var(--bg-subtle)',
                          padding: '2px 6px',
                          borderRadius: 'var(--radius-xs)',
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--text-secondary)',
                        }}
                      >
                        {log.action}
                      </code>
                      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>
                        {actionLabel(log.action)}
                      </div>
                    </td>
                    <td style={{ fontSize: 12 }}>
                      {log.objectType && (
                        <span style={{ color: 'var(--text-secondary)' }}>{log.objectType}</span>
                      )}
                      {log.objectId && (
                        <code
                          style={{
                            fontSize: 11,
                            marginLeft: 4,
                            fontFamily: 'var(--font-mono)',
                            color: 'var(--text-muted)',
                          }}
                        >
                          #{log.objectId}
                        </code>
                      )}
                    </td>
                    <td style={{ fontSize: 12 }}>
                      <span style={{ fontWeight: 500 }}>{log.actorName ?? '—'}</span>
                      {log.actorId && (
                        <code
                          style={{
                            fontSize: 10,
                            marginLeft: 4,
                            fontFamily: 'var(--font-mono)',
                            color: 'var(--text-muted)',
                          }}
                        >
                          #{log.actorId}
                        </code>
                      )}
                    </td>
                    <td>
                      <Badge variant={log.result === 'SUCCESS' ? 'emerald' : 'rose'}>
                        {log.result === 'SUCCESS'
                          ? '成功'
                          : log.result === 'DENIED'
                            ? '被拒绝'
                            : '失败'}
                      </Badge>
                    </td>
                    <td
                      style={{ fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}
                    >
                      {log.createdAt?.replace('T', ' ').replace(/\.\d+$/, '') ?? '—'}
                    </td>
                    <td>
                      <code
                        style={{
                          fontSize: 10,
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--text-tertiary)',
                          maxWidth: 120,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                          display: 'inline-block',
                          verticalAlign: 'middle',
                        }}
                        title={log.traceId}
                      >
                        {log.traceId?.slice(0, 8)}…
                      </code>
                    </td>
                    <td>
                      <Button size="sm" variant="ghost" onClick={() => setSelected(log)}>
                        查看详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 分页 */}
          {totalPages > 1 && (
            <div className="pagination-wrapper">
              <span>
                第 {page} / {totalPages} 页，共 {total} 条
              </span>
              <div className="pagination-actions">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page <= 1}
                  onClick={() => fetch(page - 1)}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page >= totalPages}
                  onClick={() => fetch(page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </>
      )}
      <Modal
        isOpen={!!selected}
        onClose={() => setSelected(null)}
        title="审计详情"
        subtitle={selected ? actionLabel(selected.action) : ''}
      >
        {selected && (
          <div style={{ display: 'grid', gap: 16 }}>
            <div>
              <p className="form-label">请求追踪 ID</p>
              <code style={{ overflowWrap: 'anywhere' }}>{selected.traceId}</code>
            </div>
            {selected.detail && ('before' in selected.detail || 'after' in selected.detail) && (
              <>
                <div>
                  <p className="form-label">变更前</p>
                  <PermissionState value={selected.detail.before} />
                </div>
                <div>
                  <p className="form-label">变更后</p>
                  <PermissionState value={selected.detail.after} />
                </div>
              </>
            )}
            {typeof selected.detail?.method === 'string' && (
              <p>
                请求：{selected.detail.method} {String(selected.detail.route || '')}
              </p>
            )}
            {typeof selected.detail?.httpStatus === 'number' && (
              <p>响应状态：{selected.detail.httpStatus}</p>
            )}
            {!selected.detail && <p className="form-hint">该记录未保存变更详情。</p>}
          </div>
        )}
      </Modal>
    </div>
  );
};

// ── 主页面组件 ─────────────────────────────────────────────────────────────────

type DocTab = 'files' | 'audit';

export const DocumentsPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<DocTab>('files');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* 页面 Tab 导航 */}
      <div className="nav-tabs">
        <button
          className={`tab-btn ${activeTab === 'files' ? 'active' : ''}`}
          onClick={() => setActiveTab('files')}
        >
          <FileTextIcon size={15} />
          <span>案件文件与证据</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'audit' ? 'active' : ''}`}
          onClick={() => setActiveTab('audit')}
        >
          <ActivityIcon size={15} />
          <span>操作审计日志</span>
        </button>
      </div>

      {/* Tab 内容 */}
      {activeTab === 'files' && (
        <Card
          title="全局文件与证据管理"
          subtitle="跨案件查看可访问的卷宗材料，支持上传、下载及 SHA-256 完整性核验"
        >
          <GlobalFilesTab />
        </Card>
      )}

      {activeTab === 'audit' && (
        <Card
          title="操作审计日志"
          subtitle="查看授权范围内的关键操作，含登录、案件变更、文件下载与成员管理"
        >
          <AuditLogsTab />
        </Card>
      )}
    </div>
  );
};
