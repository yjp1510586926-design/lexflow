import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Spinner } from '../../components/common/Spinner';
import { Modal } from '../../components/common/Modal';
import { useToast } from '../../components/common/Toast';
import { ActivityIcon, RefreshCwIcon, XIcon } from '../../components/common/Icons';
import { listTasks, cancelTask, getParsedText, isDeliveryRecoveryType } from '../../api/tasks';
import { TaskView } from '../../types/document';
import { ContractReviewPanel } from '../Cases/ContractReviewPanel';
import { DocumentExportPanel } from '../Cases/DocumentExportPanel';
import { DocumentGenerationPanel } from '../Cases/DocumentGenerationPanel';
import { TaskDeliveryRecoveryPanel } from './TaskDeliveryRecoveryPanel';
import { CaseAnalysisResultPanel } from '../Cases/CaseAnalysisResultPanel';

const TASK_TYPE_LABELS: Record<string, string> = {
  DOCUMENT_PARSE: '文书解析',
  KNOWLEDGE_INGEST: '知识入库',
  CONTRACT_REVIEW: '合同审查',
  CASE_ANALYSIS: '案件分析',
  EXPORT: '文书导出',
  DOCUMENT_GENERATE: 'AI 文书生成',
};

const TASK_STATUS_CONFIG: Record<string, { variant: any; label: string }> = {
  QUEUED: { variant: 'slate', label: '排队中' },
  RUNNING: { variant: 'amber', label: '执行中' },
  WAITING_HUMAN: { variant: 'purple', label: '待人工确认' },
  SUCCEEDED: { variant: 'emerald', label: '成功' },
  FAILED: { variant: 'rose', label: '失败' },
  CANCEL_REQUESTED: { variant: 'orange', label: '取消中' },
  CANCELLED: { variant: 'slate', label: '已取消' },
};

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

const taskObject = (task: TaskView) =>
  task.caseId
    ? `案件 #${task.caseId}`
    : ['DOCUMENT_PARSE', 'KNOWLEDGE_INGEST'].includes(task.type)
      ? '律所知识库'
      : '—';

const taskTime = (value: string | null) => {
  if (!value) return '—';
  if (!/(Z|[+-]\d{2}:?\d{2})$/i.test(value)) return `${value.replace('T', ' ')}（时区未标注）`;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
};

export const TasksPage: React.FC = () => {
  const toast = useToast();
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [typeFilter, setTypeFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [parsed, setParsed] = useState<{
    taskId: string;
    text: string;
    sourceSha256: string;
  } | null>(null);
  const [openingResult, setOpeningResult] = useState<string | null>(null);
  const [exportTask, setExportTask] = useState<string | null>(null);
  const [contractTask, setContractTask] = useState<string | null>(null);
  const [generationTask, setGenerationTask] = useState<string | null>(null);
  const [recoveryTask, setRecoveryTask] = useState<string | null>(null);
  const [analysisTask, setAnalysisTask] = useState<string | null>(null);
  const alive = useRef(true);
  const accessGeneration = useRef(0);
  const listRead = useRef(0);
  const parsedRead = useRef(0);
  const cancelInFlight = useRef(false);
  const SIZE = 20;

  const loseAccess = useCallback(() => {
    accessGeneration.current++;
    listRead.current++;
    parsedRead.current++;
    setTasks([]);
    setTotal(0);
    setParsed(null);
    setExportTask(null);
    setContractTask(null);
    setGenerationTask(null);
    setRecoveryTask(null);
    setAnalysisTask(null);
    setLoading(false);
    setOpeningResult(null);
    setCancelling(null);
    toast.error('登录状态或任务权限已失效，请重新登录或联系管理员。');
  }, [toast]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      accessGeneration.current++;
      listRead.current++;
      parsedRead.current++;
    };
  }, []);

  const fetchTasks = useCallback(
    async (p = 1, filters = { type: typeFilter, status: statusFilter }) => {
      const round = ++listRead.current;
      const access = accessGeneration.current;
      setLoading(true);
      try {
        const result = await listTasks({
          page: p,
          size: SIZE,
          type: filters.type || undefined,
          status: filters.status || undefined,
        });
        if (!alive.current || access !== accessGeneration.current || round !== listRead.current)
          return;
        setTasks(result.items);
        setTotal(result.total);
        setPage(p);
      } catch (err: any) {
        if (!alive.current || access !== accessGeneration.current) return;
        if (err.status === 401 || err.status === 403) loseAccess();
        else if (round === listRead.current) toast.error(err.message || '加载任务列表失败');
      } finally {
        if (alive.current && access === accessGeneration.current && round === listRead.current)
          setLoading(false);
      }
    },
    [typeFilter, statusFilter, toast, loseAccess],
  );

  useEffect(() => {
    fetchTasks(1);
  }, []);

  const handleCancel = async (task: TaskView) => {
    if (cancelInFlight.current) return;
    cancelInFlight.current = true;
    const access = accessGeneration.current;
    setCancelling(task.id);
    try {
      const updated = await cancelTask(task.id);
      if (!alive.current || access !== accessGeneration.current) return;
      toast.success(`任务 #${task.id} 取消请求已提交`);
      setTasks((prev) => prev.map((t) => (t.id === task.id ? updated : t)));
    } catch (err: any) {
      if (!alive.current || access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess();
      else toast.error(err.message || '取消任务失败');
    } finally {
      cancelInFlight.current = false;
      if (alive.current && access === accessGeneration.current) setCancelling(null);
    }
  };

  const openParsedText = async (taskId: string) => {
    const round = ++parsedRead.current;
    const access = accessGeneration.current;
    setOpeningResult(taskId);
    try {
      const result = await getParsedText(taskId);
      if (!alive.current || access !== accessGeneration.current || round !== parsedRead.current)
        return;
      setParsed({ taskId, ...result });
    } catch (err: any) {
      if (!alive.current || access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess();
      else if (round === parsedRead.current) toast.error(err.message || '读取解析结果失败');
    } finally {
      if (alive.current && access === accessGeneration.current && round === parsedRead.current)
        setOpeningResult(null);
    }
  };

  const totalPages = Math.ceil(total / SIZE);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card
        title="异步任务中心"
        subtitle="查看文字提取、知识入库、合同审查、案件分析、AI 文书生成和导出；任务可在案件中提交"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* 过滤栏 */}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
            <select
              className="form-select"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              style={{ height: 32, fontSize: 12, minWidth: 140 }}
            >
              <option value="">全部类型</option>
              <option value="DOCUMENT_PARSE">文书解析</option>
              <option value="KNOWLEDGE_INGEST">知识入库</option>
              <option value="CONTRACT_REVIEW">合同审查</option>
              <option value="CASE_ANALYSIS">案件分析</option>
              <option value="DOCUMENT_GENERATE">AI 文书生成</option>
              <option value="EXPORT">文书导出</option>
            </select>
            <select
              className="form-select"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              style={{ height: 32, fontSize: 12, minWidth: 130 }}
            >
              <option value="">全部状态</option>
              <option value="QUEUED">排队中</option>
              <option value="RUNNING">执行中</option>
              <option value="WAITING_HUMAN">待人工确认</option>
              <option value="SUCCEEDED">成功</option>
              <option value="FAILED">失败</option>
              <option value="CANCEL_REQUESTED">取消中</option>
              <option value="CANCELLED">已取消</option>
            </select>
            <Button variant="secondary" size="sm" onClick={() => fetchTasks(1)}>
              查询
            </Button>
            <Button
              variant="ghost"
              size="sm"
              icon={<RefreshCwIcon size={13} />}
              onClick={() => {
                setTypeFilter('');
                setStatusFilter('');
                void fetchTasks(1, { type: '', status: '' });
              }}
            >
              重置
            </Button>
            <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--text-muted)' }}>
              共 {total} 个任务
            </span>
          </div>

          {/* 任务表格 */}
          {loading ? (
            <div style={{ padding: '32px 0' }}>
              <Spinner text="加载任务..." />
            </div>
          ) : tasks.length === 0 ? (
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
              <p style={{ fontSize: 14, color: 'var(--text-muted)' }}>
                {typeFilter || statusFilter ? '没有符合条件的任务' : '暂无异步任务记录'}
              </p>
            </div>
          ) : (
            <>
              <div className="table-responsive">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>任务 ID</th>
                      <th>类型</th>
                      <th>关联对象</th>
                      <th>提交人</th>
                      <th>状态</th>
                      <th>重试/恢复次数</th>
                      <th>错误信息</th>
                      <th>提交时间</th>
                      <th>开始时间</th>
                      <th>完成时间</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((t) => {
                      const statusCfg = TASK_STATUS_CONFIG[t.status] ?? {
                        variant: 'slate',
                        label: t.status,
                      };
                      const isTerminal = TERMINAL.has(t.status);
                      return (
                        <tr key={t.id}>
                          <td>
                            <code
                              style={{
                                fontSize: 11,
                                fontFamily: 'var(--font-mono)',
                                color: 'var(--text-secondary)',
                              }}
                            >
                              #{t.id}
                            </code>
                          </td>
                          <td>
                            <Badge variant="slate">{TASK_TYPE_LABELS[t.type] ?? t.type}</Badge>
                          </td>
                          <td style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                            {taskObject(t)}
                            {t.resourceVersionId && (
                              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                源版本 #{t.resourceVersionId}
                              </div>
                            )}
                          </td>
                          <td style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                            {t.createdBy ? `用户 #${t.createdBy}` : '—'}
                          </td>
                          <td>
                            <Badge variant={statusCfg.variant}>{statusCfg.label}</Badge>
                          </td>
                          <td style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                            {t.attempt} 次
                          </td>
                          <td style={{ maxWidth: 200 }}>
                            {t.errorMessage ? (
                              <span
                                style={{
                                  fontSize: 11,
                                  color: 'var(--danger-text)',
                                  display: 'block',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  whiteSpace: 'nowrap',
                                }}
                                title={t.errorMessage}
                              >
                                {t.errorMessage}
                              </span>
                            ) : (
                              <span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>—</span>
                            )}
                          </td>
                          <td
                            style={{
                              fontSize: 11,
                              color: 'var(--text-muted)',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {taskTime(t.createdAt)}
                          </td>
                          <td
                            style={{
                              fontSize: 11,
                              color: 'var(--text-muted)',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {taskTime(t.startedAt)}
                          </td>
                          <td
                            style={{
                              fontSize: 11,
                              color: 'var(--text-muted)',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {taskTime(t.finishedAt)}
                          </td>
                          <td>
                            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                              {isDeliveryRecoveryType(t.type) && (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setRecoveryTask(t.id)}
                                >
                                  处理记录
                                </Button>
                              )}
                              {t.type === 'EXPORT' ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setExportTask(t.id)}
                                >
                                  查看导出
                                </Button>
                              ) : t.type === 'CONTRACT_REVIEW' ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setContractTask(t.id)}
                                >
                                  查看审查
                                </Button>
                              ) : t.type === 'CASE_ANALYSIS' ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setAnalysisTask(t.id)}
                                >
                                  查看分析
                                </Button>
                              ) : t.type === 'DOCUMENT_GENERATE' ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setGenerationTask(t.id)}
                                >
                                  查看生成
                                </Button>
                              ) : t.type === 'DOCUMENT_PARSE' && t.status === 'SUCCEEDED' ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  loading={openingResult === t.id}
                                  onClick={() => openParsedText(t.id)}
                                >
                                  查看原文
                                </Button>
                              ) : !isTerminal && t.status !== 'CANCEL_REQUESTED' ? (
                                <Button
                                  variant="danger"
                                  size="sm"
                                  icon={<XIcon size={12} />}
                                  disabled={cancelling !== null}
                                  loading={cancelling === t.id}
                                  onClick={() => handleCancel(t)}
                                >
                                  取消
                                </Button>
                              ) : !['DOCUMENT_PARSE', 'KNOWLEDGE_INGEST'].includes(t.type) ? (
                                <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
                                  —
                                </span>
                              ) : null}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {totalPages > 1 && (
                <div className="pagination-wrapper">
                  <span>
                    第 {page} / {totalPages} 页，共 {total} 个
                  </span>
                  <div className="pagination-actions">
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={page <= 1}
                      onClick={() => fetchTasks(page - 1)}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={page >= totalPages}
                      onClick={() => fetchTasks(page + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </Card>
      <Modal
        isOpen={exportTask !== null}
        onClose={() => {
          setExportTask(null);
          void fetchTasks(page);
        }}
        title="文书导出"
      >
        {exportTask && (
          <DocumentExportPanel key={exportTask} taskId={exportTask} onAccessLost={loseAccess} />
        )}
      </Modal>
      <Modal
        isOpen={contractTask !== null}
        onClose={() => {
          setContractTask(null);
          void fetchTasks(page);
        }}
        title="合同审查"
        maxWidth="lg"
      >
        {contractTask && (
          <ContractReviewPanel key={contractTask} taskId={contractTask} onAccessLost={loseAccess} />
        )}
      </Modal>
      <Modal
        isOpen={generationTask !== null}
        onClose={() => {
          setGenerationTask(null);
          void fetchTasks(page);
        }}
        title="AI 文书生成"
        maxWidth="lg"
      >
        {generationTask && (
          <DocumentGenerationPanel
            key={generationTask}
            taskId={generationTask}
            onAccessLost={loseAccess}
          />
        )}
      </Modal>
      <Modal
        isOpen={analysisTask !== null}
        onClose={() => {
          setAnalysisTask(null);
          void fetchTasks(page);
        }}
        title="案件分析"
        maxWidth="lg"
      >
        {analysisTask && (
          <CaseAnalysisResultPanel
            key={analysisTask}
            taskId={analysisTask}
            onChanged={() => void fetchTasks(page)}
            onAccessLost={loseAccess}
          />
        )}
      </Modal>
      <Modal
        isOpen={recoveryTask !== null}
        onClose={() => {
          setRecoveryTask(null);
          void fetchTasks(page);
        }}
        title="任务处理记录"
      >
        {recoveryTask && (
          <TaskDeliveryRecoveryPanel
            key={recoveryTask}
            taskId={recoveryTask}
            onAccessLost={loseAccess}
          />
        )}
      </Modal>
      <Modal
        isOpen={parsed !== null}
        onClose={() => {
          parsedRead.current++;
          setParsed(null);
        }}
        title="提取的原文"
        subtitle={parsed ? `任务 #${parsed.taskId} · 仅提取文本，未进行 AI 法律分析` : ''}
        maxWidth="lg"
      >
        {parsed && (
          <>
            <p style={{ fontSize: 12, color: 'var(--text-muted)', overflowWrap: 'anywhere' }}>
              源文件 SHA-256：{parsed.sourceSha256}
            </p>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
                maxHeight: 480,
                overflowY: 'auto',
                marginTop: 16,
                lineHeight: 1.7,
              }}
            >
              {parsed.text}
            </pre>
          </>
        )}
      </Modal>
    </div>
  );
};
