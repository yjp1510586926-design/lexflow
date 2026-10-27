import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Card } from '../../components/common/Card';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import { getParsedText, getTask } from '../../api/tasks';
import {
  KnowledgeChunk,
  KnowledgeDocument,
  KnowledgeSearchResult,
  listKnowledgeChunks,
  listKnowledgeDocuments,
  prepareKnowledgeDocument,
} from '../../api/knowledge';
import {
  TenantKnowledgeBase,
  TenantKnowledgeFile,
  createTenantKnowledgeBase,
  listTenantKnowledgeBases,
  listTenantKnowledgeFiles,
  uploadTenantKnowledgeFile,
  parseTenantKnowledgeFile,
  indexTenantKnowledgeDocument,
  searchTenantKnowledge,
  removeTenantKnowledgeFile,
  downloadTenantKnowledgeFile,
} from '../../api/tenant-knowledge';
import { TaskView } from '../../types/document';
import { CaseQuestionPanel } from '../Cases/CaseQuestionPanel';

const waiting = (status?: string | null) =>
  status === 'QUEUED' || status === 'RUNNING' || status === 'CANCEL_REQUESTED';
const rejected = (error: unknown) =>
  [401, 403].includes((error as { status?: number })?.status ?? 0);
const taskLabels: Record<TaskView['status'], string> = {
  QUEUED: '等待提取',
  RUNNING: '正在提取',
  WAITING_HUMAN: '等待确认',
  SUCCEEDED: '文字已提取',
  FAILED: '提取失败',
  CANCEL_REQUESTED: '正在取消',
  CANCELLED: '已取消',
};
const documentLabels: Record<KnowledgeDocument['status'], string> = {
  PREPARED: '待索引',
  INDEXING: '索引中',
  ACTIVE: '可检索',
  FAILED: '索引失败',
  SUPERSEDED: '已更新',
};
const sizeLabel = (bytes: number) =>
  bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;

export const TenantKnowledgePage: React.FC = () => {
  const toast = useToast();
  const [bases, setBases] = useState<TenantKnowledgeBase[]>([]);
  const [canManage, setCanManage] = useState(false);
  const [selectedBaseId, setSelectedBaseId] = useState('');
  const [newName, setNewName] = useState('法律与办案指引');
  const [files, setFiles] = useState<TenantKnowledgeFile[]>([]);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [denied, setDenied] = useState(false);
  const [error, setError] = useState('');
  const [pollNotice, setPollNotice] = useState('');
  const [question, setQuestion] = useState('');
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null);
  const [preview, setPreview] = useState<{
    title: string;
    text?: string;
    chunks?: KnowledgeChunk[];
  } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TenantKnowledgeFile | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const deniedRef = useRef(false);
  const busyRef = useRef(false);
  const selectedRef = useRef(selectedBaseId);
  const parseRequests = useRef(new Map<string, string>());
  selectedRef.current = selectedBaseId;

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
    };
  }, []);

  const deny = useCallback(() => {
    if (!mounted.current) return;
    deniedRef.current = true;
    generation.current += 1;
    setDenied(true);
    setLoading(false);
    setCanManage(false);
    setBases([]);
    setFiles([]);
    setDocuments([]);
    setSearchResult(null);
    setQuestion('');
    setPreview(null);
    setDeleteTarget(null);
    setPollNotice('');
    setSelectedBaseId('');
    setError('当前账号已无法访问这些资料。请重新登录或联系律所管理员。');
    parseRequests.current.clear();
  }, []);

  const handleError = useCallback(
    (cause: unknown, fallback: string) => {
      if (!mounted.current) return;
      if (rejected(cause)) {
        deny();
        return;
      }
      const message = cause instanceof Error ? cause.message : fallback;
      setError(message);
      toast.error(message);
    },
    [deny, toast],
  );

  const current = (scope: number, baseId?: string) =>
    mounted.current &&
    !deniedRef.current &&
    generation.current === scope &&
    (baseId === undefined || selectedRef.current === baseId);

  const loadContents = useCallback(async (baseId: string, scope: number) => {
    const [nextFiles, nextDocuments] = await Promise.all([
      listTenantKnowledgeFiles(baseId),
      listKnowledgeDocuments(baseId),
    ]);
    if (
      mounted.current &&
      !deniedRef.current &&
      generation.current === scope &&
      selectedRef.current === baseId
    ) {
      setFiles(nextFiles);
      setDocuments(nextDocuments);
    }
    return { nextFiles, nextDocuments };
  }, []);

  useEffect(() => {
    const scope = ++generation.current;
    setLoading(true);
    listTenantKnowledgeBases()
      .then((result) => {
        if (!current(scope)) return;
        setBases(result.items);
        setCanManage(result.canManage);
        setSelectedBaseId(result.items[0]?.id ?? '');
      })
      .catch((cause) => {
        if (current(scope)) handleError(cause, '读取律所知识库失败');
      })
      .finally(() => {
        if (current(scope)) setLoading(false);
      });
  }, [handleError]);

  useEffect(() => {
    if (!selectedBaseId || deniedRef.current) return;
    const scope = ++generation.current;
    setFiles([]);
    setDocuments([]);
    setPreview(null);
    setDeleteTarget(null);
    setSearchResult(null);
    setQuestion('');
    setPollNotice('');
    setError('');
    setLoading(true);
    loadContents(selectedBaseId, scope)
      .catch((cause) => {
        if (current(scope, selectedBaseId)) handleError(cause, '读取知识库资料失败');
      })
      .finally(() => {
        if (current(scope, selectedBaseId)) setLoading(false);
      });
  }, [selectedBaseId, loadContents, handleError]);

  // 仅跟进已提交的后台任务，最多一分钟；这里没有任何模型调用。
  const pendingIds = [
    ...new Set([
      ...files
        .filter((file) => file.parseTaskId && waiting(file.parseStatus))
        .map((file) => file.parseTaskId!),
      ...documents
        .filter((document) => document.pendingTaskId)
        .map((document) => document.pendingTaskId!),
    ]),
  ]
    .sort()
    .join(',');
  useEffect(() => {
    if (!pendingIds || !selectedBaseId || deniedRef.current || busy) return;
    let active = true;
    let rounds = 0;
    let timer: number | undefined;
    const baseId = selectedBaseId;
    const scope = generation.current;
    const poll = async () => {
      if (!active || !current(scope, baseId)) return;
      try {
        // 普通成员无任务管理权限，只刷新已有读取权限的资料列表。
        const statuses = canManage ? await Promise.all(pendingIds.split(',').map(getTask)) : [];
        if (!active || !current(scope, baseId)) return;
        const refreshed = await loadContents(baseId, scope);
        if (!active || !current(scope, baseId)) return;
        const stillPending = canManage
          ? statuses.some((task) => waiting(task.status))
          : refreshed.nextFiles.some((file) => waiting(file.parseStatus)) ||
            refreshed.nextDocuments.some((document) => document.pendingTaskId);
        if (stillPending) {
          if (++rounds < 30) timer = window.setTimeout(poll, 2000);
          else setPollNotice('自动更新已暂停。任务仍可能在后台继续，可点击“刷新资料”读取进度。');
        }
      } catch (cause) {
        if (active && current(scope, baseId)) handleError(cause, '读取任务进度失败，请手动刷新');
      }
    };
    timer = window.setTimeout(poll, 2000);
    return () => {
      active = false;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [pendingIds, selectedBaseId, busy, canManage, loadContents, handleError]);

  const run = async (operation: (scope: number, baseId: string) => Promise<void>) => {
    if (busyRef.current || deniedRef.current || loading) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    const scope = ++generation.current;
    const baseId = selectedRef.current;
    try {
      await operation(scope, baseId);
    } catch (cause) {
      if (current(scope, baseId)) handleError(cause, '操作未完成');
    } finally {
      busyRef.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const create = () =>
    run(async (scope) => {
      const base = await createTenantKnowledgeBase(newName.trim());
      if (!current(scope)) return;
      setBases((items) => [base, ...items]);
      setSelectedBaseId(base.id);
      toast.success('律所知识库已创建');
    });
  const upload = (file: File) =>
    run(async (scope, baseId) => {
      const extension = file.name.split('.').pop()?.toLowerCase();
      if (!['txt', 'docx', 'pdf'].includes(extension ?? ''))
        throw new Error('请上传 TXT、DOCX 或带文字层的 PDF 文件');
      if (file.size > 50 * 1024 * 1024) throw new Error('单文件不能超过 50MB');
      const uploaded = await uploadTenantKnowledgeFile(baseId, file);
      if (!current(scope, baseId)) return;
      setFiles((items) => [uploaded, ...items]);
      toast.success('资料已上传，请点击“提取文字”');
    });
  const parse = (file: TenantKnowledgeFile) =>
    run(async (scope, baseId) => {
      if (!file.versionId) return;
      // 网络响应未知时保留相同请求标识，手动再次提交也不会产生第二个解析任务。
      const key = `${baseId}:${file.id}:${file.versionId}`;
      if (file.parseStatus === 'FAILED' || file.parseStatus === 'CANCELLED')
        parseRequests.current.delete(key);
      if (!parseRequests.current.has(key)) parseRequests.current.set(key, crypto.randomUUID());
      const task = await parseTenantKnowledgeFile(
        baseId,
        file.id,
        file.versionId,
        parseRequests.current.get(key)!,
      );
      if (!current(scope, baseId)) return;
      if (!waiting(task.status)) parseRequests.current.delete(key);
      setFiles((items) =>
        items.map((item) =>
          item.id === file.id ? { ...item, parseTaskId: task.id, parseStatus: task.status } : item,
        ),
      );
      setPollNotice('');
      toast.success(`文字提取任务 #${task.id} 已提交`);
    });
  const prepare = (file: TenantKnowledgeFile) =>
    run(async (scope, baseId) => {
      if (!file.parseTaskId) return;
      const document = await prepareKnowledgeDocument(baseId, file.parseTaskId);
      if (!current(scope, baseId)) return;
      setDocuments((items) => [document, ...items.filter((item) => item.id !== document.id)]);
      toast.success('原文已整理，可以开始索引');
    });
  const index = (document: KnowledgeDocument) =>
    run(async (scope, baseId) => {
      const task = await indexTenantKnowledgeDocument(baseId, document.id);
      if (!current(scope, baseId)) return;
      setDocuments((items) =>
        items.map((item) =>
          item.id === document.id
            ? {
                ...item,
                status: item.status === 'ACTIVE' ? 'ACTIVE' : 'INDEXING',
                pendingTaskId: task.taskId,
              }
            : item,
        ),
      );
      setPollNotice('');
      toast.success(`索引任务 #${task.taskId} 已提交`);
    });
  const search = () =>
    run(async (scope, baseId) => {
      setSearchResult(null);
      const result = await searchTenantKnowledge(baseId, question.trim());
      if (current(scope, baseId)) setSearchResult(result);
    });
  const refresh = () =>
    run(async (scope, baseId) => {
      const access = await listTenantKnowledgeBases();
      if (!current(scope, baseId)) return;
      setCanManage(access.canManage);
      setBases(access.items);
      setPollNotice('');
      setSearchResult(null);
      setPreview(null);
      if (!access.items.some((base) => base.id === baseId)) {
        setSelectedBaseId(access.items[0]?.id ?? '');
        setFiles([]);
        setDocuments([]);
        return;
      }
      await loadContents(baseId, scope);
    });
  const showText = (file: TenantKnowledgeFile) =>
    run(async (scope, baseId) => {
      if (!file.parseTaskId) return;
      const result = await getParsedText(file.parseTaskId);
      if (current(scope, baseId))
        setPreview({ title: `原文 · ${file.displayName}`, text: result.text });
    });
  const showChunks = (document: KnowledgeDocument) =>
    run(async (scope, baseId) => {
      const chunks = await listKnowledgeChunks(baseId, document.id);
      if (current(scope, baseId)) setPreview({ title: `原文片段 · ${document.fileName}`, chunks });
    });
  const download = (fileId: string, fileName: string) =>
    run(async (scope, baseId) => {
      await downloadTenantKnowledgeFile(baseId, fileId, fileName, () => current(scope, baseId));
    });
  const remove = () =>
    run(async (scope, baseId) => {
      if (!deleteTarget) return;
      await removeTenantKnowledgeFile(baseId, deleteTarget.id);
      if (!current(scope, baseId)) return;
      setDeleteTarget(null);
      setSearchResult(null);
      setPreview(null);
      await loadContents(baseId, scope);
      toast.success('资料已移除，原文与引用不再可访问');
    });

  if (denied)
    return (
      <Card title="律所知识库">
        <p role="alert">{error}</p>
      </Card>
    );
  if (loading && bases.length === 0) return <Spinner text="正在读取律所知识库..." />;
  const manageable = canManage;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card
        title="全所共用的法律与办案资料"
        subtitle="仅本律所中有权限的成员可访问。这里的资料由管理员独立上传，案件私有材料不会自动进入。"
      >
        {error && (
          <p role="alert" style={{ color: 'var(--danger-text)', marginBottom: 12 }}>
            {error}
          </p>
        )}
        {busy && (
          <p role="status" className="form-hint" style={{ marginBottom: 12 }}>
            正在处理，请稍候…
          </p>
        )}
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
          {bases.length > 0 && (
            <div className="form-group" style={{ minWidth: 220, marginBottom: 0 }}>
              <label htmlFor="tenantKnowledgeBase" className="form-label">
                律所知识库
              </label>
              <select
                id="tenantKnowledgeBase"
                className="form-select"
                value={selectedBaseId}
                disabled={busy || loading}
                onChange={(event) => setSelectedBaseId(event.target.value)}
              >
                {bases.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          {canManage && (
            <>
              <div className="form-group" style={{ marginBottom: 0 }}>
                <label htmlFor="tenantKnowledgeName" className="form-label">
                  新知识库名称
                </label>
                <input
                  id="tenantKnowledgeName"
                  className="form-input"
                  value={newName}
                  maxLength={200}
                  onChange={(event) => setNewName(event.target.value)}
                  disabled={busy || loading}
                />
              </div>
              <Button
                size="sm"
                onClick={() => void create()}
                disabled={busy || loading || !newName.trim()}
              >
                创建知识库
              </Button>
            </>
          )}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => void refresh()}
            disabled={busy || loading}
          >
            刷新资料
          </Button>
        </div>
        {bases.length === 0 && (
          <p className="form-hint" style={{ marginTop: 12 }}>
            {canManage
              ? '创建一个知识库，然后上传法律法规、办案指引或公开案例。'
              : '管理员还没有创建知识库。请联系管理员添加资料。'}
          </p>
        )}
        {pollNotice && (
          <p className="form-hint" style={{ marginTop: 12 }}>
            {pollNotice}
          </p>
        )}
      </Card>
      {selectedBaseId && (
        <>
          <Card
            title="1. 上传并整理原文"
            subtitle={
              manageable
                ? '上传 → 提取文字 → 准备入库。文字提取与整理不会调用 AI 模型。'
                : '管理员负责上传与整理；你可以查看已经上传的资料。'
            }
            actions={
              manageable && (
                <>
                  <input
                    ref={fileInput}
                    type="file"
                    accept=".txt,.docx,.pdf"
                    style={{ display: 'none' }}
                    aria-label="上传律所资料"
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      event.target.value = '';
                      if (file) void upload(file);
                    }}
                  />
                  <Button
                    size="sm"
                    onClick={() => fileInput.current?.click()}
                    disabled={busy || loading}
                  >
                    上传资料
                  </Button>
                </>
              )
            }
          >
            <p className="form-hint">
              支持 TXT、DOCX、带文字层 PDF，单文件上限 50MB。扫描版 PDF 暂不支持。
            </p>
            {loading ? (
              <Spinner text="正在读取资料..." />
            ) : files.length === 0 ? (
              <p className="form-hint">还没有资料。</p>
            ) : (
              <div className="table-responsive">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>资料</th>
                      <th>文字提取</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {files.map((file) => {
                      const prepared = documents.some(
                        (document) =>
                          document.fileVersionId === file.versionId &&
                          document.status !== 'SUPERSEDED',
                      );
                      return (
                        <tr key={file.id}>
                          <td>
                            <strong>{file.displayName}</strong>
                            <p className="form-hint">
                              v{file.versionNo} · {sizeLabel(file.sizeBytes)}
                            </p>
                          </td>
                          <td>
                            {file.parseStatus ? taskLabels[file.parseStatus] : '尚未提取'}
                            {file.parseTaskId && (
                              <p className="form-hint">任务 #{file.parseTaskId}</p>
                            )}
                          </td>
                          <td>
                            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={busy || file.status !== 'AVAILABLE'}
                                onClick={() => void download(file.id, file.displayName)}
                              >
                                下载原件
                              </Button>
                              {manageable && file.parseStatus === 'SUCCEEDED' && (
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  disabled={busy}
                                  onClick={() => void showText(file)}
                                >
                                  查看原文
                                </Button>
                              )}
                              {manageable && file.status === 'AVAILABLE' && (
                                <>
                                  {file.parseStatus !== 'SUCCEEDED' && (
                                    <Button
                                      variant="secondary"
                                      size="sm"
                                      disabled={
                                        busy || waiting(file.parseStatus) || !file.versionId
                                      }
                                      onClick={() => void parse(file)}
                                    >
                                      提取文字
                                    </Button>
                                  )}
                                  {file.parseStatus === 'SUCCEEDED' && (
                                    <Button
                                      variant="secondary"
                                      size="sm"
                                      disabled={busy || prepared}
                                      onClick={() => void prepare(file)}
                                    >
                                      {prepared ? '已准备' : '准备入库'}
                                    </Button>
                                  )}
                                  <Button
                                    variant="danger"
                                    size="sm"
                                    disabled={busy}
                                    onClick={() => setDeleteTarget(file)}
                                  >
                                    移除
                                  </Button>
                                </>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          <Card
            title="2. 索引资料"
            subtitle="管理员手动配置 Embedding Key 后主动开始索引。索引可能产生模型费用；刷新进度不会调用模型。"
          >
            {documents.length === 0 ? (
              <p className="form-hint">先对已提取文字的资料点击“准备入库”。</p>
            ) : (
              documents.map((document) => (
                <div
                  key={document.id}
                  style={{
                    padding: '12px 0',
                    borderBottom: '1px solid var(--border)',
                    display: 'flex',
                    gap: 12,
                    justifyContent: 'space-between',
                    flexWrap: 'wrap',
                  }}
                >
                  <div>
                    <strong>{document.fileName}</strong>
                    <p className="form-hint">
                      v{document.fileVersion} · {document.chunkCount} 段 ·{' '}
                      {documentLabels[document.status]}
                      {document.pendingTaskId
                        ? ` · ${document.status === 'ACTIVE' ? '重建中 · ' : ''}任务 #${document.pendingTaskId}`
                        : ''}
                    </p>
                  </div>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => void showChunks(document)}
                    >
                      查看原文片段
                    </Button>
                    {manageable &&
                      !document.pendingTaskId &&
                      ['PREPARED', 'FAILED', 'ACTIVE'].includes(document.status) && (
                        <Button size="sm" disabled={busy} onClick={() => void index(document)}>
                          {document.status === 'ACTIVE' ? '重建索引' : '开始索引'}
                        </Button>
                      )}
                  </div>
                </div>
              ))
            )}
            <p className="form-hint" style={{ marginTop: 12 }}>
              更换向量模型后需重建索引。重建失败或取消会保留旧索引；检索只使用与当前模型相符的版本。
            </p>
          </Card>
          <Card
            title="3. 检索原文"
            subtitle="只检索本知识库中已完成索引的资料，返回可核对的页码或段落。点击检索会调用向量模型；已配置排序模型时也会调用排序模型，可能产生费用。"
          >
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <input
                className="form-input"
                style={{ flex: 1, minWidth: 200, maxWidth: 560 }}
                value={question}
                maxLength={500}
                aria-label="检索律所资料"
                placeholder="例如：办案指引要求何时提交材料？"
                disabled={busy}
                onChange={(event) => setQuestion(event.target.value)}
              />
              <Button
                size="sm"
                disabled={busy || question.trim().length < 2 || loading}
                onClick={() => void search()}
              >
                检索原文
              </Button>
            </div>
            {searchResult && (
              <div style={{ marginTop: 16 }}>
                <p className="form-hint">
                  {searchResult.reranked
                    ? '已按排序模型的相关性排列。'
                    : '按向量相似度排列，未调用排序模型。'}
                  {searchResult.outdatedDocuments > 0
                    ? ` ${searchResult.outdatedDocuments} 份资料需要重建索引，未参与本次检索。`
                    : ''}
                </p>
                {searchResult.citations.length === 0 ? (
                  <p className="form-hint">没有找到可引用的原文片段。</p>
                ) : (
                  searchResult.citations.map((hit) => (
                    <article
                      key={hit.chunkId}
                      style={{ padding: '14px 0', borderBottom: '1px solid var(--border)' }}
                    >
                      <strong>
                        {hit.fileName} · v{hit.fileVersion} ·{' '}
                        {hit.locatorKind === 'PAGE'
                          ? `第 ${hit.locatorIndex} 页`
                          : `第 ${hit.locatorIndex} 段`}
                      </strong>
                      <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7, margin: '8px 0' }}>
                        {hit.text}
                      </p>
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busy}
                        onClick={() => void download(hit.fileId, hit.fileName)}
                      >
                        下载原件核对
                      </Button>
                    </article>
                  ))
                )}
              </div>
            )}
          </Card>
          <Card
            title="4. 根据律所资料提问"
            subtitle="会话和历史回答仅你本人可见。资料撤销后，相关回答和引用会隐藏。"
          >
            <CaseQuestionPanel
              key={selectedBaseId}
              baseId={selectedBaseId}
              scope="TENANT"
              onAccessLost={deny}
            />
          </Card>
        </>
      )}
      <Modal
        isOpen={preview !== null}
        onClose={() => setPreview(null)}
        title={preview?.title ?? '原文'}
        maxWidth="lg"
      >
        {preview?.text !== undefined && (
          <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>{preview.text}</p>
        )}
        {preview?.chunks?.map((chunk) => (
          <article
            key={chunk.id}
            style={{ padding: '12px 0', borderBottom: '1px solid var(--border)' }}
          >
            <strong>
              {chunk.locatorKind === 'PAGE'
                ? `第 ${chunk.locatorIndex} 页`
                : `第 ${chunk.locatorIndex} 段`}{' '}
              · 片段 {chunk.chunkNo}
            </strong>
            <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.7, marginTop: 8 }}>{chunk.text}</p>
          </article>
        ))}
        {preview?.chunks?.length === 0 && (
          <p className="form-hint">源文件已不可用，或暂无可查看的片段。</p>
        )}
      </Modal>
      <Modal
        isOpen={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        title="移除律所资料"
      >
        <p>
          移除《{deleteTarget?.displayName}
          》后，成员不能再检索、查看或下载这份资料。此操作不可恢复。
        </p>
        <div style={{ marginTop: 20, display: 'flex', gap: 10, justifyContent: 'end' }}>
          <Button
            variant="secondary"
            size="sm"
            disabled={busy}
            onClick={() => setDeleteTarget(null)}
          >
            取消
          </Button>
          <Button variant="danger" size="sm" loading={busy} onClick={() => void remove()}>
            确认移除
          </Button>
        </div>
      </Modal>
    </div>
  );
};
