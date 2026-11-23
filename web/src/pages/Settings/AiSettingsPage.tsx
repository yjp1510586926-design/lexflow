import React, { useEffect, useRef, useState } from 'react';
import { request } from '../../api/client';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { ActivityIcon, CheckIcon, EditIcon, LockIcon } from '../../components/common/Icons';
import './AiSettingsPage.css';

type Purpose = 'LLM' | 'EMBEDDING' | 'RERANK';
type TestStatus = 'PREPARED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'STALE';
interface Config {
  purpose: Purpose;
  provider: string;
  baseUrl: string;
  model: string;
  revision: string;
}
interface State {
  storageReady: boolean;
  items: Config[];
}
interface TestView {
  id: string | number;
  purpose: Purpose;
  revision: string;
  status: TestStatus;
  message: string;
  elapsedMs: number | null;
  dimension?: number | null;
  createdAt: string;
}
const labels: Record<Purpose, string> = {
  LLM: '资料问答与合同审查模型',
  EMBEDDING: '资料向量模型',
  RERANK: '检索排序模型',
};
const testLabels: Record<TestStatus, string> = {
  PREPARED: '测试已提交，等待执行。',
  PROCESSING: '正在测试连接。',
  SUCCEEDED: '连接测试成功。',
  FAILED: '连接测试失败，请检查已保存的接口、模型和 Key。',
  UNKNOWN: '测试结果未知。请求可能已到达服务商；重新测试可能再次产生费用。',
  STALE: '配置已变更，这次测试结果已失效。请重新读取配置后测试。',
};
const testBadges: Record<
  TestStatus,
  { label: string; variant: 'blue' | 'emerald' | 'amber' | 'rose' }
> = {
  PREPARED: { label: '等待测试', variant: 'blue' },
  PROCESSING: { label: '测试中', variant: 'blue' },
  SUCCEEDED: { label: '连接正常', variant: 'emerald' },
  FAILED: { label: '连接失败', variant: 'rose' },
  UNKNOWN: { label: '结果待确认', variant: 'amber' },
  STALE: { label: '记录已失效', variant: 'amber' },
};
const activeTest = (test: TestView | null) =>
  test?.status === 'PREPARED' || test?.status === 'PROCESSING';
const forbidden = (error: unknown) =>
  (error as { status?: number } | null)?.status === 403 ||
  (error as { status?: number } | null)?.status === 401;

export const AiSettingsPage: React.FC = () => {
  const [data, setData] = useState<State | null>(null);
  const [purpose, setPurpose] = useState<Purpose>('LLM');
  const [provider, setProvider] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [key, setKey] = useState('');
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [consent, setConsent] = useState(false);
  const [test, setTest] = useState<TestView | null>(null);
  const [testing, setTesting] = useState(false);
  const [testUncertain, setTestUncertain] = useState(false);
  const [testNotice, setTestNotice] = useState('');
  const [readingTest, setReadingTest] = useState(false);
  const item = data?.items.find((item) => item.purpose === purpose);
  const configured = Boolean(item);
  const savedScope = `${purpose}:${item?.revision ?? ''}`;
  const scopeRef = useRef(savedScope);
  scopeRef.current = savedScope;
  const mountedRef = useRef(true);
  const readSequence = useRef(0);
  const summaryRef = useRef<HTMLElement>(null);
  const testBadge = testing
    ? { label: '正在测试', variant: 'blue' as const }
    : readingTest && !test
      ? { label: '正在读取', variant: 'blue' as const }
      : test
        ? testBadges[test.status]
        : testUncertain
          ? testBadges.UNKNOWN
          : { label: '尚未验证', variant: 'slate' as const };
  const unchanged =
    Boolean(item?.revision) &&
    provider === item?.provider &&
    baseUrl === item?.baseUrl &&
    model === item?.model &&
    key === '';

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      readSequence.current += 1;
    };
  }, []);

  const denyAccess = () => {
    setData(null);
    setTest(null);
    setTestUncertain(false);
    setKey('');
    setEditing(false);
    setConsent(false);
    setTestNotice('');
    setError('当前账号无法管理 AI 配置，请重新登录或联系律所管理员。');
  };
  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const next = await request<State>('/api/v1/settings/ai');
      if (mountedRef.current) {
        setData(next);
        setMessage('');
      }
      return true;
    } catch (e) {
      if (mountedRef.current) {
        if (forbidden(e)) denyAccess();
        else {
          setData(null);
          setError(e instanceof Error ? e.message : '配置读取失败');
        }
      }
      return false;
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    const selected = data?.items.find((config) => config.purpose === purpose);
    setProvider(selected?.provider ?? '');
    setBaseUrl(selected?.baseUrl ?? '');
    setModel(selected?.model ?? '');
    setKey('');
    setEditing(false);
    setConfirmDelete(false);
  }, [data, purpose]);
  useEffect(() => {
    if (message && configured && !editing) {
      summaryRef.current?.focus({ preventScroll: true });
      summaryRef.current?.scrollIntoView({ block: 'nearest' });
    }
  }, [message, configured, editing]);
  useEffect(() => {
    setConsent(false);
  }, [purpose, provider, baseUrl, model, key, item?.revision]);

  const readLatest = async (
    scope: string,
    selectedPurpose: Purpose,
    revision: string,
    signal?: AbortSignal,
    uncertain = testUncertain,
  ) => {
    const sequence = ++readSequence.current;
    setReadingTest(true);
    try {
      const result = await request<TestView | null>(
        `/api/v1/settings/ai/${selectedPurpose}/tests/latest`,
        { params: { revision }, signal },
      );
      if (mountedRef.current && scopeRef.current === scope && readSequence.current === sequence) {
        setTest(result);
        if (result) setTestUncertain(false);
        setTestNotice(
          result
            ? ''
            : uncertain
              ? '尚未读取到测试记录。如需重新测试，请再次确认可能产生费用。'
              : '当前配置尚未测试。',
        );
      }
    } catch (e) {
      if (
        mountedRef.current &&
        scopeRef.current === scope &&
        readSequence.current === sequence &&
        !signal?.aborted
      ) {
        if (forbidden(e)) denyAccess();
        else setTestNotice('结果读取失败。可稍后重新读取，这不会调用模型。');
      }
    } finally {
      if (mountedRef.current && scopeRef.current === scope && readSequence.current === sequence)
        setReadingTest(false);
    }
  };

  useEffect(() => {
    setTest(null);
    setTestUncertain(false);
    setTestNotice('');
    setConsent(false);
    setReadingTest(false);
    readSequence.current += 1;
    if (!item?.revision) return;
    const controller = new AbortController();
    void readLatest(savedScope, purpose, item.revision, controller.signal, false);
    return () => {
      controller.abort();
      readSequence.current += 1;
    };
  }, [savedScope]);

  useEffect(() => {
    if (!test || !activeTest(test) || !item?.revision) return;
    const scope = savedScope;
    const id = test.id;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let reads = 0;
    const poll = async () => {
      try {
        const result = await request<TestView>(`/api/v1/settings/ai/tests/${id}`, {
          signal: controller.signal,
        });
        if (controller.signal.aborted || !mountedRef.current || scopeRef.current !== scope) return;
        setTest((previous) =>
          previous?.id === result.id && !activeTest(previous) && activeTest(result)
            ? previous
            : result,
        );
        if (!activeTest(result)) {
          setTestNotice('');
          return;
        }
        reads += 1;
        if (reads >= 20) {
          setTestNotice('测试仍在处理中。自动读取已停止，可稍后手动读取结果。');
          return;
        }
        timer = setTimeout(() => void poll(), 3000);
      } catch (e) {
        if (!controller.signal.aborted && mountedRef.current && scopeRef.current === scope) {
          if (forbidden(e)) denyAccess();
          else setTestNotice('结果读取失败。可稍后重新读取，这不会调用模型。');
        }
      }
    };
    timer = setTimeout(() => void poll(), 3000);
    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [test?.id, test?.status, savedScope]);

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    setMessage('');
    setConsent(false);
    try {
      await request(`/api/v1/settings/ai/${purpose}`, {
        method: 'PUT',
        body: JSON.stringify({ provider, baseUrl, model, apiKey: key }),
      });
      if (mountedRef.current) {
        setKey('');
        setTest(null);
        setTestNotice('');
        const refreshed = await load();
        if (mountedRef.current) {
          setEditing(false);
          setMessage(
            refreshed
              ? '配置已保存。可在下方测试连接，确认模型是否可用。'
              : '配置已保存，但最新状态读取失败，请重新读取配置，无需重复保存。',
          );
        }
      }
    } catch (e) {
      if (mountedRef.current) {
        setKey('');
        if (forbidden(e)) denyAccess();
        else setError(e instanceof Error ? e.message : '保存失败，请重新输入 Key 后重试');
      }
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(false);
    }
  };
  const remove = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    setMessage('');
    setConsent(false);
    try {
      await request(`/api/v1/settings/ai/${purpose}`, { method: 'DELETE' });
      if (mountedRef.current) {
        setKey('');
        setTest(null);
        setTestNotice('');
        const refreshed = await load();
        if (mountedRef.current) {
          setMessage(
            refreshed
              ? '配置已删除。可重新添加此用途的模型配置。'
              : '配置已删除，但最新状态读取失败，请重新读取配置。',
          );
        }
      }
    } catch (e) {
      if (mountedRef.current) {
        if (forbidden(e)) denyAccess();
        else setError(e instanceof Error ? e.message : '删除失败');
      }
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(false);
    }
  };
  const runTest = async () => {
    if (
      editing ||
      busyRef.current ||
      readingTest ||
      !item?.revision ||
      !unchanged ||
      !consent ||
      activeTest(test)
    )
      return;
    const scope = savedScope;
    const revision = item.revision;
    const requestId = crypto.randomUUID();
    busyRef.current = true;
    readSequence.current += 1;
    setBusy(true);
    setTesting(true);
    setError('');
    setMessage('');
    setTest(null);
    setTestUncertain(false);
    setConsent(false);
    setTestNotice('正在测试连接。本次会发送少量合成文本。');
    try {
      const result = await request<TestView>(`/api/v1/settings/ai/${purpose}/tests`, {
        method: 'POST',
        retryCsrf: false,
        body: JSON.stringify({ revision, requestId }),
      });
      if (mountedRef.current && scopeRef.current === scope) {
        setTest(result);
        setTestNotice('');
      }
    } catch (e) {
      if (mountedRef.current && scopeRef.current === scope) {
        if (forbidden(e)) denyAccess();
        else {
          setTestUncertain(true);
          setTestNotice(
            '本次测试结果未知，请先读取已提交的测试结果。重新勾选后发起新测试可能再次产生费用。',
          );
        }
      }
    } finally {
      busyRef.current = false;
      if (mountedRef.current) {
        setBusy(false);
        setTesting(false);
      }
    }
  };

  const cancelEdit = () => {
    setProvider(item?.provider ?? '');
    setBaseUrl(item?.baseUrl ?? '');
    setModel(item?.model ?? '');
    setKey('');
    setConsent(false);
    setError('');
    setMessage('');
    setEditing(false);
  };

  return (
    <Card
      title="AI 配置"
      subtitle="仅律所管理员可修改。请使用自己的模型服务账号。"
      className="ai-settings-card"
    >
      {error && (
        <p role="alert" className="ai-settings-notice ai-settings-notice-error">
          {error}
        </p>
      )}
      {message && (
        <p role="status" className="ai-settings-notice ai-settings-notice-success">
          {message}
        </p>
      )}
      {!data ? (
        <Button loading={loading} onClick={() => void load()}>
          {loading ? '正在读取配置…' : '重新读取配置'}
        </Button>
      ) : (
        <div className="ai-settings">
          {!data.storageReady && (
            <p role="status" className="ai-settings-notice ai-settings-notice-warning">
              服务端尚未启用凭据加密，请联系部署者配置后再保存。案件和文件功能不受影响。
            </p>
          )}
          <div className="ai-settings-purpose">
            <label className="form-label" htmlFor="ai-purpose">
              配置用途
            </label>
            <select
              id="ai-purpose"
              className="form-select"
              value={purpose}
              disabled={busy}
              onChange={(e) => {
                setPurpose(e.target.value as Purpose);
                setEditing(false);
                setKey('');
                setTest(null);
                setConsent(false);
                setMessage('');
                setError('');
              }}
            >
              {(Object.keys(labels) as Purpose[]).map((p) => (
                <option key={p} value={p}>
                  {labels[p]}
                </option>
              ))}
            </select>
          </div>
          {configured && !editing && item ? (
            <section
              ref={summaryRef}
              tabIndex={-1}
              aria-label="已保存的模型配置"
              className="ai-settings-summary"
            >
              <div className="ai-settings-summary-header">
                <div className="ai-settings-heading">
                  <span className="ai-settings-saved-icon">
                    <CheckIcon size={20} />
                  </span>
                  <div>
                    <h4>配置已保存</h4>
                    <p>当前用途使用以下配置，API Key 已加密保存。</p>
                  </div>
                </div>
                <Button
                  type="button"
                  variant="secondary"
                  icon={<EditIcon size={15} />}
                  disabled={busy}
                  onClick={() => {
                    setEditing(true);
                    setKey('');
                    setConsent(false);
                    setConfirmDelete(false);
                    setError('');
                    setMessage('');
                  }}
                >
                  修改配置
                </Button>
              </div>
              <dl className="ai-settings-details">
                <div>
                  <dt>服务商</dt>
                  <dd>{item.provider}</dd>
                </div>
                <div>
                  <dt>模型名称</dt>
                  <dd>{item.model}</dd>
                </div>
                <div className="ai-settings-detail-wide">
                  <dt>接口地址</dt>
                  <dd className="ai-settings-endpoint">{item.baseUrl}</dd>
                </div>
                <div className="ai-settings-detail-wide">
                  <dt>API Key</dt>
                  <dd className="ai-settings-key-status">
                    <LockIcon size={14} />
                    已安全保存<span>修改配置时重新填写</span>
                  </dd>
                </div>
              </dl>
            </section>
          ) : (
            <form onSubmit={save} className="ai-settings-form">
              <div className="ai-settings-section-heading">
                <h4>{configured ? '修改模型配置' : '添加模型配置'}</h4>
                <p>
                  {configured
                    ? '保存后替换当前配置。取消修改将继续使用原配置。'
                    : '填写此用途使用的服务商、模型和 API Key。'}
                </p>
              </div>
              <label>
                <span className="form-label">服务商</span>
                <input
                  className="form-input"
                  required
                  maxLength={80}
                  value={provider}
                  disabled={busy}
                  onChange={(e) => setProvider(e.target.value)}
                />
              </label>
              <label>
                <span className="form-label">接口地址</span>
                <input
                  className="form-input"
                  required
                  type="url"
                  maxLength={500}
                  placeholder="https://服务商地址/v1"
                  value={baseUrl}
                  disabled={busy}
                  onChange={(e) => setBaseUrl(e.target.value)}
                />
              </label>
              <label>
                <span className="form-label">模型名称</span>
                <input
                  className="form-input"
                  required
                  maxLength={120}
                  value={model}
                  disabled={busy}
                  onChange={(e) => setModel(e.target.value)}
                />
              </label>
              <div>
                <label>
                  <span className="form-label">API Key</span>
                  <input
                    className="form-input"
                    required
                    type="password"
                    autoComplete="off"
                    maxLength={8192}
                    aria-describedby={configured ? 'ai-key-help' : undefined}
                    placeholder={
                      configured ? '重新输入 API Key 以保存修改' : '请输入服务商提供的 API Key'
                    }
                    value={key}
                    disabled={busy || !data.storageReady}
                    onChange={(e) => setKey(e.target.value)}
                  />
                </label>
                {configured && (
                  <p id="ai-key-help" className="form-hint">
                    为保护凭据，原 API Key 不会回显；保存修改时需重新填写。
                  </p>
                )}
              </div>
              <p className="ai-settings-help">
                {purpose === 'RERANK'
                  ? '排序接口需兼容 Cohere v2；填写基础地址（例如 https://服务商地址/v2），系统会追加 /rerank。保存后，主动检索和提问会使用该配置；删除配置可停止排序调用。'
                  : '请使用支持 OpenAI 兼容接口的模型服务；问答模型还需支持 JSON 输出。'}{' '}
                保存配置不会调用模型。
              </p>
              <div className="ai-settings-actions">
                <Button type="submit" loading={busy} disabled={!data.storageReady}>
                  {busy ? '正在保存…' : configured ? '保存修改' : '保存配置'}
                </Button>
                {configured && (
                  <Button type="button" variant="secondary" disabled={busy} onClick={cancelEdit}>
                    取消修改
                  </Button>
                )}
              </div>
            </form>
          )}
          {configured && !editing && (
            <section aria-labelledby="ai-connection-test-title" className="ai-settings-test">
              <div className="ai-settings-test-header">
                <h4 id="ai-connection-test-title">
                  <ActivityIcon size={17} />
                  连接测试
                </h4>
                <Badge variant={testBadge.variant}>{testBadge.label}</Badge>
              </div>
              <p className="ai-settings-help">
                保存配置后可主动测试连接。测试仅确认接口可用，不评估法律回答质量。
              </p>
              {test && (
                <div
                  role="status"
                  className={`ai-settings-test-result ai-settings-test-result-${test.status.toLowerCase()}`}
                >
                  <p>{testLabels[test.status]}</p>
                  {test.status === 'SUCCEEDED' && (
                    <p>
                      {typeof test.elapsedMs === 'number' &&
                        Number.isFinite(test.elapsedMs) &&
                        `用时 ${test.elapsedMs} 毫秒。`}
                      {purpose === 'EMBEDDING' &&
                        typeof test.dimension === 'number' &&
                        Number.isFinite(test.dimension) &&
                        ` 向量维度 ${test.dimension}。`}
                    </p>
                  )}
                </div>
              )}
              {testNotice && (
                <p role="status" className="ai-settings-help">
                  {testNotice}
                </p>
              )}
              <label className="ai-settings-consent">
                <input
                  type="checkbox"
                  checked={consent}
                  disabled={busy || readingTest || !unchanged || activeTest(test)}
                  onChange={(e) => setConsent(e.target.checked)}
                />
                <span>我确认将发送少量合成文本，可能产生模型费用。</span>
              </label>
              <div className="ai-settings-actions">
                <Button
                  type="button"
                  onClick={() => void runTest()}
                  disabled={
                    busy ||
                    readingTest ||
                    !data.storageReady ||
                    !unchanged ||
                    !consent ||
                    activeTest(test)
                  }
                >
                  {testing ? '正在测试…' : test || testUncertain ? '重新测试连接' : '测试连接'}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  loading={readingTest}
                  disabled={busy || !item?.revision}
                  onClick={() => {
                    if (item?.revision) void readLatest(savedScope, purpose, item.revision);
                  }}
                >
                  读取测试结果
                </Button>
                {test?.status === 'STALE' && (
                  <Button
                    type="button"
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void load()}
                  >
                    重新读取配置
                  </Button>
                )}
              </div>
              <p className="ai-settings-help">
                读取结果或刷新页面不会调用模型。测试失败或结果未知时，系统不会自动重试模型请求。
              </p>
            </section>
          )}
          {configured && !editing && (
            <div className="ai-settings-delete">
              {!confirmDelete ? (
                <Button variant="ghost" disabled={busy} onClick={() => setConfirmDelete(true)}>
                  删除此配置
                </Button>
              ) : (
                <>
                  <p className="ai-settings-help">删除后，这一用途需要重新填写 Key 才能使用。</p>
                  <Button variant="danger" disabled={busy} onClick={() => void remove()}>
                    确认删除
                  </Button>{' '}
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => setConfirmDelete(false)}
                  >
                    保留配置
                  </Button>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </Card>
  );
};
