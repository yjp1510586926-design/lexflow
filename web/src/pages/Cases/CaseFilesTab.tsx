import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  FileTextIcon,
  PlusIcon,
  TrashIcon,
  RefreshCwIcon,
  ShieldIcon,
  AlertCircleIcon,
} from '../../components/common/Icons';
import { listCaseFiles, uploadCaseFile, downloadFile, deleteFile } from '../../api/files';
import { FileView, FileStatus } from '../../types/file';
import { CaseView } from '../../types/case';
import { submitTask } from '../../api/tasks';
import { ContractReviewPanel } from './ContractReviewPanel';

interface CaseFilesTabProps {
  caseInfo: CaseView;
}

// ── 工具函数 ──────────────────────────────────

function formatBytes(bytes: number): string {
  if (bytes === 0) return '—';
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

const ALLOWED_EXTS = '.pdf,.docx,.txt,.png,.jpeg,.jpg';
const MAX_SIZE = 50 * 1024 * 1024;

// ── 主组件 ────────────────────────────────────

export const CaseFilesTab: React.FC<CaseFilesTabProps> = ({ caseInfo }) => {
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [files, setFiles] = useState<FileView[]>([]);
  const [loading, setLoading] = useState(true);

  // 上传进度状态
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadingName, setUploadingName] = useState('');

  // 删除确认模态框
  const [deleteTarget, setDeleteTarget] = useState<FileView | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [parsingFileId, setParsingFileId] = useState<string | null>(null);
  const [reviewTarget, setReviewTarget] = useState<FileView | null>(null);
  const [denied, setDenied] = useState(false);
  const alive = useRef(true);
  const accessGeneration = useRef(0);
  const listRead = useRef(0);
  const deniedRef = useRef(false);
  const currentCase = useRef(caseInfo.id);
  currentCase.current = caseInfo.id;
  const uploadInFlight = useRef(false);
  const deleteInFlight = useRef(false);
  const parseInFlight = useRef(false);

  const clear = useCallback(() => {
    setFiles([]);
    setDeleteTarget(null);
    setReviewTarget(null);
    setUploading(false);
    setUploadProgress(0);
    setUploadingName('');
    setDeleting(false);
    setParsingFileId(null);
    setLoading(false);
    uploadInFlight.current = false;
    deleteInFlight.current = false;
    parseInFlight.current = false;
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  const current = useCallback(
    (access: number, caseId: string) =>
      alive.current &&
      !deniedRef.current &&
      access === accessGeneration.current &&
      caseId === currentCase.current,
    [],
  );

  const handleError = useCallback(
    (error: any, fallback: string) => {
      if (error.status === 401 || error.status === 403) {
        deniedRef.current = true;
        accessGeneration.current++;
        listRead.current++;
        clear();
        setDenied(true);
      }
      toast.error(error instanceof Error ? error.message : fallback);
    },
    [clear, toast],
  );

  const loseAccess = useCallback(() => {
    if (!deniedRef.current)
      handleError(
        Object.assign(new Error('案件文件权限已失效'), { status: 403 }),
        '案件文件权限已失效',
      );
  }, [handleError]);

  const isArchived = caseInfo.status === 'ARCHIVED';

  const handleParse = async (file: FileView) => {
    if (!file.versionId || deniedRef.current || parseInFlight.current) return;
    parseInFlight.current = true;
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    setParsingFileId(file.id);
    try {
      const task = await submitTask({
        caseId: caseInfo.id,
        type: 'DOCUMENT_PARSE',
        resourceVersionId: file.versionId,
      });
      if (!current(access, caseId)) return;
      toast.success(`文本提取任务 #${task.id} 已提交，可在任务记录查看结果`);
    } catch (error) {
      if (current(access, caseId)) handleError(error, '任务提交失败');
    } finally {
      if (current(access, caseId)) {
        parseInFlight.current = false;
        setParsingFileId(null);
      }
    }
  };

  // ── 加载文件列表 ────────────────────────

  const fetchFiles = useCallback(async () => {
    if (deniedRef.current) return;
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    const round = ++listRead.current;
    setLoading(true);
    try {
      const data = await listCaseFiles(caseInfo.id);
      if (!current(access, caseId) || round !== listRead.current) return;
      setFiles(data);
    } catch (err: any) {
      if (!current(access, caseId)) return;
      if (err.status === 401 || err.status === 403 || round === listRead.current)
        handleError(err, '获取文件列表失败');
    } finally {
      if (current(access, caseId) && round === listRead.current) setLoading(false);
    }
  }, [caseInfo.id, current, handleError]);

  useEffect(() => {
    alive.current = true;
    accessGeneration.current++;
    deniedRef.current = false;
    setDenied(false);
    clear();
    void fetchFiles();
    return () => {
      alive.current = false;
      accessGeneration.current++;
      listRead.current++;
    };
  }, [clear, fetchFiles]);

  // ── 客户端预校验 + 上传 ────────────────────────

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || deniedRef.current || uploadInFlight.current) return;

    // 重置 input 以允许重复选择同一文件
    e.target.value = '';

    // 客户端预校验（后端还会完整校验）
    const ext = '.' + (file.name.split('.').pop()?.toLowerCase() ?? '');
    if (!ALLOWED_EXTS.split(',').includes(ext)) {
      toast.error(`不支持的文件类型：${ext}，请上传 PDF、DOCX、TXT、PNG 或 JPEG`);
      return;
    }
    if (file.size > MAX_SIZE) {
      toast.error(`文件大小超过 50MB 限制（当前 ${formatBytes(file.size)}）`);
      return;
    }

    uploadInFlight.current = true;
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    setUploading(true);
    setUploadProgress(0);
    setUploadingName(file.name);

    try {
      const newFile = await uploadCaseFile(caseInfo.id, file, (pct) => {
        if (current(access, caseId)) setUploadProgress(pct);
      });
      if (!current(access, caseId)) return;
      listRead.current++;
      setLoading(false);
      toast.success(`《${newFile.displayName}》上传成功并完成完整性校验`);
      setFiles((prev) => [newFile, ...prev]);
    } catch (err: any) {
      if (current(access, caseId)) handleError(err, '文件上传失败');
    } finally {
      if (current(access, caseId)) {
        uploadInFlight.current = false;
        setUploading(false);
        setUploadProgress(0);
        setUploadingName('');
      }
    }
  };

  // ── 下载 ────────────────────────

  const handleDownload = async (file: FileView) => {
    if (deniedRef.current) return;
    if (file.status !== 'AVAILABLE') {
      toast.error('文件尚未就绪或已失败，无法下载');
      return;
    }
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    try {
      await downloadFile(file.id, file.displayName, () => current(access, caseId));
    } catch (error) {
      if (current(access, caseId)) handleError(error, '下载文件失败');
    }
  };

  // ── 删除 ────────────────────────

  const handleDeleteConfirm = async () => {
    if (!deleteTarget || deniedRef.current || deleteInFlight.current) return;
    deleteInFlight.current = true;
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    setDeleting(true);
    try {
      await deleteFile(deleteTarget.id);
      if (!current(access, caseId)) return;
      listRead.current++;
      setLoading(false);
      toast.success(`《${deleteTarget.displayName}》已从卷宗中移除`);
      setFiles((prev) => prev.filter((f) => f.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (err: any) {
      if (current(access, caseId)) handleError(err, '删除文件失败');
    } finally {
      if (current(access, caseId)) {
        deleteInFlight.current = false;
        setDeleting(false);
      }
    }
  };

  // ── 状态 Badge ────────────────────────

  const statusBadge = (status: FileStatus) => {
    const map: Record<FileStatus, { variant: any; label: string }> = {
      AVAILABLE: { variant: 'emerald', label: '已就绪' },
      UPLOADING: { variant: 'amber', label: '上传中' },
      FAILED: { variant: 'rose', label: '上传失败' },
      DELETED: { variant: 'slate', label: '已删除' },
    };
    const { variant, label } = map[status] ?? { variant: 'slate', label: status };
    return <Badge variant={variant}>{label}</Badge>;
  };

  // ── 渲染 ────────────────────────

  if (denied)
    return (
      <p role="alert" style={{ color: 'var(--danger-text)' }}>
        登录状态或案件文件权限已失效。旧资料已清空，请重新打开案件或联系管理员。
      </p>
    );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* 工具栏 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div>
          <h3 style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-main)' }}>案件电子卷宗</h3>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
            支持 PDF、DOCX、TXT、PNG、JPEG · 单文件上限 50MB · SHA-256 完整性校验
          </p>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
            文字提取支持 TXT、DOCX 和含文字层 PDF；扫描版 PDF 暂不支持。
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <Button
            variant="ghost"
            size="sm"
            icon={<RefreshCwIcon size={14} />}
            onClick={fetchFiles}
            disabled={loading}
          >
            刷新
          </Button>
          {!isArchived && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                id="caseFileUpload"
                accept={ALLOWED_EXTS}
                style={{ display: 'none' }}
                onChange={handleFileChange}
              />
              <Button
                variant="primary"
                size="sm"
                icon={<PlusIcon size={14} />}
                loading={uploading}
                onClick={() => fileInputRef.current?.click()}
              >
                {uploading ? `上传中 ${uploadProgress}%` : '上传材料'}
              </Button>
            </>
          )}
        </div>
      </div>

      {/* 上传进度条 */}
      {uploading && (
        <div
          style={{
            padding: '10px 14px',
            backgroundColor: 'var(--brand-blue-subtle)',
            borderRadius: 'var(--radius-sm)',
            border: '1px solid #bfdbfe',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 6,
              fontSize: 12,
            }}
          >
            <span style={{ color: 'var(--brand-blue-hover)', fontWeight: 500 }}>
              正在上传：{uploadingName}
            </span>
            <span style={{ color: 'var(--brand-blue-hover)', fontWeight: 600 }}>
              {uploadProgress}%
            </span>
          </div>
          <div
            style={{
              height: 4,
              backgroundColor: '#bfdbfe',
              borderRadius: 2,
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${uploadProgress}%`,
                backgroundColor: 'var(--brand-blue)',
                borderRadius: 2,
                transition: 'width 0.2s ease',
              }}
            />
          </div>
        </div>
      )}

      {/* 归档提示 */}
      {isArchived && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '8px 12px',
            backgroundColor: 'var(--warning-bg)',
            border: '1px solid var(--warning-border)',
            borderRadius: 'var(--radius-sm)',
            fontSize: 12,
            color: 'var(--warning-text)',
          }}
        >
          <AlertCircleIcon size={14} />
          <span>案件已归档，卷宗材料为只读状态，不允许上传或删除。</span>
        </div>
      )}

      {/* 文件列表 */}
      {loading ? (
        <div style={{ padding: '32px 0' }}>
          <Spinner text="正在加载卷宗目录..." />
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
            案件卷宗材料目录为空
          </p>
          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            {isArchived
              ? '已归档案件，无可查看材料'
              : '点击「上传材料」添加证据文件、起诉书、代理合同等卷宗材料'}
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
                <th>SHA-256 指纹</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {files.map((f) => (
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
                          color: 'var(--text-main)',
                          maxWidth: 240,
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
                  <td style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                    {formatBytes(f.sizeBytes)}
                  </td>
                  <td>{statusBadge(f.status)}</td>
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
                          maxWidth: 160,
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
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={<ShieldIcon size={13} />}
                        disabled={f.status !== 'AVAILABLE'}
                        onClick={() => void handleDownload(f)}
                        title="代理下载（重新校验权限）"
                      >
                        下载
                      </Button>
                      {[
                        'text/plain',
                        'application/pdf',
                        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                      ].includes(f.mimeType ?? '') &&
                        f.status === 'AVAILABLE' &&
                        f.versionId && (
                          <Button
                            variant="secondary"
                            size="sm"
                            loading={parsingFileId === f.id}
                            onClick={() => handleParse(f)}
                          >
                            提取文字
                          </Button>
                        )}
                      {!isArchived &&
                        [
                          'text/plain',
                          'application/pdf',
                          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                        ].includes(f.mimeType ?? '') &&
                        f.status === 'AVAILABLE' &&
                        f.versionId && (
                          <Button variant="secondary" size="sm" onClick={() => setReviewTarget(f)}>
                            审查合同
                          </Button>
                        )}
                      {!isArchived && (
                        <Button
                          variant="danger"
                          size="sm"
                          icon={<TrashIcon size={13} />}
                          onClick={() => setDeleteTarget(f)}
                          title="逻辑删除（不可恢复）"
                        >
                          删除
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        isOpen={reviewTarget !== null}
        onClose={() => setReviewTarget(null)}
        title={reviewTarget ? `审查《${reviewTarget.displayName}》` : '合同审查'}
        maxWidth="lg"
      >
        {reviewTarget?.versionId && (
          <ContractReviewPanel
            key={reviewTarget.versionId}
            caseId={caseInfo.id}
            fileVersionId={reviewTarget.versionId}
            fileName={reviewTarget.displayName}
            onAccessLost={loseAccess}
          />
        )}
      </Modal>
      {/* 删除确认弹窗 */}
      <Modal isOpen={!!deleteTarget} onClose={() => setDeleteTarget(null)} title="确认删除文件">
        <div
          style={{
            fontSize: 13,
            color: 'var(--text-secondary)',
            lineHeight: 1.7,
            marginBottom: 20,
          }}
        >
          <p>
            即将从卷宗中删除文件：
            <strong style={{ color: 'var(--text-main)' }}>《{deleteTarget?.displayName}》</strong>
          </p>
          <p style={{ marginTop: 8, color: 'var(--danger-text)' }}>
            删除后文件将无法通过检索和下载访问，且不可恢复。请确认操作。
          </p>
        </div>
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
