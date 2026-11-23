import React, { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { useToast } from '../../components/common/Toast';
import {
  getAnalysis,
  approveAnalysis,
  recoverAnalysis,
  CaseAnalysisView,
  AnalysisCitation,
} from '../../api/case-analysis';
import './CaseAnalysisResultPanel.css';

const statusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '分析中',
  WAITING_HUMAN: '待人工复核',
  SUCCEEDED: '已完成',
  FAILED: '分析失败',
  CANCEL_REQUESTED: '取消中',
  CANCELLED: '已取消',
};
export const caseAnalysisStatusLabel = (status: string) => statusLabels[status] ?? status;

export const CaseAnalysisResultPanel: React.FC<{
  taskId: string;
  onChanged?: () => void;
  onAccessLost?: () => void;
}> = ({ taskId, onChanged, onAccessLost }) => {
  const toast = useToast();
  const [view, setView] = useState<CaseAnalysisView | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const [note, setNote] = useState(''),
    [citation, setCitation] = useState<AnalysisCitation | null>(null);
  const [citationTarget, setCitationTarget] = useState<{ id: string; group: string } | null>(null);
  const [readingCitation, setReadingCitation] = useState(false);
  const [citationError, setCitationError] = useState('');
  const citationSequence = useRef(0);
  const citationPanel = useRef<HTMLDivElement>(null);
  const citationOpener = useRef<HTMLButtonElement | null>(null);
  const requestController = useRef<AbortController | null>(null);
  const currentTask = useRef(taskId);
  currentTask.current = taskId;
  // 每次刷新增加代次，只接受当前任务最近一次读取；decision 另绑定复核版本与意见。
  const generation = useRef(0),
    decision = useRef<{ version: number; note: string; id: string } | null>(null),
    mounted = useRef(true),
    denied = useRef(false);
  const fail = (failure: unknown) => {
    const e = failure as { status?: number; message?: string };
    if (e.status === 401 || e.status === 403) {
      denied.current = true;
      generation.current++;
      setBusy(false);
      setView(null);
      setCitation(null);
      citationSequence.current++;
      setCitationTarget(null);
      setReadingCitation(false);
      setCitationError('');
      setNote('');
      decision.current = null;
      onAccessLost?.();
    }
    setError(e.message ?? '读取案件分析失败');
    toast.error(e.message ?? '读取案件分析失败');
  };
  const refresh = async (preserveCitationRequest = false) => {
    if (denied.current) return null;
    if (!preserveCitationRequest) {
      citationSequence.current++;
      setCitation(null);
      setCitationTarget(null);
      setReadingCitation(false);
      setCitationError('');
    }
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    const requestedTask = taskId;
    const token = ++generation.current;
    setBusy(true);
    try {
      const next = await getAnalysis(taskId, controller.signal);
      if (!mounted.current || token !== generation.current || currentTask.current !== requestedTask)
        return null;
      if (next.taskId !== requestedTask) throw new Error('分析任务已变化，请重新打开报告');
      setView(next);
      setError('');
      if (decision.current?.version !== next.stateVersion) decision.current = null;
      return next;
    } catch (e) {
      if (
        !controller.signal.aborted &&
        mounted.current &&
        token === generation.current &&
        currentTask.current === requestedTask
      )
        fail(e);
      return null;
    } finally {
      if (
        mounted.current &&
        token === generation.current &&
        currentTask.current === requestedTask
      ) {
        setBusy(false);
        requestController.current = null;
      }
    }
  };
  useEffect(() => {
    mounted.current = true;
    denied.current = false;
    setView(null);
    setCitation(null);
    citationSequence.current++;
    setCitationTarget(null);
    setReadingCitation(false);
    setCitationError('');
    setNote('');
    decision.current = null;
    void refresh();
    return () => {
      mounted.current = false;
      generation.current++;
      citationSequence.current++;
      requestController.current?.abort();
    };
  }, [taskId]);
  useEffect(() => {
    if (!citationTarget || !citationPanel.current) return;
    citationPanel.current.scrollIntoView({ block: 'nearest' });
    if (!readingCitation) citationPanel.current.focus({ preventScroll: true });
  }, [citationTarget, citation, readingCitation, citationError]);
  const mutate = async (approve: boolean) => {
    if (!view || busy) return;
    setBusy(true);
    const token = generation.current;
    try {
      if (approve) {
        if (
          !decision.current ||
          decision.current.version !== view.stateVersion ||
          decision.current.note !== note.trim()
        )
          // 复核标识绑定状态版本及意见；只有同一决策可重试，状态冲突后必须刷新并重新确认。
          decision.current = {
            version: view.stateVersion,
            note: note.trim(),
            id: crypto.randomUUID(),
          };
        await approveAnalysis(
          taskId,
          view.stateVersion,
          decision.current.id,
          decision.current.note,
        );
      } else await recoverAnalysis(taskId, view.stateVersion);
      if (mounted.current && token === generation.current) {
        await refresh();
        if (!denied.current) onChanged?.();
      }
    } catch (e) {
      if (mounted.current && token === generation.current) {
        fail(e);
        if ((e as { status?: number }).status === 409) {
          decision.current = null;
          await refresh();
        }
      }
    } finally {
      if (mounted.current && token === generation.current) setBusy(false);
    }
  };
  // 引用跳转先重新读结果和授权，避免直接展开权限或材料状态已变化的旧缓存。
  const jump = async (id: string, group: string, opener: HTMLButtonElement) => {
    if (denied.current || busy) return;
    const requestedTask = taskId;
    const token = ++citationSequence.current;
    citationOpener.current = opener;
    setCitation(null);
    setCitationTarget({ id, group });
    setCitationError('');
    setReadingCitation(true);
    const next = await refresh(true);
    if (
      !mounted.current ||
      denied.current ||
      currentTask.current !== requestedTask ||
      token !== citationSequence.current
    )
      return;
    setReadingCitation(false);
    if (!next) {
      setCitationError('原文读取失败，请重新点击引用重试。');
      return;
    }
    const target = next?.report?.citations.find((c) => c.id === id);
    const source = next.sources.find((s) => s.parseTaskId === target?.parseTaskId);
    const segment = source?.segments.find(
      (s) => s.index === target?.index && s.kind === target.kind,
    );
    if (
      !target ||
      !source ||
      !segment ||
      source.fileVersionId !== target.fileVersionId ||
      source.sha256 !== target.sha256 ||
      target.startOffset < segment.startOffset ||
      target.endOffset > segment.endOffset ||
      target.endOffset <= target.startOffset ||
      source.text.slice(target.startOffset, target.endOffset) !== target.quote
    ) {
      const message = '该引用或来源已变化，无法定位原文，请刷新报告后重试。';
      setCitationError(message);
      setError(message);
      toast.error(message);
      return;
    }
    setCitation(target);
  };
  const closeCitation = () => {
    const token = ++citationSequence.current;
    if (readingCitation) {
      generation.current++;
      requestController.current?.abort();
      requestController.current = null;
      setBusy(false);
    }
    setCitation(null);
    setCitationTarget(null);
    setReadingCitation(false);
    setCitationError('');
    const opener = citationOpener.current;
    const requestedTask = taskId;
    requestAnimationFrame(() => {
      if (
        mounted.current &&
        currentTask.current === requestedTask &&
        token === citationSequence.current &&
        opener?.isConnected
      )
        opener.focus({ preventScroll: true });
    });
  };
  const source = view?.sources.find((s) => s.parseTaskId === citation?.parseTaskId),
    segment = source?.segments.find((s) => s.index === citation?.index);
  const refs = (ids: string[], group: string) => (
    <div className="analysis-citation-group">
      <div className="analysis-citation-links">
        {ids.map((id) => {
          const expanded = citationTarget?.group === group && citationTarget.id === id;
          return (
            <button
              key={id}
              className="btn btn-ghost btn-sm"
              type="button"
              disabled={busy}
              aria-expanded={expanded}
              aria-controls={expanded ? `analysis-citation-${taskId}-${group}` : undefined}
              onClick={(event) => void jump(id, group, event.currentTarget)}
            >
              [{id}] {expanded && readingCitation ? '正在读取原文…' : '查看原文位置'}
            </button>
          );
        })}
      </div>
      {citationTarget?.group === group && (
        <div
          ref={citationPanel}
          id={`analysis-citation-${taskId}-${group}`}
          role="region"
          aria-label="分析引用原文"
          aria-busy={readingCitation}
          tabIndex={-1}
          className="analysis-citation-preview"
        >
          <div className="analysis-citation-header">
            <h4>引用原文 · {citationTarget.id}</h4>
            <Button type="button" variant="ghost" size="sm" onClick={closeCitation}>
              收起原文
            </Button>
          </div>
          {readingCitation && <p role="status">正在读取原文并核对访问权限…</p>}
          {citationError && (
            <p role="alert" className="analysis-citation-error">
              {citationError}
            </p>
          )}
          {citation && source && segment && (
            <>
              <p className="analysis-citation-location">
                {source.fileName} · v{source.fileVersion} ·{' '}
                {citation.kind === 'PAGE' ? `第 ${citation.index} 页` : `第 ${citation.index} 段`}
              </p>
              <p className="analysis-citation-text">
                {source.text.slice(segment.startOffset, citation.startOffset)}
                <mark>{citation.quote}</mark>
                {source.text.slice(citation.endOffset, segment.endOffset)}
              </p>
              <details className="analysis-citation-metadata">
                <summary>文件版本校验信息</summary>
                <p>
                  固定文件版本 {citation.fileVersionId} · SHA-256 {citation.sha256}
                </p>
              </details>
            </>
          )}
        </div>
      )}
    </div>
  );
  const headingStyle = { margin: '18px 0 6px', fontWeight: 600 };
  return (
    <section aria-label="案件分析结果" style={{ lineHeight: 1.7 }}>
      <div
        style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}
      >
        <h3 style={{ fontWeight: 600 }}>案件分析结果</h3>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy || denied.current}
          onClick={() => void refresh()}
        >
          刷新分析结果
        </Button>
      </div>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {error}
        </p>
      )}
      {view && (
        <>
          <p>
            状态：
            {view.waitingReason === 'DELIVERY'
              ? '待投递处理'
              : caseAnalysisStatusLabel(view.status)}
          </p>
          <details>
            <summary>本次固定背景、分析要求及材料</summary>
            <p style={{ whiteSpace: 'pre-wrap' }}>
              背景：{view.background}\n要求：{view.requirements}
            </p>
            <ul>
              {view.sources.map((s) => (
                <li key={s.parseTaskId}>
                  {s.fileName} · v{s.fileVersion} · 解析任务 {s.parseTaskId}
                </li>
              ))}
            </ul>
          </details>
          {view.waitingReason === 'UNCERTAIN' && (
            <p role="alert">
              模型执行结果不确定。系统不会重复模型调用，请取消任务并核对执行记录后决定是否重新提交。
            </p>
          )}
          {view.waitingReason === 'DELIVERY' && (
            <p role="status">
              投递重试已结束，请在任务中心的「处理记录」中核查并确认恢复。本页暂停人工复核，已保存的分析及复核意见继续保留。
            </p>
          )}
          {view.report && (
            <>
              <h4 style={headingStyle}>材料支持的事实</h4>
              {view.report.facts.length === 0 && <p>材料尚不足以确认事实。</p>}
              {view.report.facts.map((f, n) => (
                <article key={n} style={{ padding: '4px 0' }}>
                  <p>{f.statement}</p>
                  {refs(f.citationIds, `fact-${n}`)}
                </article>
              ))}
              <h4 style={headingStyle}>待核实事项</h4>
              <ul>
                {view.report.pendingVerification.map((s, n) => (
                  <li key={n}>{s}</li>
                ))}
              </ul>
              <h4 style={headingStyle}>争议与办案建议</h4>
              {view.report.issues.map((i, n) => (
                <article
                  key={n}
                  style={{ padding: '12px 0', borderBottom: '1px solid var(--border)' }}
                >
                  <strong>
                    {i.title} ·{' '}
                    {i.level === 'HIGH' ? '高风险' : i.level === 'MEDIUM' ? '中风险' : '低风险'}
                  </strong>
                  <p>{i.analysis}</p>
                  <p>建议：{i.suggestion}</p>
                  {refs(i.citationIds, `issue-${n}`)}
                </article>
              ))}
              <h4 style={headingStyle}>缺少的材料</h4>
              <ul>
                {view.report.missingMaterials.map((s, n) => (
                  <li key={n}>{s}</li>
                ))}
              </ul>
              <h4 style={headingStyle}>建议边界</h4>
              <p style={{ whiteSpace: 'pre-wrap' }}>{view.report.recommendationBoundary}</p>
            </>
          )}
          {view.status === 'WAITING_HUMAN' && view.waitingReason === 'REVIEW' && (
            <div style={{ marginTop: 18 }}>
              <label className="form-label" htmlFor={`analysis-note-${taskId}`}>
                人工复核意见
              </label>
              <textarea
                id={`analysis-note-${taskId}`}
                className="form-input"
                rows={3}
                value={note}
                maxLength={500}
                disabled={busy}
                onChange={(e) => setNote(e.target.value)}
                placeholder="核对材料事实、证据缺口与建议适用条件后填写意见。"
              />
              <Button size="sm" disabled={busy || !note.trim()} onClick={() => void mutate(true)}>
                确认已复核并完成分析
              </Button>
            </div>
          )}
          {['QUEUED', 'RUNNING'].includes(view.status) && (
            <Button
              variant="secondary"
              size="sm"
              disabled={busy}
              onClick={() => void mutate(false)}
            >
              恢复同一执行
            </Button>
          )}
          {view.decisionNote && <p>人工复核意见：{view.decisionNote}</p>}
        </>
      )}
    </section>
  );
};
