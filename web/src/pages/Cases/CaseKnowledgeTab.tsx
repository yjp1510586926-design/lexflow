import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import { CaseView } from '../../types/case';
import { TaskView } from '../../types/document';
import { FileView } from '../../types/file';
import { listTasks } from '../../api/tasks';
import { downloadFile, listCaseFiles } from '../../api/files';
import { CaseQuestionPanel } from './CaseQuestionPanel';
import {
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeChunk,
  listKnowledgeBases,
  createKnowledgeBase,
  listKnowledgeDocuments,
  prepareKnowledgeDocument,
  listKnowledgeChunks,
  startKnowledgeIndex,
  searchKnowledge,
  KnowledgeSearchResult,
} from '../../api/knowledge';

export const CaseKnowledgeTab: React.FC<{ caseInfo: CaseView }> = ({ caseInfo }) => {
  const toast = useToast();
  const [bases, setBases] = useState<KnowledgeBase[]>([]);
  const [selectedBaseId, setSelectedBaseId] = useState('');
  const [name, setName] = useState('案件资料');
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [files, setFiles] = useState<FileView[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState('');
  const [chunks, setChunks] = useState<KnowledgeChunk[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [question, setQuestion] = useState('');
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null);

  const [denied, setDenied] = useState(false);
  const alive = useRef(true);
  const deniedRef = useRef(false);
  const accessGeneration = useRef(0);
  const currentCase = useRef(caseInfo.id);
  const currentBase = useRef('');
  const listRead = useRef(0),
    documentRead = useRef(0),
    chunkRead = useRef(0),
    searchRead = useRef(0);
  const operation = useRef(0);
  const operationInFlight = useRef(false);
  currentCase.current = caseInfo.id;

  const clearBase = useCallback(() => {
    documentRead.current++;
    chunkRead.current++;
    searchRead.current++;
    operation.current++;
    operationInFlight.current = false;
    setDocuments([]);
    setChunks(null);
    setSearchResult(null);
    setSelectedTaskId('');
    setQuestion('');
    setBusy(false);
  }, []);

  const selectBase = useCallback(
    (id: string) => {
      if (id === currentBase.current) return;
      currentBase.current = id;
      clearBase();
      setSelectedBaseId(id);
    },
    [clearBase],
  );

  const clear = useCallback(() => {
    currentBase.current = '';
    clearBase();
    setBases([]);
    setFiles([]);
    setTasks([]);
    setSelectedBaseId('');
    setLoading(false);
  }, [clearBase]);

  const current = useCallback(
    (access: number, caseId: string, baseId?: string) =>
      alive.current &&
      !deniedRef.current &&
      access === accessGeneration.current &&
      caseId === currentCase.current &&
      (baseId === undefined || baseId === currentBase.current),
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
        Object.assign(new Error('案件知识库权限已失效'), { status: 403 }),
        '案件知识库权限已失效',
      );
  }, [handleError]);

  const reloadDocuments = useCallback(
    async (baseId: string) => {
      if (!baseId || deniedRef.current) return;
      const access = accessGeneration.current,
        caseId = caseInfo.id,
        round = ++documentRead.current;
      try {
        const items = await listKnowledgeDocuments(baseId);
        if (current(access, caseId, baseId) && round === documentRead.current) setDocuments(items);
      } catch (error: any) {
        if (
          current(access, caseId, baseId) &&
          (error.status === 401 || error.status === 403 || round === documentRead.current)
        )
          handleError(error, '加载已整理文件失败');
      }
    },
    [caseInfo.id, current, handleError],
  );

  const reload = useCallback(async () => {
    if (deniedRef.current) return;
    const access = accessGeneration.current,
      caseId = caseInfo.id,
      round = ++listRead.current;
    setLoading(true);
    try {
      const [nextBases, taskPage, nextFiles] = await Promise.all([
        listKnowledgeBases(caseId),
        listTasks({ caseId, type: 'DOCUMENT_PARSE', status: 'SUCCEEDED', size: 100 }),
        listCaseFiles(caseId),
      ]);
      if (!current(access, caseId) || round !== listRead.current) return;
      setBases(nextBases);
      setTasks(taskPage.items);
      setFiles(nextFiles);
      const nextBase = nextBases.some((base) => base.id === currentBase.current)
        ? currentBase.current
        : (nextBases[0]?.id ?? '');
      if (nextBase === currentBase.current) void reloadDocuments(nextBase);
      else selectBase(nextBase);
    } catch (error: any) {
      if (
        current(access, caseId) &&
        (error.status === 401 || error.status === 403 || round === listRead.current)
      )
        handleError(error, '加载案件资料失败');
    } finally {
      if (current(access, caseId) && round === listRead.current) setLoading(false);
    }
  }, [caseInfo.id, current, handleError, reloadDocuments, selectBase]);

  useEffect(() => {
    alive.current = true;
    accessGeneration.current++;
    deniedRef.current = false;
    setDenied(false);
    clear();
    void reload();
    return () => {
      alive.current = false;
      accessGeneration.current++;
      listRead.current++;
      documentRead.current++;
      chunkRead.current++;
      searchRead.current++;
      operation.current++;
    };
  }, [clear, reload]);

  useEffect(() => {
    if (selectedBaseId) void reloadDocuments(selectedBaseId);
  }, [selectedBaseId, reloadDocuments]);

  const act = async (baseId: string | undefined, run: () => Promise<void>, fallback: string) => {
    if (deniedRef.current || operationInFlight.current) return;
    const access = accessGeneration.current,
      caseId = caseInfo.id,
      round = ++operation.current;
    operationInFlight.current = true;
    setBusy(true);
    try {
      await run();
    } catch (error) {
      if (current(access, caseId, baseId) && round === operation.current)
        handleError(error, fallback);
    } finally {
      if (current(access, caseId, baseId) && round === operation.current) {
        operationInFlight.current = false;
        setBusy(false);
      }
    }
  };

  const create = async () => {
    const access = accessGeneration.current,
      caseId = caseInfo.id;
    await act(
      undefined,
      async () => {
        const base = await createKnowledgeBase(caseId, name.trim());
        if (!current(access, caseId)) return;
        listRead.current++;
        setLoading(false);
        setBases((items) => [base, ...items]);
        selectBase(base.id);
        toast.success('案件知识库已创建');
      },
      '创建失败',
    );
  };

  const prepare = async () => {
    const baseId = currentBase.current,
      access = accessGeneration.current,
      caseId = caseInfo.id;
    if (!baseId || !selectedTaskId) return;
    await act(
      baseId,
      async () => {
        const document = await prepareKnowledgeDocument(baseId, selectedTaskId);
        if (!current(access, caseId, baseId)) return;
        documentRead.current++;
        setDocuments((items) => [document, ...items.filter((item) => item.id !== document.id)]);
        setSelectedTaskId('');
        toast.success('原文已切分，可以点击“开始索引”');
      },
      '准备入库失败',
    );
  };

  const openChunks = async (documentId: string) => {
    const baseId = currentBase.current,
      access = accessGeneration.current,
      caseId = caseInfo.id;
    const round = ++chunkRead.current;
    setChunks(null);
    await act(
      baseId,
      async () => {
        const items = await listKnowledgeChunks(baseId, documentId);
        if (current(access, caseId, baseId) && round === chunkRead.current) setChunks(items);
      },
      '读取切片失败',
    );
  };

  const startIndex = async (documentId: string) => {
    const baseId = currentBase.current,
      access = accessGeneration.current,
      caseId = caseInfo.id;
    await act(
      baseId,
      async () => {
        const task = await startKnowledgeIndex(baseId, documentId);
        if (!current(access, caseId, baseId)) return;
        documentRead.current++;
        setDocuments((items) =>
          items.map((item) =>
            item.id === documentId
              ? {
                  ...item,
                  status: item.status === 'ACTIVE' ? 'ACTIVE' : 'INDEXING',
                  pendingTaskId: task.taskId,
                }
              : item,
          ),
        );
        toast.success(`索引任务 #${task.taskId} 已提交，请稍后刷新状态`);
      },
      '索引提交失败',
    );
  };

  const search = async () => {
    const baseId = currentBase.current,
      access = accessGeneration.current,
      caseId = caseInfo.id;
    if (!baseId || question.trim().length < 2) return;
    const round = ++searchRead.current;
    setSearchResult(null);
    await act(
      baseId,
      async () => {
        const result = await searchKnowledge(baseId, question.trim());
        if (current(access, caseId, baseId) && round === searchRead.current)
          setSearchResult(result);
      },
      '检索失败',
    );
  };

  const download = async (fileId: string, fileName: string) => {
    if (deniedRef.current) return;
    const access = accessGeneration.current,
      caseId = caseInfo.id,
      baseId = currentBase.current;
    try {
      await downloadFile(fileId, fileName, () => current(access, caseId, baseId));
    } catch (error) {
      if (current(access, caseId, baseId)) handleError(error, '下载原件失败');
    }
  };

  const statusName: Record<KnowledgeDocument['status'], string> = {
    PREPARED: '待索引',
    INDEXING: '索引中',
    ACTIVE: '可检索',
    FAILED: '索引失败',
    SUPERSEDED: '已更新',
  };

  const nameForTask = (task: TaskView) => {
    const file = files.find((item) => item.versionId === task.resourceVersionId);
    return `${file?.displayName ?? '已解析文件'} · 任务 #${task.id}`;
  };

  if (denied)
    return (
      <p role="alert" style={{ color: 'var(--danger-text)' }}>
        登录状态或案件知识库权限已失效。旧资料已清空，请重新打开案件或联系管理员。
      </p>
    );

  if (loading) return <Spinner text="正在加载案件知识库..." />;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <p className="form-hint">
        先整理已解析文件，再由律所管理员配置 Embedding API Key
        后主动开始索引。完成索引后可以检索原文或提问；更换向量模型后请重建索引。重建失败或取消保留原索引，检索只使用与当前模型匹配的版本；模型调用可能产生费用。
      </p>
      {bases.length === 0 ? (
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <input
            className="form-input"
            style={{ maxWidth: 280 }}
            value={name}
            maxLength={200}
            onChange={(event) => setName(event.target.value)}
            aria-label="知识库名称"
          />
          <Button
            variant="primary"
            size="sm"
            onClick={create}
            disabled={busy || caseInfo.status === 'ARCHIVED' || !name.trim()}
          >
            创建案件知识库
          </Button>
        </div>
      ) : (
        <div className="form-group">
          <label className="form-label" htmlFor="caseKnowledgeBase">
            案件知识库
          </label>
          <select
            id="caseKnowledgeBase"
            className="form-select"
            style={{ maxWidth: 300 }}
            value={selectedBaseId}
            onChange={(event) => selectBase(event.target.value)}
          >
            {bases.map((base) => (
              <option value={base.id} key={base.id}>
                {base.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {selectedBaseId && (
        <>
          <div className="form-group">
            <label className="form-label" htmlFor="parsedTask">
              选择已完成文字提取的文件
            </label>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <select
                id="parsedTask"
                className="form-select"
                style={{ maxWidth: 350 }}
                value={selectedTaskId}
                onChange={(event) => setSelectedTaskId(event.target.value)}
              >
                <option value="">请选择解析任务</option>
                {tasks.map((task) => (
                  <option value={task.id} key={task.id}>
                    {nameForTask(task)}
                  </option>
                ))}
              </select>
              <Button
                variant="secondary"
                size="sm"
                onClick={prepare}
                disabled={busy || !selectedTaskId || caseInfo.status === 'ARCHIVED'}
              >
                准备入库
              </Button>
              <Button variant="ghost" size="sm" onClick={() => void reload()} disabled={busy}>
                刷新
              </Button>
            </div>
            {tasks.length === 0 && (
              <p className="form-hint">先到“文件与证据管理”上传并提取文字，再返回这里准备入库。</p>
            )}
          </div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <h4 style={{ marginBottom: 10 }}>已整理文件</h4>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => void reloadDocuments(selectedBaseId)}
                disabled={busy}
              >
                刷新状态
              </Button>
            </div>
            {documents.length === 0 ? (
              <p className="form-hint">还没有文件。</p>
            ) : (
              documents.map((document) => (
                <div
                  key={document.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 12,
                    padding: '10px 0',
                    borderBottom: '1px solid var(--border)',
                  }}
                >
                  <span>
                    {document.fileName} · v{document.fileVersion} · {document.chunkCount} 段 ·{' '}
                    {statusName[document.status]}
                    {document.pendingTaskId && document.status === 'ACTIVE' ? ' · 重建中' : ''}
                    {document.pendingTaskId ? ` · 任务 #${document.pendingTaskId}` : ''}
                  </span>
                  <div style={{ display: 'flex', gap: 8 }}>
                    {!document.pendingTaskId &&
                      (document.status === 'PREPARED' ||
                        document.status === 'FAILED' ||
                        document.status === 'ACTIVE') && (
                        <Button
                          variant="primary"
                          size="sm"
                          onClick={() => void startIndex(document.id)}
                          disabled={busy || caseInfo.status === 'ARCHIVED'}
                        >
                          {document.status === 'ACTIVE' ? '重建索引' : '开始索引'}
                        </Button>
                      )}
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => void openChunks(document.id)}
                      disabled={busy}
                    >
                      查看切片
                    </Button>
                  </div>
                </div>
              ))
            )}
          </div>
          {chunks && (
            <div>
              <h4 style={{ marginBottom: 10 }}>原文切片</h4>
              {chunks.length === 0 ? (
                <p className="form-hint">源文件不可用或没有可查看的切片。</p>
              ) : (
                chunks.map((chunk) => (
                  <div
                    key={chunk.id}
                    style={{ padding: 10, borderBottom: '1px solid var(--border)' }}
                  >
                    <strong>
                      {chunk.locatorKind === 'PAGE'
                        ? `第 ${chunk.locatorIndex} 页`
                        : `第 ${chunk.locatorIndex} 段`}{' '}
                      · 切片 {chunk.chunkNo}
                    </strong>
                    <p style={{ whiteSpace: 'pre-wrap', marginTop: 6 }}>{chunk.text}</p>
                  </div>
                ))
              )}
            </div>
          )}
          <div>
            <h4 style={{ marginBottom: 8 }}>检索案件原文</h4>
            <p className="form-hint">
              只查已完成索引的本案件材料。检索可能产生向量模型费用；管理员配置排序模型后，本次检索还会调用排序模型。结果是原文片段。
            </p>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <input
                className="form-input"
                style={{ maxWidth: 440, flex: 1 }}
                value={question}
                maxLength={500}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="例如：合同约定何时付款？"
                aria-label="检索问题"
              />
              <Button
                variant="primary"
                size="sm"
                onClick={() => void search()}
                disabled={busy || question.trim().length < 2}
              >
                检索原文
              </Button>
            </div>
            {searchResult && (
              <div style={{ marginTop: 12 }}>
                <p className="form-hint">
                  {searchResult.reranked
                    ? '已按排序模型的相关性排列。'
                    : '当前按向量相似度排列，未调用排序模型。'}
                </p>
                {searchResult.outdatedDocuments > 0 && (
                  <p className="form-hint">
                    有 {searchResult.outdatedDocuments}{' '}
                    份材料的索引模型与当前配置不同，未参与本次检索。
                  </p>
                )}
                {searchResult.citations.length === 0 ? (
                  <p className="form-hint">没有找到可引用的原文片段。</p>
                ) : (
                  searchResult.citations.map((hit) => (
                    <div
                      key={hit.chunkId}
                      style={{ padding: 10, borderBottom: '1px solid var(--border)' }}
                    >
                      <strong>
                        {hit.fileName} · v{hit.fileVersion} ·{' '}
                        {hit.locatorKind === 'PAGE'
                          ? `第 ${hit.locatorIndex} 页`
                          : `第 ${hit.locatorIndex} 段`}
                      </strong>
                      <p style={{ whiteSpace: 'pre-wrap', margin: '6px 0' }}>{hit.text}</p>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void download(hit.fileId, hit.fileName)}
                      >
                        下载原件
                      </Button>
                    </div>
                  ))
                )}
              </div>
            )}
          </div>
          <CaseQuestionPanel
            key={selectedBaseId}
            baseId={selectedBaseId}
            onAccessLost={loseAccess}
          />
        </>
      )}
    </div>
  );
};
