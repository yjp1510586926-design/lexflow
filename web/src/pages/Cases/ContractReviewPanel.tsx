import React, { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import { KnowledgeBase, listKnowledgeBases } from '../../api/knowledge';
import { TenantKnowledgeBase, listTenantKnowledgeBases } from '../../api/tenant-knowledge';
import {
  ContractView,
  ContractCandidate,
  ContractCitation,
  contractCandidates,
  supplementContract,
  contractCitation,
  downloadContractCitation,
  getContract,
  submitContract,
  approveContract,
  recoverContract,
} from '../../api/contracts';
import { cancelTask } from '../../api/tasks';

const statuses: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '审查中',
  WAITING_HUMAN: '待人工复核',
  SUCCEEDED: '审查完成',
  FAILED: '审查失败',
  CANCELLED: '已取消',
};
const levels = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' };

export function ContractReviewPanel({
  caseId,
  fileVersionId,
  fileName,
  taskId: initialTaskId,
  onAccessLost,
}: {
  caseId?: string;
  fileVersionId?: string;
  fileName?: string;
  taskId?: string;
  onAccessLost?: () => void;
}) {
  const toast = useToast();
  const [taskId, setTaskId] = useState(initialTaskId ?? null);
  const [stance, setStance] = useState('NEUTRAL'),
    [focus, setFocus] = useState(''),
    [base, setBase] = useState('');
  const [bases, setBases] = useState<(KnowledgeBase | TenantKnowledgeBase)[]>([]),
    [baseError, setBaseError] = useState('');
  const [view, setView] = useState<ContractView | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const [note, setNote] = useState(''),
    [confirmed, setConfirmed] = useState(false);
  // 提交与人工决定使用独立幂等标识；提交输入变化时更新 requestId，补材另有 supplementId。
  const requestId = useRef(crypto.randomUUID()),
    decisionId = useRef(crypto.randomUUID());
  // generation 隔离案件/文件/任务切换，readSequence 防止较慢的旧查询覆盖较新的手动刷新。
  const mounted = useRef(true),
    loading = useRef(false),
    generation = useRef(0),
    readSequence = useRef(0);
  const [candidates, setCandidates] = useState<ContractCandidate[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [instructions, setInstructions] = useState(''),
    [costConfirmed, setCostConfirmed] = useState(false),
    [showSupplement, setShowSupplement] = useState(false);
  const [citations, setCitations] = useState<Record<string, ContractCitation>>({});
  const supplementId = useRef(crypto.randomUUID());
  const canPoll = useRef(true),
    accessLost = useRef(false);
  const [pollCycle, setPollCycle] = useState(0);
  const current = (ticket: number) => mounted.current && ticket === generation.current;
  const failure = (e: unknown, ticket: number, fallback: string) => {
    if (!current(ticket)) return;
    if ([401, 403].includes((e as { status?: number })?.status ?? 0)) {
      // 立即撤销当前界面的材料、引用和确认状态，并使在途请求及下载失效。
      generation.current++;
      loading.current = false;
      canPoll.current = false;
      accessLost.current = true;
      setView(null);
      setBases([]);
      setCandidates([]);
      setCitations({});
      setSelected([]);
      setNote('');
      setInstructions('');
      setConfirmed(false);
      setCostConfirmed(false);
      setShowSupplement(false);
      setBusy(false);
      onAccessLost?.();
    }
    setError(e instanceof Error ? e.message : fallback);
  };
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current++;
    };
  }, []);
  useEffect(() => {
    generation.current++;
    loading.current = false;
    accessLost.current = false;
    setTaskId(initialTaskId ?? null);
    setView(null);
    setCitations({});
    setCandidates([]);
    setSelected([]);
    setInstructions('');
    setNote('');
    setFocus('');
    setStance('NEUTRAL');
    setBase('');
    setBases([]);
    setConfirmed(false);
    setCostConfirmed(false);
    setShowSupplement(false);
    setError('');
    setBusy(false);
    requestId.current = crypto.randomUUID();
    decisionId.current = crypto.randomUUID();
    supplementId.current = crypto.randomUUID();
  }, [initialTaskId, caseId, fileVersionId]);
  useEffect(() => {
    if (!caseId || taskId) return;
    let active = true;
    const ticket = generation.current;
    setBases([]);
    setBaseError('');
    Promise.allSettled([listKnowledgeBases(caseId), listTenantKnowledgeBases()]).then(
      ([privateBases, publicBases]) => {
        if (!active || !current(ticket)) return;
        const denied = [privateBases, publicBases].find(
          (result) => result.status === 'rejected' && result.reason?.status === 401,
        );
        if (denied?.status === 'rejected') {
          failure(denied.reason, ticket, '登录已失效');
          return;
        }
        setBases([
          ...(privateBases.status === 'fulfilled' ? privateBases.value : []),
          ...(publicBases.status === 'fulfilled' ? publicBases.value.items : []),
        ]);
        if (privateBases.status === 'rejected' || publicBases.status === 'rejected')
          setBaseError('部分知识库无权限或暂时无法读取，可选择已显示的资料，也可只审查合同原文。');
      },
    );
    return () => {
      active = false;
    };
  }, [caseId, taskId]);
  const refresh = async (id: string, force = false) => {
    if (loading.current && !force) return;
    loading.current = true;
    const ticket = generation.current,
      sequence = ++readSequence.current;
    try {
      const next = await getContract(id);
      if (current(ticket) && sequence === readSequence.current) {
        setView(next);
        setError('');
        canPoll.current = ['QUEUED', 'RUNNING'].includes(next.status);
      }
    } catch (e) {
      if (current(ticket)) {
        // 权限失败始终优先清空；普通查询失败只有在本轮仍是最新读取时才影响界面。
        if ([401, 403].includes((e as { status?: number })?.status ?? 0))
          failure(e, ticket, '读取审查结果失败');
        else if (sequence === readSequence.current) {
          setView(null);
          failure(e, ticket, '读取审查结果失败');
        }
      }
    } finally {
      if (current(ticket) && sequence === readSequence.current) loading.current = false;
    }
  };
  const refreshAndPoll = async () => {
    if (!taskId || accessLost.current) return;
    const ticket = generation.current;
    await refresh(taskId, true);
    if (current(ticket) && !accessLost.current) setPollCycle((value) => value + 1);
  };
  useEffect(() => {
    if (!taskId || accessLost.current) return;
    canPoll.current = true;
    void refresh(taskId);
    let rounds = 0;
    // 只对排队/执行状态查询，最多约三分钟；人工复核或投递故障由显式操作推进。
    const timer = window.setInterval(() => {
      if (++rounds >= 60) window.clearInterval(timer);
      if (canPoll.current) void refresh(taskId);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [taskId, pollCycle]);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!caseId || !fileVersionId) return;
    setBusy(true);
    setError('');
    const ticket = generation.current;
    try {
      const result = await submitContract({
        caseId,
        fileVersionId,
        stance,
        focus,
        knowledgeBaseId: base || null,
        idempotencyKey: requestId.current,
      });
      if (current(ticket)) setTaskId(result.taskId);
    } catch (e) {
      failure(e, ticket, '提交审查失败');
    } finally {
      if (current(ticket)) setBusy(false);
    }
  };
  const act = async (action: () => Promise<unknown>, message: string) => {
    if (!taskId) return;
    setBusy(true);
    const ticket = generation.current;
    try {
      await action();
      if (!current(ticket)) return;
      toast.success(message);
      setConfirmed(false);
      setNote('');
      setShowSupplement(false);
      setCostConfirmed(false);
      setSelected([]);
      setInstructions('');
      setCitations({});
      await refreshAndPoll();
    } catch (e) {
      failure(e, ticket, '操作失败');
    } finally {
      if (current(ticket)) setBusy(false);
    }
  };
  const openSupplement = async () => {
    if (!taskId) return;
    const ticket = generation.current;
    setBusy(true);
    try {
      const options = await contractCandidates(taskId);
      if (current(ticket)) {
        setCandidates(options);
        setShowSupplement(true);
      }
    } catch (e) {
      failure(e, ticket, '读取已解析材料失败');
    } finally {
      if (current(ticket)) setBusy(false);
    }
  };
  const showCitation = async (id: string) => {
    if (!taskId) return;
    const ticket = generation.current;
    try {
      const source = await contractCitation(taskId, id);
      if (current(ticket)) {
        setCitations((prev) => ({ ...prev, [id]: source }));
        window.requestAnimationFrame(() => {
          if (current(ticket))
            document
              .getElementById(`contract-reference-${id}`)
              ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
      }
    } catch (e) {
      failure(e, ticket, '读取出处失败');
    }
  };
  const downloadCitation = async (source: ContractCitation) => {
    if (!taskId) return;
    const ticket = generation.current;
    try {
      await downloadContractCitation(taskId, source.id, source.fileName, () => current(ticket));
    } catch (e) {
      failure(e, ticket, '下载原件失败');
    }
  };
  if (!taskId)
    return (
      <form onSubmit={submit} style={{ display: 'grid', gap: 16 }}>
        <p>审查文件：{fileName ?? '所选合同版本'}</p>
        <label>
          审查立场
          <select
            className="form-select"
            value={stance}
            onChange={(e) => {
              setStance(e.target.value);
              requestId.current = crypto.randomUUID();
            }}
          >
            <option value="NEUTRAL">中立审查</option>
            <option value="PARTY_A">代表甲方</option>
            <option value="PARTY_B">代表乙方</option>
          </select>
        </label>
        <label>
          关注点（选填）
          <textarea
            className="form-input"
            maxLength={500}
            rows={3}
            value={focus}
            placeholder="例如：付款期限、违约责任、解除条件"
            onChange={(e) => {
              setFocus(e.target.value);
              requestId.current = crypto.randomUUID();
            }}
          />
        </label>
        <label>
          参考资料（选填）
          <select
            className="form-select"
            value={base}
            onChange={(e) => {
              setBase(e.target.value);
              requestId.current = crypto.randomUUID();
            }}
          >
            <option value="">仅审查合同原文</option>
            {bases.map((b) => (
              <option value={b.id} key={b.id}>
                {b.scope === 'TENANT' ? '律所' : '案件'} · {b.name}
              </option>
            ))}
          </select>
        </label>
        {baseError && <p style={{ color: 'var(--text-muted)' }}>{baseError}</p>}
        <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
          支持 TXT、DOCX 和含文字层 PDF，提取文字最多 4
          万字。提交后会使用本律所手动配置的模型，可能产生费用。未提供适用法律依据时，结果会进入人工复核。
        </p>
        {error && (
          <p role="alert" style={{ color: 'var(--danger-text)' }}>
            {error}
          </p>
        )}
        <Button type="submit" loading={busy}>
          提交审查
        </Button>
      </form>
    );
  return (
    <div style={{ display: 'grid', gap: 16, fontSize: 13, lineHeight: 1.7 }}>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {error}
        </p>
      )}
      {!view && !error && <Spinner text="读取审查状态…" />}
      {view && (
        <>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              gap: 12,
              alignItems: 'center',
            }}
          >
            <Badge
              variant={
                view.status === 'SUCCEEDED'
                  ? 'emerald'
                  : view.status === 'WAITING_HUMAN'
                    ? 'purple'
                    : 'slate'
              }
            >
              {view.waitingReason === 'DELIVERY'
                ? '待投递处理'
                : (statuses[view.status] ?? view.status)}
            </Badge>
            <Button size="sm" variant="secondary" onClick={refreshAndPoll}>
              刷新
            </Button>
          </div>
          {['QUEUED', 'RUNNING'].includes(view.status) && (
            <p>正在处理合同，结果会自动更新。关闭窗口后可在任务中心继续查看。</p>
          )}
          {view.status === 'FAILED' && (
            <p>本次审查未完成。请检查材料、模型配置及服务状态，再主动提交新的审查。</p>
          )}
          {view.waitingReason === 'DELIVERY' && (
            <p role="status">
              投递重试已结束，请在任务中心的「处理记录」中核查并确认恢复。本页暂停人工复核和补材续审，已保存的报告、材料和复核意见继续保留。
            </p>
          )}
          {view.waitingReason === 'UNCERTAIN' && (
            <p role="alert">
              上次模型请求的结果不确定，系统不会自动重复调用。请取消本次审查，确认后再决定是否重新提交。
            </p>
          )}
          {view.report && (
            <>
              <p>
                <strong>{view.report.contractType}</strong> · 第 {(view.round ?? 0) + 1} 轮审查 ·{' '}
                {view.stance === 'PARTY_A'
                  ? '代表甲方'
                  : view.stance === 'PARTY_B'
                    ? '代表乙方'
                    : '中立审查'}
              </p>
              <p style={{ color: 'var(--text-muted)' }}>
                以下为 AI 辅助分析与修改建议。
                {view.decidedBy ? '已记录人工复核意见。' : '结果尚待核对。'}
              </p>
              {!view.report.evidence.length && (
                <p style={{ color: 'var(--warning-text)' }}>
                  未取得适用法律资料，以下建议仅基于合同文字，不能据此判断条款的法律效力。
                </p>
              )}
              {view.report.clauses.map((clause) => (
                <section
                  key={clause.id}
                  style={{ border: '1px solid var(--border-strong)', borderRadius: 8, padding: 14 }}
                >
                  <strong>{clause.title}</strong>
                  <blockquote
                    style={{
                      whiteSpace: 'pre-wrap',
                      margin: '10px 0',
                      padding: '8px 12px',
                      borderLeft: '3px solid var(--border-strong)',
                      background: 'var(--bg-subtle)',
                    }}
                  >
                    {clause.quote}
                  </blockquote>
                  {view
                    .report!.risks.filter((r) => r.clauseId === clause.id)
                    .map((risk, i) => (
                      <div key={i} style={{ marginTop: 10 }}>
                        <Badge
                          variant={
                            risk.level === 'HIGH'
                              ? 'rose'
                              : risk.level === 'MEDIUM'
                                ? 'amber'
                                : 'slate'
                          }
                        >
                          {levels[risk.level]}
                        </Badge>
                        <p style={{ marginTop: 6 }}>{risk.reason}</p>
                        <p>
                          <strong>修改建议：</strong>
                          {risk.suggestion}
                        </p>
                        {!!risk.evidenceIds.length && (
                          <details>
                            <summary>查看参考资料</summary>
                            {risk.evidenceIds.map((id) => (
                              <div key={id}>
                                <blockquote>
                                  {view.report!.evidence.find((e) => e.id === id)?.text}
                                </blockquote>
                                <Button
                                  size="sm"
                                  variant="secondary"
                                  onClick={() => showCitation(id)}
                                >
                                  定位出处 [{id}]
                                </Button>
                              </div>
                            ))}
                          </details>
                        )}
                      </div>
                    ))}
                </section>
              ))}
              {!!view.report.missingMaterials.length && (
                <div>
                  <strong>需要补充或核实的材料</strong>
                  <ul>
                    {view.report.missingMaterials.map((m, i) => (
                      <li key={i}>{m}</li>
                    ))}
                  </ul>
                </div>
              )}
              {!!view.report.basisConflicts?.length && (
                <section
                  role="alert"
                  style={{ border: '1px solid var(--danger-text)', borderRadius: 8, padding: 14 }}
                >
                  <strong>参考依据存在冲突，需人工核实适用范围和有效时间</strong>
                  {view.report.basisConflicts.map((c, i) => (
                    <div key={i}>
                      <p>{c.description}</p>
                      {c.evidenceIds.map((id) => (
                        <Button
                          key={id}
                          size="sm"
                          variant="secondary"
                          onClick={() => showCitation(id)}
                        >
                          定位冲突依据 [{id}]
                        </Button>
                      ))}
                    </div>
                  ))}
                </section>
              )}
              {Object.values(citations).map((source) => (
                <section
                  id={`contract-reference-${source.id}`}
                  key={source.id}
                  style={{ border: '1px solid var(--border-strong)', padding: 14, borderRadius: 8 }}
                >
                  <strong>
                    {source.fileName} · v{source.fileVersion} · 第 {source.locatorIndex}{' '}
                    {source.locatorKind === 'PAGE' ? '页' : '段'}
                  </strong>
                  <p>
                    原文字符位置 {source.startOffset}–{source.endOffset}
                  </p>
                  <blockquote style={{ whiteSpace: 'pre-wrap' }}>{source.text}</blockquote>
                  <Button size="sm" variant="secondary" onClick={() => downloadCitation(source)}>
                    下载所引用版本原件
                  </Button>
                </section>
              ))}
              {!!view.supplements?.length && (
                <details>
                  <summary>已固定的补充材料（{view.supplements.length}份）</summary>
                  {view.supplements.map((s) => (
                    <section key={s.parseTaskId}>
                      <strong>{s.fileName}</strong>
                      {s.segments.map((segment) => (
                        <div key={segment.index}>
                          <p>
                            第 {segment.index} {segment.kind === 'PAGE' ? '页' : '段'}
                          </p>
                          <pre style={{ whiteSpace: 'pre-wrap' }}>
                            {s.text.slice(segment.startOffset, segment.endOffset)}
                          </pre>
                        </div>
                      ))}
                    </section>
                  ))}
                </details>
              )}
              <details>
                <summary>查看完整合同原文</summary>
                <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {view.report.sourceText}
                </pre>
              </details>
              {view.decisionNote && (
                <p>
                  <strong>复核意见：</strong>
                  {view.decisionNote}
                </p>
              )}
              {view.status === 'WAITING_HUMAN' && view.waitingReason === 'REVIEW' && (
                <div style={{ display: 'grid', gap: 12 }}>
                  <Button variant="secondary" loading={busy} onClick={openSupplement}>
                    补充已解析材料并续审
                  </Button>
                  {showSupplement && (
                    <section
                      style={{
                        display: 'grid',
                        gap: 12,
                        border: '1px solid var(--border-strong)',
                        padding: 14,
                        borderRadius: 8,
                      }}
                    >
                      <p>
                        选择案件中已解析成功的新材料（最多5份）。版本、原文和页段将固定保存，累计最多4万字。
                      </p>
                      {!candidates.length && <p>暂无可用材料，请先在案件文件页上传并完成解析。</p>}
                      {candidates
                        .filter(
                          (c) => !view.supplements?.some((s) => s.parseTaskId === c.parseTaskId),
                        )
                        .map((c) => (
                          <label key={c.parseTaskId} style={{ display: 'flex', gap: 8 }}>
                            <input
                              type="checkbox"
                              checked={selected.includes(c.parseTaskId)}
                              disabled={!selected.includes(c.parseTaskId) && selected.length >= 5}
                              onChange={(e) => {
                                setSelected((prev) =>
                                  e.target.checked
                                    ? [...prev, c.parseTaskId]
                                    : prev.filter((id) => id !== c.parseTaskId),
                                );
                                supplementId.current = crypto.randomUUID();
                              }}
                            />
                            {c.fileName} · v{c.fileVersion}
                          </label>
                        ))}
                      <label>
                        补充说明（选填）
                        <textarea
                          className="form-input"
                          maxLength={1000}
                          rows={3}
                          value={instructions}
                          onChange={(e) => {
                            setInstructions(e.target.value);
                            supplementId.current = crypto.randomUUID();
                          }}
                        />
                      </label>
                      <label style={{ display: 'flex', gap: 8 }}>
                        <input
                          type="checkbox"
                          checked={costConfirmed}
                          onChange={(e) => setCostConfirmed(e.target.checked)}
                        />
                        确认提交新一轮审查，检索及模型分析可能产生新的费用。
                      </label>
                      <Button
                        loading={busy}
                        disabled={!costConfirmed || !selected.length}
                        onClick={() =>
                          act(
                            () =>
                              supplementContract(
                                taskId,
                                view.stateVersion,
                                supplementId.current,
                                selected,
                                instructions,
                                costConfirmed,
                              ),
                            '补充材料已固定，正在进行新一轮审查',
                          )
                        }
                      >
                        提交补材续审
                      </Button>
                    </section>
                  )}
                  <label>
                    复核意见
                    <textarea
                      className="form-input"
                      rows={3}
                      maxLength={500}
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="记录已核对的条款、依据，以及仍需处理的问题"
                    />
                  </label>
                  <label style={{ display: 'flex', gap: 8, alignItems: 'start' }}>
                    <input
                      type="checkbox"
                      checked={confirmed}
                      onChange={(e) => setConfirmed(e.target.checked)}
                    />
                    我已核对原文、修改建议和参考依据冲突，了解缺少的法律依据或材料仍需另行确认。
                  </label>
                  <Button
                    loading={busy}
                    disabled={!confirmed || !note.trim()}
                    onClick={() =>
                      act(
                        () => approveContract(taskId, view.stateVersion, decisionId.current, note),
                        '复核意见已保存，正在完成报告',
                      )
                    }
                  >
                    确认已复核
                  </Button>
                </div>
              )}
            </>
          )}
          {['QUEUED', 'RUNNING', 'WAITING_HUMAN'].includes(view.status) && (
            <div style={{ display: 'flex', gap: 10 }}>
              {['QUEUED', 'RUNNING'].includes(view.status) && (
                <Button
                  size="sm"
                  variant="secondary"
                  loading={busy}
                  onClick={() =>
                    act(
                      () => recoverContract(taskId, view.stateVersion),
                      '恢复请求已提交，不会自动重做已有模型步骤',
                    )
                  }
                >
                  恢复处理
                </Button>
              )}
              <Button
                size="sm"
                variant="danger"
                loading={busy}
                onClick={() => act(() => cancelTask(taskId), '审查已取消')}
              >
                取消审查
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
