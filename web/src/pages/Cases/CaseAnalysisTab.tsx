import React, { useEffect, useRef, useState } from 'react';
import { CaseView } from '../../types/case';
import { Button } from '../../components/common/Button';
import { useToast } from '../../components/common/Toast';
import { analysisSources, submitAnalysis, AnalysisSource } from '../../api/case-analysis';
import { listTasks } from '../../api/tasks';
import { TaskView } from '../../types/document';
import { CaseAnalysisResultPanel, caseAnalysisStatusLabel } from './CaseAnalysisResultPanel';

export const CaseAnalysisTab: React.FC<{ caseInfo: CaseView }> = ({ caseInfo }) => {
  const toast = useToast(),
    [sources, setSources] = useState<AnalysisSource[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [tasks, setTasks] = useState<TaskView[]>([]);
  const [background, setBackground] = useState(caseInfo.description ?? ''),
    [requirements, setRequirements] = useState(
      '梳理材料支持的事实、待核实事项、主要争议、证据缺口和办案建议。',
    );
  const [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [openTask, setOpenTask] = useState<string | null>(null);
  // intent 绑定一次固定输入；generation 只负责丢弃案件切换、刷新或撤权前的响应。
  const intent = useRef<{ input: string; id: string } | null>(null),
    generation = useRef(0),
    mounted = useRef(true),
    denied = useRef(false);
  // 撤权同时清除材料、用户输入和付费确认，不能由较晚返回的请求重新显示。
  const lose = () => {
    denied.current = true;
    generation.current++;
    setSources([]);
    setSelected([]);
    setTasks([]);
    setOpenTask(null);
    setBackground('');
    setRequirements('');
    setConfirmed(false);
    intent.current = null;
  };
  const fail = (e: unknown) => {
    const f = e as { status?: number; message?: string };
    if (f.status === 401 || f.status === 403) lose();
    setError(f.message ?? '案件分析操作失败');
    toast.error(f.message ?? '案件分析操作失败');
  };
  const refresh = async () => {
    if (denied.current) return;
    const token = ++generation.current;
    setBusy(true);
    try {
      const [materials, list] = await Promise.all([
        analysisSources(caseInfo.id),
        listTasks({ caseId: caseInfo.id, type: 'CASE_ANALYSIS', size: 100 }),
      ]);
      if (!mounted.current || token !== generation.current) return;
      setSources(materials);
      setTasks(list.items);
      setError('');
      setSelected((old) => old.filter((id) => materials.some((s) => s.parseTaskId === id)));
    } catch (e) {
      if (mounted.current && token === generation.current) fail(e);
    } finally {
      if (mounted.current && token === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    mounted.current = true;
    denied.current = false;
    setSources([]);
    setTasks([]);
    setSelected([]);
    setOpenTask(null);
    setBackground(caseInfo.description ?? '');
    intent.current = null;
    void refresh();
    return () => {
      mounted.current = false;
      generation.current++;
    };
  }, [caseInfo.id]);
  const submit = async () => {
    // 对所选解析任务排序后固定本次输入；输入不变时重用幂等键，避免响应丢失后重复付费提交。
    const fixed = {
      caseId: caseInfo.id,
      parseTaskIds: [...selected].sort(),
      background: background.trim(),
      requirements: requirements.trim(),
      confirmModelCosts: confirmed,
    };
    const input = JSON.stringify(fixed);
    if (intent.current?.input !== input) intent.current = { input, id: crypto.randomUUID() };
    const token = generation.current;
    setBusy(true);
    try {
      const task = await submitAnalysis({ ...fixed, idempotencyKey: intent.current.id });
      if (!mounted.current || token !== generation.current) return;
      setOpenTask(task.taskId);
      setConfirmed(false);
      intent.current = null;
      await refresh();
      toast.success('案件分析已提交，请稍后刷新结果');
    } catch (e) {
      if (mounted.current && token === generation.current) fail(e);
    } finally {
      if (mounted.current && token === generation.current) setBusy(false);
    }
  };
  return (
    <section aria-label="案件分析">
      <p className="form-hint">
        选定本案件的已解析原件，分析材料事实、争议和证据缺口。结果按页或段引用固定版本。高风险、资料不足或待核实事项需人工复核。模型调用可能产生费用。
      </p>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {error}
        </p>
      )}
      <Button
        variant="ghost"
        size="sm"
        disabled={busy || denied.current}
        onClick={() => void refresh()}
      >
        刷新分析材料与任务
      </Button>
      {caseInfo.status !== 'ARCHIVED' && !denied.current && (
        <>
          <h4>选择分析材料（1–10份，总文字最多4万字）</h4>
          {!sources.length && <p>暂无带页段定位的成功解析材料，请先在文件页面提交解析。</p>}
          {sources.map((s) => (
            <label key={s.parseTaskId} style={{ display: 'block', margin: '8px 0' }}>
              <input
                type="checkbox"
                checked={selected.includes(s.parseTaskId)}
                disabled={busy || (!selected.includes(s.parseTaskId) && selected.length >= 10)}
                onChange={(e) =>
                  setSelected((old) =>
                    e.target.checked
                      ? [...old, s.parseTaskId]
                      : old.filter((id) => id !== s.parseTaskId),
                  )
                }
              />{' '}
              {s.fileName} · v{s.fileVersion} · 解析任务 {s.parseTaskId} · {s.text.length}字
            </label>
          ))}
          <label className="form-label" htmlFor={`analysis-background-${caseInfo.id}`}>
            本次固定案件背景
          </label>
          <textarea
            id={`analysis-background-${caseInfo.id}`}
            className="form-input"
            rows={3}
            maxLength={3000}
            value={background}
            disabled={busy}
            onChange={(e) => setBackground(e.target.value)}
          />
          <label className="form-label" htmlFor={`analysis-requirements-${caseInfo.id}`}>
            分析要求
          </label>
          <textarea
            id={`analysis-requirements-${caseInfo.id}`}
            className="form-input"
            rows={3}
            maxLength={2000}
            value={requirements}
            disabled={busy}
            onChange={(e) => setRequirements(e.target.value)}
          />
          <label style={{ display: 'block', margin: '12px 0' }}>
            <input
              type="checkbox"
              checked={confirmed}
              disabled={busy}
              onChange={(e) => setConfirmed(e.target.checked)}
            />{' '}
            我确认本次案件分析可能产生模型费用
          </label>
          <Button
            size="sm"
            disabled={
              busy || !confirmed || !selected.length || !background.trim() || !requirements.trim()
            }
            onClick={() => void submit()}
          >
            提交案件分析
          </Button>
        </>
      )}
      <h4 style={{ marginTop: 20 }}>分析记录</h4>
      {tasks.map((t) => (
        <div key={t.id} style={{ display: 'flex', gap: 14, alignItems: 'center', margin: '8px 0' }}>
          <span>
            任务 {t.id} · {caseAnalysisStatusLabel(t.status)}
          </span>
          <Button size="sm" variant="ghost" onClick={() => setOpenTask(t.id)}>
            查看分析结果
          </Button>
        </div>
      ))}
      {openTask && (
        <CaseAnalysisResultPanel
          key={openTask}
          taskId={openTask}
          onChanged={() => void refresh()}
          onAccessLost={lose}
        />
      )}
    </section>
  );
};
