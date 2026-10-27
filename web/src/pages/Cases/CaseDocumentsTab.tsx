import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  FileTextIcon,
  PlusIcon,
  RefreshCwIcon,
  EditIcon,
  CheckIcon,
  XIcon,
  AlertCircleIcon,
  DownloadIcon,
} from '../../components/common/Icons';
import {
  listDocuments,
  createDocument,
  listVersions,
  addVersion,
  publishDocument,
  restoreVersion,
  reviewVersion,
  requestDocumentExport,
} from '../../api/documents';
import { DocumentView, VersionView } from '../../types/document';
import { CaseView } from '../../types/case';
import { DocumentExportPanel } from './DocumentExportPanel';
import { DocumentGenerationPanel } from './DocumentGenerationPanel';
import { requestGeneration } from '../../api/document-generations';

interface CaseDocumentsTabProps {
  caseInfo: CaseView;
}

const DOC_STATUS_CONFIG: Record<DocumentView['status'], { variant: any; label: string }> = {
  DRAFT: { variant: 'amber', label: '草稿' },
  PUBLISHED: { variant: 'emerald', label: '已发布' },
  ARCHIVED: { variant: 'slate', label: '已归档' },
};

const documentClock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});
function documentTime(value: string | undefined) {
  if (!value) return '—';
  // 旧接口快照未声明时区；保留原始时间文字，避免按浏览器时区解释后产生偏移。
  if (!/(?:Z|[+-]\d{2}:\d{2})$/.test(value))
    return `${value.replace('T', ' ').replace(/\.\d+$/, '')}（未标注时区）`;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '时间不可用';
  const parts = Object.fromEntries(
    documentClock.formatToParts(date).map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export const CaseDocumentsTab: React.FC<CaseDocumentsTabProps> = ({ caseInfo }) => {
  const toast = useToast();
  const isArchived = caseInfo.status === 'ARCHIVED';

  // 列表状态
  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [accessError, setAccessError] = useState<string | null>(null);

  // 新建文书
  const [createOpen, setCreateOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newContent, setNewContent] = useState('');
  const [creating, setCreating] = useState(false);

  // 版本抽屉（选中文书）
  const [selectedDoc, setSelectedDoc] = useState<DocumentView | null>(null);
  const [versions, setVersions] = useState<VersionView[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [editContent, setEditContent] = useState('');
  const [savingVersion, setSavingVersion] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [exportTask, setExportTask] = useState<string | null>(null);
  const exportKeys = useRef(new Map<string, string>());
  const [exportingId, setExportingId] = useState<string | null>(null);
  const [reviewingId, setReviewingId] = useState<string | null>(null);
  const [generationOpen, setGenerationOpen] = useState(false);
  const [generationTask, setGenerationTask] = useState<string | null>(null);
  const [instructions, setInstructions] = useState('');
  const [confirmCost, setConfirmCost] = useState(false);
  const [generating, setGenerating] = useState(false);
  const generationBusy = useRef(false),
    generationKeys = useRef(new Map<string, string>());
  const [previewVersion, setPreviewVersion] = useState<VersionView | null>(null);
  const [conflict, setConflict] = useState<{
    document: DocumentView;
    versions: VersionView[];
  } | null>(null);
  const [conflictLoading, setConflictLoading] = useState(false);
  const [hasConflict, setHasConflict] = useState(false);
  const [restoringId, setRestoringId] = useState<string | null>(null);
  // ref 在同一事件循环内立即生效，阻止 React 尚未更新 loading 状态时重复提交。
  const mutationBusy = useRef(false);
  // 选择代次隔离文书切换，权限代次隔离撤权/卸载；旧网络请求完成后也不能回填正文。
  const selectionGeneration = useRef(0);
  const accessGeneration = useRef(0);
  const mutationPending =
    savingVersion || publishing || restoringId !== null || reviewingId !== null;

  // 同时清除已有敏感内容和在途响应的写入资格，权限恢复后须重新读取。
  const loseAccess = useCallback((message: string) => {
    accessGeneration.current++;
    selectionGeneration.current++;
    setDocs([]);
    setSelectedDoc(null);
    setVersions([]);
    setEditContent('');
    setPreviewVersion(null);
    setConflict(null);
    setHasConflict(false);
    setGenerationTask(null);
    setGenerationOpen(false);
    setInstructions('');
    setConfirmCost(false);
    setExportTask(null);
    setCreateOpen(false);
    setNewTitle('');
    setNewContent('');
    setAccessError(message);
  }, []);

  // 冲突时只另存服务器最新内容，保留用户编辑及原并发基准，等待用户对照合并。
  const loadConflict = async (document: DocumentView) => {
    const selection = selectionGeneration.current,
      access = accessGeneration.current;
    setHasConflict(true);
    setConflict(null);
    setConflictLoading(true);
    try {
      const currentDocs = await listDocuments(caseInfo.id);
      const current = currentDocs.find((d) => d.id === document.id);
      if (!current) throw Object.assign(new Error('文书已不可访问'), { status: 403 });
      const currentVersions = await listVersions(caseInfo.id, document.id);
      if (selection !== selectionGeneration.current || access !== accessGeneration.current) return;
      if (!currentVersions.some((v) => v.id === current.currentVersionId))
        throw new Error('最新正文暂不可读取，请重新读取后合并');
      if (
        current.version === document.version &&
        current.currentVersionId === document.currentVersionId
      ) {
        setHasConflict(false);
        return;
      }
      setDocs(currentDocs);
      setConflict({ document: current, versions: currentVersions });
    } catch (err: any) {
      if (selection !== selectionGeneration.current || access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess(err.message || '文书读取权限已失效');
      else toast.error(err.message || '读取最新版本失败，你的编辑仍保留在编辑器中');
    } finally {
      if (selection === selectionGeneration.current && access === accessGeneration.current)
        setConflictLoading(false);
    }
  };

  const mutationFailed = async (err: any, document: DocumentView, fallback: string) => {
    if (err.status === 401 || err.status === 403) loseAccess(err.message || '文书读取权限已失效');
    else if (err.status === 409) await loadConflict(document);
    toast.error(err.message || fallback);
  };

  // 加载文书列表
  const fetchDocs = useCallback(async () => {
    const access = accessGeneration.current;
    setLoading(true);
    try {
      const data = await listDocuments(caseInfo.id);
      if (access !== accessGeneration.current) return;
      setDocs(data);
      setAccessError(null);
      return data;
    } catch (err: any) {
      if (access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) {
        loseAccess(err.message || '文书读取权限已失效，请联系管理员');
      }
      toast.error(err.message || '加载文书列表失败');
    } finally {
      setLoading(false);
    }
  }, [caseInfo.id, toast, loseAccess]);

  useEffect(() => {
    void fetchDocs();
    return () => {
      accessGeneration.current++;
      selectionGeneration.current++;
    };
  }, [fetchDocs]);

  // 加载版本列表
  const fetchVersions = useCallback(
    async (doc: DocumentView) => {
      const sequence = ++selectionGeneration.current,
        access = accessGeneration.current;
      setConflict(null);
      setHasConflict(false);
      setPreviewVersion(null);
      setVersions([]);
      setEditContent('');
      setSelectedDoc(doc);
      setVersionsLoading(true);
      try {
        const data = await listVersions(caseInfo.id, doc.id);
        if (sequence !== selectionGeneration.current || access !== accessGeneration.current) return;
        setVersions(data);
        setEditContent(data.find((v) => v.id === doc.currentVersionId)?.contentText ?? '');
      } catch (err: any) {
        if (sequence !== selectionGeneration.current || access !== accessGeneration.current) return;
        setSelectedDoc(null);
        setVersions([]);
        setEditContent('');
        if (err.status === 401 || err.status === 403)
          loseAccess(err.message || '文书读取权限已失效');
        toast.error(err.message || '加载版本失败');
      } finally {
        if (sequence === selectionGeneration.current && access === accessGeneration.current)
          setVersionsLoading(false);
      }
    },
    [caseInfo.id, toast, loseAccess],
  );

  // 创建文书
  const handleCreate = async () => {
    if (!newTitle.trim()) {
      toast.error('文书标题不能为空');
      return;
    }
    const access = accessGeneration.current;
    setCreating(true);
    try {
      const doc = await createDocument(caseInfo.id, newTitle.trim(), newContent);
      if (access !== accessGeneration.current) return;
      toast.success(`《${doc.title}》已创建`);
      setDocs((prev) => [doc, ...prev]);
      setCreateOpen(false);
      setNewTitle('');
      setNewContent('');
    } catch (err: any) {
      if (access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess(err.message || '文书权限已失效');
      toast.error(err.message || '创建文书失败');
    } finally {
      setCreating(false);
    }
  };

  // 保存绑定当前文书锁版本和正文指针；409 后禁止直接重试，须先合并最新版本。
  const handleSaveVersion = async () => {
    if (!selectedDoc || mutationBusy.current || hasConflict || versionsLoading) return;
    const document = selectedDoc,
      content = editContent,
      selection = selectionGeneration.current;
    mutationBusy.current = true;
    setSavingVersion(true);
    try {
      const v = await addVersion(caseInfo.id, document, content);
      if (selection !== selectionGeneration.current) return;
      toast.success(`版本 v${v.versionNo} 已保存`);
      setVersions((prev) => [v, ...prev]);
      // 更新列表中的 currentVersionNo
      setDocs((prev) =>
        prev.map((d) =>
          d.id === document.id
            ? {
                ...document,
                version: document.version + 1,
                currentVersionNo: v.versionNo,
                currentVersionId: v.id,
                status: 'DRAFT',
              }
            : d,
        ),
      );
      setSelectedDoc({
        ...document,
        version: document.version + 1,
        currentVersionNo: v.versionNo,
        currentVersionId: v.id,
        status: 'DRAFT',
      });
    } catch (err: any) {
      if (selection === selectionGeneration.current)
        await mutationFailed(err, document, '保存版本失败');
    } finally {
      mutationBusy.current = false;
      setSavingVersion(false);
    }
  };

  // 发布
  const handlePublish = async () => {
    if (!selectedDoc || mutationBusy.current || hasConflict || versionsLoading) return;
    const document = selectedDoc,
      selection = selectionGeneration.current;
    mutationBusy.current = true;
    setPublishing(true);
    try {
      const updated = await publishDocument(caseInfo.id, document);
      if (selection !== selectionGeneration.current) return;
      toast.success(`《${updated.title}》已发布`);
      setSelectedDoc(updated);
      setDocs((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    } catch (err: any) {
      if (selection === selectionGeneration.current)
        await mutationFailed(err, document, '发布失败');
    } finally {
      mutationBusy.current = false;
      setPublishing(false);
    }
  };

  // 恢复历史版本
  const handleRestore = async (fromVersion: VersionView) => {
    if (!selectedDoc || mutationBusy.current || hasConflict || versionsLoading) return;
    const document = selectedDoc,
      selection = selectionGeneration.current;
    mutationBusy.current = true;
    setRestoringId(fromVersion.id);
    try {
      const v = await restoreVersion(caseInfo.id, document, fromVersion.id);
      if (selection !== selectionGeneration.current) return;
      toast.success(`已从 v${fromVersion.versionNo} 恢复为新版本 v${v.versionNo}`);
      setVersions((prev) => [v, ...prev]);
      setEditContent(v.contentText ?? '');
      setDocs((prev) =>
        prev.map((d) =>
          d.id === document.id
            ? {
                ...document,
                version: document.version + 1,
                currentVersionNo: v.versionNo,
                currentVersionId: v.id,
                status: 'DRAFT',
              }
            : d,
        ),
      );
      setSelectedDoc({
        ...document,
        version: document.version + 1,
        currentVersionNo: v.versionNo,
        currentVersionId: v.id,
        status: 'DRAFT',
      });
    } catch (err: any) {
      if (selection === selectionGeneration.current)
        await mutationFailed(err, document, '恢复版本失败');
    } finally {
      mutationBusy.current = false;
      setRestoringId(null);
    }
  };

  // 人工复核 AI 草稿版本
  const handleReview = async (version: VersionView, status: 'APPROVED' | 'REJECTED') => {
    if (!selectedDoc || mutationBusy.current || hasConflict || versionsLoading) return;
    const document = selectedDoc,
      selection = selectionGeneration.current;
    mutationBusy.current = true;
    setReviewingId(version.id);
    try {
      const updated = await reviewVersion(caseInfo.id, document.id, version.id, status);
      if (selection !== selectionGeneration.current) return;
      toast.success(
        status === 'APPROVED'
          ? `版本 v${version.versionNo} 审核通过`
          : `版本 v${version.versionNo} 已驳回`,
      );
      setVersions((prev) =>
        prev.map((v) => (v.id === version.id ? { ...v, reviewStatus: updated.reviewStatus } : v)),
      );
      if (status === 'APPROVED') {
        // 审核通过可能切换当前正文并增加锁版本；重新读取服务器实际行，不能自行推算。
        const current = (await listDocuments(caseInfo.id)).find((d) => d.id === document.id);
        if (selection !== selectionGeneration.current) return;
        if (!current) throw Object.assign(new Error('文书已不可访问'), { status: 403 });
        const currentVersions = await listVersions(caseInfo.id, document.id);
        if (selection !== selectionGeneration.current) return;
        setVersions(currentVersions);
        setSelectedDoc(current);
        setDocs((prev) => prev.map((d) => (d.id === current.id ? current : d)));
        setEditContent(
          currentVersions.find((v) => v.id === current.currentVersionId)?.contentText ?? '',
        );
      }
    } catch (err: any) {
      if (selection === selectionGeneration.current)
        await mutationFailed(err, document, '复核操作失败');
    } finally {
      mutationBusy.current = false;
      setReviewingId(null);
    }
  };

  // 导出当前已保存版本；编辑器中的未保存修改不进入文件。
  const handleExport = async (doc: DocumentView) => {
    if (exportingId) return;
    const access = accessGeneration.current;
    setExportingId(doc.id);
    try {
      // 响应丢失时保留同一版本的导出键；确认任务创建成功后才允许新的导出意图。
      const input = `${doc.id}:${doc.currentVersionId}`;
      const key = exportKeys.current.get(input) ?? crypto.randomUUID();
      exportKeys.current.set(input, key);
      const job = await requestDocumentExport(caseInfo.id, doc, key);
      if (access !== accessGeneration.current) return;
      exportKeys.current.delete(input);
      setExportTask(job.taskId);
    } catch (err: any) {
      if (access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) {
        loseAccess(err.message || '文书读取权限已失效，请联系管理员');
      }
      toast.error(err.message || '导出失败');
    } finally {
      setExportingId(null);
    }
  };

  const handleGeneration = async () => {
    if (
      !selectedDoc?.currentVersionId ||
      generationBusy.current ||
      !confirmCost ||
      !instructions.trim()
    )
      return;
    const access = accessGeneration.current;
    generationBusy.current = true;
    setGenerating(true);
    // 同一文书版本和起草要求重用幂等键，响应未知时重试不会另建付费任务。
    const input = JSON.stringify([
      selectedDoc.id,
      selectedDoc.currentVersionId,
      instructions.trim(),
    ]);
    const key = generationKeys.current.get(input) ?? crypto.randomUUID();
    generationKeys.current.set(input, key);
    try {
      const task = await requestGeneration(
        caseInfo.id,
        selectedDoc.id,
        selectedDoc.currentVersionId,
        instructions.trim(),
        key,
        confirmCost,
      );
      if (access !== accessGeneration.current) return;
      generationKeys.current.delete(input);
      setGenerationTask(task.taskId);
      setGenerationOpen(false);
      setConfirmCost(false);
      setInstructions('');
    } catch (e: any) {
      if (access !== accessGeneration.current) return;
      if (e.status === 401 || e.status === 403) loseAccess(e.message || '文书权限已失效');
      // 409 冲突会重置本次键和费用确认；响应未知的其他失败继续保留原键供重试。
      if (e.status === 409) {
        generationKeys.current.delete(input);
        setConfirmCost(false);
      }
      toast.error(e.message || '提交状态未确认，请先在任务中心检查');
    } finally {
      generationBusy.current = false;
      setGenerating(false);
    }
  };

  // ── 渲染 ──────────────────────────────────────────────────────────────────

  if (accessError) {
    return (
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <p style={{ color: 'var(--danger-text)', fontSize: 14 }}>{accessError}</p>
        <p style={{ color: 'var(--text-muted)', fontSize: 12 }}>
          当前无法读取案件文书，请联系管理员。权限恢复后可刷新。
        </p>
        <div>
          <Button variant="secondary" size="sm" loading={loading} onClick={fetchDocs}>
            刷新
          </Button>
        </div>
      </div>
    );
  }

  const exportModal = (
    <Modal isOpen={exportTask !== null} onClose={() => setExportTask(null)} title="文书导出">
      {exportTask && (
        <DocumentExportPanel
          key={exportTask}
          taskId={exportTask}
          onAccessLost={() => {
            loseAccess('文书读取权限已失效，请联系管理员');
          }}
        />
      )}
    </Modal>
  );
  const generationModal = (
    <Modal
      isOpen={generationOpen || generationTask !== null}
      title="AI 文书生成"
      maxWidth="lg"
      onClose={() => {
        setGenerationOpen(false);
        setGenerationTask(null);
        setInstructions('');
        setConfirmCost(false);
        const selection = selectionGeneration.current;
        if (selectedDoc)
          void fetchDocs().then((currentDocs) => {
            if (selection !== selectionGeneration.current) return;
            const current = currentDocs?.find((doc) => doc.id === selectedDoc.id);
            if (current) void fetchVersions(current);
          });
      }}
    >
      {generationTask ? (
        <DocumentGenerationPanel
          key={generationTask}
          taskId={generationTask}
          onAccessLost={() => {
            loseAccess('文书读取权限已失效，请联系管理员');
          }}
        />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            基于当前已保存正文和案件背景起草，结果保留为待复核版本。
          </p>
          <label>
            生成要求
            <textarea
              className="form-textarea"
              aria-label="生成要求"
              rows={5}
              maxLength={2000}
              value={instructions}
              onChange={(e) => {
                setInstructions(e.target.value);
                setConfirmCost(false);
              }}
              placeholder="例如：根据现有事实起草催款函；不确定信息保留占位。"
            />
          </label>
          <label style={{ display: 'flex', gap: 8, fontSize: 13 }}>
            <input
              type="checkbox"
              checked={confirmCost}
              onChange={(e) => setConfirmCost(e.target.checked)}
            />
            我确认本次生成将调用已配置的模型，可能产生费用
          </label>
          <div>
            <Button
              loading={generating}
              disabled={!confirmCost || !instructions.trim() || generating}
              onClick={() => void handleGeneration()}
            >
              提交生成任务
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
  const previewModal = (
    <Modal
      isOpen={previewVersion !== null}
      onClose={() => setPreviewVersion(null)}
      title={previewVersion ? `版本 v${previewVersion.versionNo} 正文` : ''}
      maxWidth="lg"
    >
      {previewVersion && (
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere',
            maxHeight: 480,
            overflow: 'auto',
          }}
        >
          {previewVersion.contentText}
        </pre>
      )}
    </Modal>
  );

  // 文书详情抽屉视图
  if (selectedDoc) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* 面包屑 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Button
            variant="ghost"
            size="sm"
            disabled={mutationPending}
            onClick={() => {
              selectionGeneration.current++;
              setSelectedDoc(null);
              setVersions([]);
              setEditContent('');
              setConflict(null);
              setHasConflict(false);
              setPreviewVersion(null);
            }}
          >
            ← 返回文书列表
          </Button>
          <span style={{ color: 'var(--text-muted)', fontSize: 12 }}>/</span>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-main)' }}>
            {selectedDoc.title}
          </span>
          <Badge variant={DOC_STATUS_CONFIG[selectedDoc.status].variant}>
            {DOC_STATUS_CONFIG[selectedDoc.status].label}
          </Badge>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 280px', gap: 20 }}>
          {/* 编辑区 */}
          <div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 8,
              }}
            >
              <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                当前版本 v{selectedDoc.currentVersionNo}
              </span>
              <div style={{ display: 'flex', gap: 8 }}>
                {!isArchived && (
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={
                      mutationPending ||
                      hasConflict ||
                      versionsLoading ||
                      generating ||
                      editContent !==
                        (versions.find((v) => v.id === selectedDoc.currentVersionId)?.contentText ??
                          '')
                    }
                    onClick={() => {
                      setInstructions('');
                      setConfirmCost(false);
                      setGenerationOpen(true);
                    }}
                  >
                    AI 起草
                  </Button>
                )}
                <Button
                  variant="secondary"
                  size="sm"
                  icon={<DownloadIcon size={13} />}
                  loading={exportingId === selectedDoc.id}
                  disabled={versionsLoading || !!exportingId}
                  onClick={() => handleExport(selectedDoc)}
                >
                  导出 TXT
                </Button>
                {!isArchived && selectedDoc.status !== 'PUBLISHED' && (
                  <Button
                    variant="primary"
                    size="sm"
                    icon={<CheckIcon size={13} />}
                    loading={publishing}
                    disabled={
                      mutationPending ||
                      hasConflict ||
                      versionsLoading ||
                      editContent !==
                        (versions.find((v) => v.id === selectedDoc.currentVersionId)?.contentText ??
                          '')
                    }
                    onClick={handlePublish}
                  >
                    发布
                  </Button>
                )}
                {!isArchived && (
                  <Button
                    variant="secondary"
                    size="sm"
                    icon={<EditIcon size={13} />}
                    loading={savingVersion}
                    disabled={mutationPending || hasConflict || versionsLoading}
                    onClick={handleSaveVersion}
                  >
                    保存新版本
                  </Button>
                )}
              </div>
            </div>
            <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 8 }}>
              导出当前已保存版本，未保存的修改不会包含在文件中。草稿仍为草稿。
            </p>
            {hasConflict && (
              <div
                role="alert"
                style={{
                  padding: 12,
                  marginBottom: 12,
                  border: '1px solid var(--warning-border)',
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--warning-bg)',
                }}
              >
                <p style={{ fontSize: 13, marginBottom: 8 }}>
                  文书已被修改。你的未保存编辑保留在下方，请对照最新正文合并后继续。
                </p>
                {conflictLoading ? (
                  <Spinner text="读取最新版本..." />
                ) : conflict ? (
                  <>
                    <p style={{ fontSize: 12, marginBottom: 6 }}>
                      服务器当前版本 v{conflict.document.currentVersionNo} ·{' '}
                      {DOC_STATUS_CONFIG[conflict.document.status].label}
                    </p>
                    <pre
                      aria-label="最新已保存正文"
                      style={{
                        whiteSpace: 'pre-wrap',
                        overflowWrap: 'anywhere',
                        maxHeight: 220,
                        overflow: 'auto',
                        padding: 10,
                        background: 'var(--bg-subtle)',
                        fontSize: 13,
                      }}
                    >
                      {conflict.versions.find((v) => v.id === conflict.document.currentVersionId)
                        ?.contentText ?? ''}
                    </pre>
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => {
                        // 仅更新下一次提交的并发基准，保留编辑器中用户已手动合并的正文。
                        setSelectedDoc(conflict.document);
                        setVersions(conflict.versions);
                        setHasConflict(false);
                        setConflict(null);
                      }}
                    >
                      我已对照合并，继续编辑
                    </Button>
                  </>
                ) : (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => void loadConflict(selectedDoc)}
                  >
                    重新读取最新版本
                  </Button>
                )}
              </div>
            )}
            <textarea
              className="form-textarea"
              aria-label="文书正文"
              value={editContent}
              onChange={(e) => setEditContent(e.target.value)}
              disabled={isArchived || mutationPending || versionsLoading}
              rows={20}
              placeholder="在此输入文书正文..."
              style={{
                width: '100%',
                fontFamily: 'var(--font-mono)',
                fontSize: 13,
                resize: 'vertical',
              }}
            />
          </div>

          {/* 版本历史 */}
          <div>
            <div
              style={{
                fontSize: 12,
                fontWeight: 600,
                color: 'var(--text-muted)',
                marginBottom: 10,
                textTransform: 'uppercase',
                letterSpacing: '0.05em',
              }}
            >
              版本历史 · 北京时间
            </div>
            {versionsLoading ? (
              <Spinner text="加载..." />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {versions.map((v) => (
                  <div
                    key={v.id}
                    style={{
                      padding: '10px 12px',
                      backgroundColor:
                        v.versionNo === selectedDoc.currentVersionNo
                          ? 'var(--brand-blue-subtle)'
                          : 'var(--bg-subtle)',
                      borderRadius: 'var(--radius-sm)',
                      border: `1px solid ${v.versionNo === selectedDoc.currentVersionNo ? '#bfdbfe' : 'var(--border-default)'}`,
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                      }}
                    >
                      <span style={{ fontSize: 13, fontWeight: 600 }}>v{v.versionNo}</span>
                      <Badge variant={v.source === 'AI_DRAFT' ? 'amber' : 'slate'}>
                        {v.source === 'AI_DRAFT' ? 'AI 草稿' : '手动'}
                      </Badge>
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }}>
                      {documentTime(v.createdAt)}
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => setPreviewVersion(v)}>
                      查看正文
                    </Button>
                    {v.source === 'AI_DRAFT' && (
                      <div
                        style={{
                          marginTop: 6,
                          paddingTop: 6,
                          borderTop: '1px dashed var(--border-default)',
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                          }}
                        >
                          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>AI复核:</span>
                          {v.reviewStatus === 'APPROVED' && <Badge variant="emerald">已通过</Badge>}
                          {v.reviewStatus === 'REJECTED' && <Badge variant="rose">已驳回</Badge>}
                          {(!v.reviewStatus || v.reviewStatus === 'PENDING_REVIEW') && (
                            <Badge variant="amber">待复核</Badge>
                          )}
                        </div>
                        {!isArchived &&
                          (!v.reviewStatus || v.reviewStatus === 'PENDING_REVIEW') && (
                            <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                              <Button
                                variant="primary"
                                size="sm"
                                style={{ fontSize: 11, flex: 1, padding: '2px 4px', height: 24 }}
                                icon={<CheckIcon size={12} />}
                                loading={reviewingId === v.id}
                                disabled={
                                  mutationPending ||
                                  hasConflict ||
                                  editContent !==
                                    (versions.find(
                                      (item) => item.id === selectedDoc.currentVersionId,
                                    )?.contentText ?? '')
                                }
                                onClick={() => handleReview(v, 'APPROVED')}
                              >
                                通过
                              </Button>
                              <Button
                                variant="danger"
                                size="sm"
                                style={{ fontSize: 11, flex: 1, padding: '2px 4px', height: 24 }}
                                icon={<XIcon size={12} />}
                                loading={reviewingId === v.id}
                                disabled={mutationPending || hasConflict}
                                onClick={() => handleReview(v, 'REJECTED')}
                              >
                                驳回
                              </Button>
                            </div>
                          )}
                      </div>
                    )}
                    {!isArchived &&
                      v.versionNo !== selectedDoc.currentVersionNo &&
                      (v.source !== 'AI_DRAFT' || v.reviewStatus === 'APPROVED') && (
                        <Button
                          variant="ghost"
                          size="sm"
                          style={{ marginTop: 6, fontSize: 11 }}
                          loading={restoringId === v.id}
                          disabled={
                            mutationPending ||
                            hasConflict ||
                            editContent !==
                              (versions.find((item) => item.id === selectedDoc.currentVersionId)
                                ?.contentText ?? '')
                          }
                          onClick={() => handleRestore(v)}
                        >
                          恢复此版本
                        </Button>
                      )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
        {exportModal}
        {generationModal}
        {previewModal}
      </div>
    );
  }

  // 文书列表视图
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* 工具栏 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h3 style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-main)' }}>案件文书</h3>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
            管理案件相关的法律文书草稿、历史版本与发布状态
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <Button
            variant="ghost"
            size="sm"
            icon={<RefreshCwIcon size={14} />}
            onClick={fetchDocs}
            disabled={loading}
          >
            刷新
          </Button>
          {!isArchived && (
            <Button
              variant="primary"
              size="sm"
              icon={<PlusIcon size={14} />}
              onClick={() => setCreateOpen(true)}
            >
              新建文书
            </Button>
          )}
        </div>
      </div>

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
          案件已归档，文书内容为只读状态。
        </div>
      )}

      {/* 列表 */}
      {loading ? (
        <div style={{ padding: '32px 0' }}>
          <Spinner text="加载文书列表..." />
        </div>
      ) : docs.length === 0 ? (
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
          <p style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-main)' }}>尚无文书</p>
          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            {isArchived ? '已归档案件无可查看文书' : '点击「新建文书」开始起草合同、诉状或答辩状'}
          </p>
        </div>
      ) : (
        <div className="table-responsive">
          <table className="data-table">
            <thead>
              <tr>
                <th>文书标题</th>
                <th>状态</th>
                <th>当前版本</th>
                <th>创建时间（北京时间）</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((doc) => (
                <tr key={doc.id}>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <FileTextIcon
                        size={14}
                        style={{ color: 'var(--text-secondary)', flexShrink: 0 }}
                      />
                      <span style={{ fontSize: 13, fontWeight: 500 }}>{doc.title}</span>
                    </div>
                  </td>
                  <td>
                    <Badge variant={DOC_STATUS_CONFIG[doc.status].variant}>
                      {DOC_STATUS_CONFIG[doc.status].label}
                    </Badge>
                  </td>
                  <td style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                    v{doc.currentVersionNo}
                  </td>
                  <td style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                    {documentTime(doc.createdAt)}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={<EditIcon size={13} />}
                        onClick={() => fetchVersions(doc)}
                      >
                        编辑
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={<DownloadIcon size={13} />}
                        onClick={() => handleExport(doc)}
                        loading={exportingId === doc.id}
                        disabled={!!exportingId}
                        title="导出当前已保存版本（TXT）"
                      >
                        导出
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {exportModal}
      {/* 新建文书弹窗 */}
      <Modal
        isOpen={createOpen}
        onClose={() => {
          setCreateOpen(false);
          setNewTitle('');
          setNewContent('');
        }}
        title="新建文书"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div>
            <label
              style={{
                fontSize: 12,
                color: 'var(--text-muted)',
                display: 'block',
                marginBottom: 6,
              }}
            >
              文书标题 <span style={{ color: 'var(--danger-text)' }}>*</span>
            </label>
            <input
              className="form-input"
              placeholder="例如：劳动合同纠纷答辩状"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
            />
          </div>
          <div>
            <label
              style={{
                fontSize: 12,
                color: 'var(--text-muted)',
                display: 'block',
                marginBottom: 6,
              }}
            >
              初始内容（可留空后在编辑器中填写）
            </label>
            <textarea
              className="form-textarea"
              rows={8}
              placeholder="在此输入文书正文..."
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 13 }}
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <Button variant="secondary" size="md" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              variant="primary"
              size="md"
              loading={creating}
              icon={<PlusIcon size={14} />}
              onClick={handleCreate}
            >
              创建文书
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
