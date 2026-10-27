import React, { useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  getTaskDeliveryRecovery,
  recoverTaskDelivery,
  TaskDeliveryRecoveryView,
  isDeliveryRecoveryType,
  hasRecoveryModelCosts,
} from '../../api/tasks';

const statusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  WAITING_HUMAN: '待人工确认',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCEL_REQUESTED: '取消中',
  CANCELLED: '已取消',
};
const deliveryLabels: Record<TaskDeliveryRecoveryView['deliveryStatus'], string> = {
  NONE: '未进入死信',
  DEAD: '等待人工处理',
  RESOLVED: '已处理',
};
const failureLabels = {
  TRANSIENT: '暂时性故障',
  PERMANENT: '需修正的故障',
  UNCERTAIN: '调用结果不确定',
};

type RecoveryRequest = { stateVersion: number; requestId: string; confirmModelCosts: boolean };
type RequestContext = { taskId: string; scope: number; sequence: number };
// canRecover 来自服务端执行记录检查，费用勾选不能覆盖 UNCERTAIN 或其他不可恢复状态。
const recoverable = (value: TaskDeliveryRecoveryView) =>
  value.canRecover &&
  value.deliveryStatus === 'DEAD' &&
  value.status === 'WAITING_HUMAN' &&
  value.failureKind !== 'UNCERTAIN' &&
  isDeliveryRecoveryType(value.type);

export const TaskDeliveryRecoveryPanel: React.FC<{ taskId: string; onAccessLost?: () => void }> = ({
  taskId,
  onAccessLost,
}) => {
  const toast = useToast();
  const [view, setView] = useState<TaskDeliveryRecoveryView | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<'refresh' | 'recover' | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const alive = useRef(true);
  const scope = useRef(0);
  const sequence = useRef(0);
  const currentTask = useRef(taskId);
  const denied = useRef(false);
  const acting = useRef(false);
  const accessLost = useRef(onAccessLost);
  const recoveryRequest = useRef<RecoveryRequest | null>(null);
  accessLost.current = onAccessLost;
  currentTask.current = taskId;
  const busy = action !== null;

  const sameTask = (context: RequestContext) =>
    alive.current && context.scope === scope.current && context.taskId === currentTask.current;
  const current = (context: RequestContext) =>
    sameTask(context) && context.sequence === sequence.current;
  const context = (): RequestContext => ({
    taskId,
    scope: scope.current,
    sequence: ++sequence.current,
  });
  // 只接收当前任务的最新请求，切换任务和撤权都会推进 scope，使在途响应失效。
  const accept = (value: TaskDeliveryRecoveryView, request: RequestContext) => {
    if (!current(request)) return;
    if (value.taskId !== request.taskId) throw new Error('任务处理记录不匹配，请重新读取');
    if (recoveryRequest.current?.stateVersion !== value.stateVersion)
      recoveryRequest.current = null;
    setView(value);
    setError('');
  };
  const fail = (err: unknown, request: RequestContext) => {
    if (!sameTask(request)) return;
    const failure = err as { status?: number; message?: string };
    if (failure.status === 401 || failure.status === 403) {
      denied.current = true;
      scope.current++;
      sequence.current++;
      recoveryRequest.current = null;
      setView(null);
      setConfirmed(false);
      setError(failure.message || '任务权限已失效');
      acting.current = false;
      setAction(null);
      setLoading(false);
      accessLost.current?.();
      return;
    }
    if (!current(request)) return;
    setView(null);
    setConfirmed(false);
    setError(
      failure.status === 409
        ? '任务状态已变化，请刷新处理记录后重新确认。'
        : failure.message || '无法读取任务处理记录',
    );
    if (failure.status === 409) recoveryRequest.current = null;
  };

  const refresh = async () => {
    if (acting.current || denied.current) return;
    acting.current = true;
    const request = context();
    setAction('refresh');
    try {
      accept(await getTaskDeliveryRecovery(request.taskId), request);
    } catch (err) {
      fail(err, request);
    } finally {
      if (current(request)) {
        acting.current = false;
        setAction(null);
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    alive.current = true;
    scope.current++;
    denied.current = false;
    acting.current = false;
    recoveryRequest.current = null;
    setView(null);
    setError('');
    setConfirmed(false);
    setAction(null);
    setLoading(true);
    void refresh();
    return () => {
      alive.current = false;
      scope.current++;
      sequence.current++;
    };
  }, [taskId]);

  const recover = async () => {
    if (!view || !recoverable(view) || acting.current || denied.current) return;
    const paid = hasRecoveryModelCosts(view.type);
    if (paid && !confirmed) return;
    acting.current = true;
    const request = context();
    setAction('recover');
    setError('');
    // 网络失败可能发生在服务端提交之后；同一状态版本重用 requestId，避免人工重试创建第二次恢复。
    const payload =
      recoveryRequest.current?.stateVersion === view.stateVersion
        ? recoveryRequest.current
        : {
            stateVersion: view.stateVersion,
            requestId: crypto.randomUUID(),
            confirmModelCosts: paid && confirmed,
          };
    recoveryRequest.current = payload;
    try {
      const accepted = await recoverTaskDelivery(request.taskId, payload);
      if (!current(request)) return;
      accept(accepted, request);
      recoveryRequest.current = null;
      setConfirmed(false);
      toast.success('恢复请求已提交');
      accept(await getTaskDeliveryRecovery(request.taskId), request);
    } catch (err) {
      fail(err, request);
    } finally {
      if (current(request)) {
        acting.current = false;
        setAction(null);
        setLoading(false);
      }
    }
  };

  if (loading) return <Spinner text="读取任务处理记录…" />;
  const uncertain = view?.failureKind === 'UNCERTAIN';
  const paidTask = !!view && hasRecoveryModelCosts(view.type);
  const canRecover = !!view && recoverable(view);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 13 }}>
          {error}
        </p>
      )}
      {view && (
        <>
          <p style={{ fontWeight: 600 }}>
            任务 #{view.taskId} · {statusLabels[view.status] ?? view.status}
          </p>
          <dl
            style={{
              display: 'grid',
              gridTemplateColumns: 'auto 1fr',
              gap: '10px 20px',
              fontSize: 13,
            }}
          >
            <dt>投递状态</dt>
            <dd style={{ margin: 0 }}>{deliveryLabels[view.deliveryStatus]}</dd>
            <dt>人工恢复次数</dt>
            <dd style={{ margin: 0 }}>{view.attempt} 次</dd>
            <dt>延迟重投次数</dt>
            <dd style={{ margin: 0 }}>{view.retryCount} / 3</dd>
            {view.failureKind && (
              <>
                <dt>故障类型</dt>
                <dd style={{ margin: 0 }}>{failureLabels[view.failureKind]}</dd>
              </>
            )}
            {view.deadLetteredAt && (
              <>
                <dt>进入死信时间</dt>
                <dd style={{ margin: 0 }}>{view.deadLetteredAt.replace('T', ' ')}</dd>
              </>
            )}
          </dl>
          {view.recoveryMessage && (
            <p role={canRecover ? undefined : 'status'} style={{ fontSize: 13, lineHeight: 1.7 }}>
              {view.recoveryMessage}
            </p>
          )}
          {uncertain && (
            <p style={{ fontSize: 13, color: 'var(--danger-text)' }}>
              调用结果不确定，已禁止恢复。请按处理提示核查，避免重复调用模型。
            </p>
          )}
          {canRecover && paidTask && (
            <>
              <p style={{ fontSize: 12, lineHeight: 1.7, color: 'var(--text-muted)' }}>
                已完成步骤复用保存的结果，尚未执行步骤可能调用模型并产生费用。勾选费用确认不会允许重做结果不确定或已失败的步骤。
              </p>
              <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 13 }}>
                <input
                  type="checkbox"
                  checked={confirmed}
                  disabled={busy}
                  onChange={(event) => setConfirmed(event.target.checked)}
                />
                <span>我已检查故障原因，并确认继续处理可能产生模型费用。</span>
              </label>
            </>
          )}
        </>
      )}
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <Button
          variant="secondary"
          disabled={busy || denied.current}
          loading={action === 'refresh'}
          onClick={() => void refresh()}
        >
          刷新处理记录
        </Button>
        {view && (
          <Button
            disabled={!canRecover || busy || (paidTask && !confirmed)}
            loading={action === 'recover'}
            onClick={() => void recover()}
          >
            恢复处理
          </Button>
        )}
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
        本页不会自动刷新。提交恢复后，可手动刷新查看处理进度。
      </p>
    </div>
  );
};
