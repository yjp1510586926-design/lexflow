import React, { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Spinner } from '../../components/common/Spinner';
import { GenerationView, getGeneration, recoverGeneration } from '../../api/document-generations';
import { cancelTask, getTaskDeliveryRecovery } from '../../api/tasks';

const stopped = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'WAITING_HUMAN']);
const labels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '草稿已保存',
  FAILED: '生成失败',
  CANCELLED: '已取消',
  WAITING_HUMAN: '调用状态需人工处理',
};
export const DocumentGenerationPanel: React.FC<{ taskId: string; onAccessLost?: () => void }> = ({
  taskId,
  onAccessLost,
}) => {
  const [job, setJob] = useState<GenerationView | null>(null),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false);
  const [automatic, setAutomatic] = useState(true);
  const [deliveryWaiting, setDeliveryWaiting] = useState(false);
  const alive = useRef(true),
    stop = useRef(false),
    accessLost = useRef(onAccessLost),
    acting = useRef(false),
    reads = useRef(0),
    generation = useRef(0);
  const scope = useRef(0),
    currentTask = useRef(taskId),
    denied = useRef(false);
  // scope 隔离任务生命周期，generation 只接收最新查询；旧请求仍会完成，但不能再写入界面。
  accessLost.current = onAccessLost;
  currentTask.current = taskId;
  const sameTask = (ticket: { scope: number; taskId: string }) =>
    alive.current && ticket.scope === scope.current && ticket.taskId === currentTask.current;
  const fail = (e: any, ticket: { scope: number; taskId: string }, clear = false) => {
    if (!sameTask(ticket)) return;
    if (e.status === 401 || e.status === 403) {
      // 撤权后清除材料并使所有在途查询失效，不能等下一次轮询再隐藏已有内容。
      denied.current = true;
      scope.current++;
      generation.current++;
      stop.current = true;
      acting.current = false;
      setJob(null);
      setDeliveryWaiting(false);
      setBusy(false);
      setLoading(false);
      setAutomatic(false);
      setError(e.message || '生成任务权限已失效');
      accessLost.current?.();
      return;
    }
    if (clear) {
      setJob(null);
      setDeliveryWaiting(false);
      stop.current = true;
    }
    setError(e.message || '无法读取生成任务');
  };
  const load = async () => {
    if (denied.current) return;
    const round = ++generation.current,
      ticket = { scope: scope.current, taskId };
    try {
      const value = await getGeneration(ticket.taskId);
      if (!sameTask(ticket) || round !== generation.current) return;
      if (value.taskId !== ticket.taskId) throw new Error('生成任务不匹配，请重新读取');
      const delivery =
        value.status === 'WAITING_HUMAN' ? await getTaskDeliveryRecovery(ticket.taskId) : null;
      if (!sameTask(ticket) || round !== generation.current) return;
      if (delivery && delivery.taskId !== ticket.taskId)
        throw new Error('任务处理记录不匹配，请重新读取');
      setJob(value);
      setError('');
      stop.current = stopped.has(value.status);
      // 投递记录与生成记录需处于同一状态版本，避免用过期的死信提示解释最新任务。
      setDeliveryWaiting(
        delivery?.deliveryStatus === 'DEAD' &&
          delivery.stateVersion === value.stateVersion &&
          delivery.status === value.status,
      );
    } catch (e: any) {
      if (sameTask(ticket) && ([401, 403].includes(e.status) || round === generation.current))
        fail(e, ticket, true);
    } finally {
      if (sameTask(ticket) && round === generation.current) setLoading(false);
    }
  };
  useEffect(() => {
    alive.current = true;
    scope.current++;
    stop.current = false;
    denied.current = false;
    acting.current = false;
    reads.current = 0;
    setJob(null);
    setDeliveryWaiting(false);
    setError('');
    setLoading(true);
    setBusy(false);
    setAutomatic(true);
    let fetching = false;
    // 自动查询不重叠且最多 60 次；等待人工处理时停止查询，由用户确认后显式继续。
    const tick = async () => {
      if (fetching || stop.current) return;
      if (++reads.current > 60) {
        stop.current = true;
        setAutomatic(false);
        return;
      }
      fetching = true;
      try {
        await load();
      } finally {
        fetching = false;
      }
    };
    void tick();
    const timer = window.setInterval(tick, 3000);
    return () => {
      alive.current = false;
      scope.current++;
      generation.current++;
      window.clearInterval(timer);
    };
  }, [taskId]);
  const act = async (kind: 'cancel' | 'recover') => {
    // 取消以服务端任务状态为准；恢复带上看到的 stateVersion，防止继续已变化的执行状态。
    if (!job || acting.current || denied.current) return;
    acting.current = true;
    setBusy(true);
    const ticket = { scope: scope.current, taskId };
    try {
      if (kind === 'cancel') await cancelTask(ticket.taskId);
      else await recoverGeneration(ticket.taskId, job.stateVersion);
      if (sameTask(ticket)) await load();
    } catch (e: any) {
      if (sameTask(ticket)) fail(e, ticket);
    } finally {
      if (sameTask(ticket)) {
        acting.current = false;
        setBusy(false);
      }
    }
  };
  if (loading) return <Spinner text="读取生成任务…" />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {error}
        </p>
      )}
      {job && (
        <>
          <p>
            任务 #{job.taskId} ·{' '}
            {deliveryWaiting ? '待投递处理' : (labels[job.status] ?? job.status)}
          </p>
          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
            生成结果保存为独立待复核版本。请核对事实、待补信息与法律依据，再在案件文书的版本历史中复核。
          </p>
          {deliveryWaiting && (
            <p role="status">
              投递重试已结束，请在任务中心的「处理记录」中核查并确认恢复。已生成的待复核版本继续保留，本页不能直接继续投递。
            </p>
          )}
          {job.status === 'WAITING_HUMAN' && !deliveryWaiting && (
            <p role="status">
              当前执行需人工处理，请查看任务中心的「处理记录」。系统不会自动重复调用付费步骤。
            </p>
          )}
          {job.errorMessage && <p role="alert">{job.errorMessage}</p>}
          {job.result && (
            <>
              <p>
                结果 v{job.result.versionNo} ·{' '}
                {job.result.reviewStatus === 'APPROVED'
                  ? '已复核通过'
                  : job.result.reviewStatus === 'REJECTED'
                    ? '已驳回'
                    : '待复核'}
              </p>
              {!!job.missingInformation.length && (
                <ul>
                  {job.missingInformation.map((x, i) => (
                    <li key={i}>{x}</li>
                  ))}
                </ul>
              )}
              <pre
                style={{
                  whiteSpace: 'pre-wrap',
                  overflowWrap: 'anywhere',
                  maxHeight: 400,
                  overflow: 'auto',
                  padding: 14,
                  background: 'var(--bg-subtle)',
                }}
              >
                {job.result.contentText}
              </pre>
            </>
          )}
          <div style={{ display: 'flex', gap: 10 }}>
            {!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status) && (
              <Button variant="danger" loading={busy} onClick={() => void act('cancel')}>
                取消生成
              </Button>
            )}
            {['QUEUED', 'RUNNING'].includes(job.status) && (
              <Button variant="secondary" loading={busy} onClick={() => void act('recover')}>
                继续处理
              </Button>
            )}
          </div>
        </>
      )}
      {!automatic && <p style={{ fontSize: 12 }}>自动查询已结束，可手动刷新查看结果。</p>}
      <div>
        <Button variant="secondary" disabled={busy || denied.current} onClick={() => void load()}>
          刷新生成结果
        </Button>
      </div>
    </div>
  );
};
