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
      setNote('');
      decision.current = null;
      onAccessLost?.();
    }
    setError(e.message ?? '读取案件分析失败');
    toast.error(e.message ?? '读取案件分析失败');
  };
  const refresh = async () => {
    if (denied.current) return null;
    const token = ++generation.current;
    setBusy(true);
    try {
      const next = await getAnalysis(taskId);
      if (!mounted.current || token !== generation.current) return null;
      setView(next);
      setError('');
      if (decision.current?.version !== next.stateVersion) decision.current = null;
      return next;
    } catch (e) {
      if (mounted.current && token === generation.current) fail(e);
      return null;
    } finally {
      if (mounted.current && token === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    mounted.current = true;
    denied.current = false;
    setView(null);
    setCitation(null);
    setNote('');
    decision.current = null;
    void refresh();
    return () => {
      mounted.current = false;
      generation.current++;
    };
  }, [taskId]);
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
  const jump = async (id: string) => {
    const next = await refresh();
    const target = next?.report?.citations.find((c) => c.id === id);
    setCitation(target ?? null);
  };
  const refs = (ids: string[]) =>
    ids.map((id) => (
      <button
        key={id}
        className="btn btn-ghost btn-sm"
        type="button"
        disabled={busy}
        onClick={() => void jump(id)}
      >
        [{id}] 查看原文位置
      </button>
    ));
  const source = view?.sources.find((s) => s.parseTaskId === citation?.parseTaskId),
    segment = source?.segments.find((s) => s.index === citation?.index);
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
                  {refs(f.citationIds)}
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
                  {refs(i.citationIds)}
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
          {citation && source && segment && (
            <div
              role="region"
              aria-label="分析引用原文"
              style={{ padding: 16, border: '1px solid var(--border)', marginTop: 16 }}
            >
              <h4>
                [{citation.id}] {source.fileName} · v{source.fileVersion} ·{' '}
                {citation.kind === 'PAGE' ? `第 ${citation.index} 页` : `第 ${citation.index} 段`}
              </h4>
              <p style={{ whiteSpace: 'pre-wrap' }}>
                {source.text.slice(segment.startOffset, citation.startOffset)}
                <mark>{citation.quote}</mark>
                {source.text.slice(citation.endOffset, segment.endOffset)}
              </p>
              <small>
                固定文件版本 {citation.fileVersionId} · SHA-256 {citation.sha256}
              </small>
            </div>
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
