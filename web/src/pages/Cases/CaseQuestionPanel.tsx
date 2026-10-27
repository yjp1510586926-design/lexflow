import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button';
import { useToast } from '../../components/common/Toast';
import { downloadFile } from '../../api/files';
import { downloadTenantKnowledgeFile } from '../../api/tenant-knowledge';
import {
  AiSession,
  AiQuestion,
  listAiSessions,
  createAiSession,
  listAiQuestions,
  askAiQuestion,
} from '../../api/ai';

export const CaseQuestionPanel: React.FC<{
  baseId: string;
  scope?: 'CASE' | 'TENANT';
  onAccessLost?: () => void;
}> = ({ baseId, scope = 'CASE', onAccessLost }) => {
  const toast = useToast();
  const [sessions, setSessions] = useState<AiSession[]>([]);
  const [sessionId, setSessionId] = useState('');
  const [questions, setQuestions] = useState<AiQuestion[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [accessError, setAccessError] = useState('');
  const alive = useRef(true);
  const generation = useRef(0);
  const denied = useRef(false);
  const source = useRef(`${scope}:${baseId}`);
  const selectedSession = useRef(sessionId);
  const sessionRead = useRef(0);
  const questionRead = useRef(0);
  const asking = useRef(false);
  const accessLostCallback = useRef(onAccessLost);
  const submission = useRef<{ sessionId: string; requestId: string; question: string } | null>(
    null,
  );
  source.current = `${scope}:${baseId}`;
  selectedSession.current = sessionId;
  accessLostCallback.current = onAccessLost;

  // 知识库身份与权限代次同时匹配才可回填；仅比较会话 ID 无法隔离知识库切换。
  const current = useCallback(
    (access: number, sourceId: string) =>
      alive.current &&
      !denied.current &&
      access === generation.current &&
      sourceId === source.current,
    [],
  );

  const clear = useCallback(() => {
    sessionRead.current++;
    questionRead.current++;
    asking.current = false;
    selectedSession.current = '';
    submission.current = null;
    setQuestions([]);
    setSessions([]);
    setSessionId('');
    setQuestion('');
    setBusy(false);
    setLoading(false);
  }, []);

  const handleError = useCallback(
    (error: any, fallback: string, access: number, sourceId: string) => {
      if (!current(access, sourceId)) return;
      if (error.status === 401 || error.status === 403) {
        denied.current = true;
        generation.current++;
        clear();
        setAccessError(
          `问答权限已失效，请重新打开${sourceId.startsWith('TENANT:') ? '律所知识库' : '案件'}后再试。`,
        );
        accessLostCallback.current?.();
      }
      toast.error(error instanceof Error ? error.message : fallback);
    },
    [clear, current, toast],
  );

  useEffect(() => {
    alive.current = true;
    generation.current++;
    denied.current = false;
    clear();
    setAccessError('');
    setLoading(true);
    const access = generation.current,
      sourceId = `${scope}:${baseId}`,
      round = ++sessionRead.current;
    listAiSessions(baseId)
      .then((items) => {
        if (!current(access, sourceId) || round !== sessionRead.current) return;
        setSessions(items);
        selectedSession.current = items[0]?.id ?? '';
        setSessionId(selectedSession.current);
      })
      .catch((error) => handleError(error, '读取会话失败', access, sourceId))
      .finally(() => {
        if (current(access, sourceId) && round === sessionRead.current) setLoading(false);
      });
    return () => {
      alive.current = false;
      generation.current++;
      sessionRead.current++;
      questionRead.current++;
    };
  }, [baseId, scope, clear, current, handleError]);

  useEffect(() => {
    const access = generation.current,
      sourceId = `${scope}:${baseId}`,
      round = ++questionRead.current;
    let active = true;
    setQuestions([]);
    if (sessionId && sessionId === selectedSession.current && !denied.current) {
      listAiQuestions(sessionId)
        .then((items) => {
          if (
            active &&
            current(access, sourceId) &&
            round === questionRead.current &&
            sessionId === selectedSession.current
          )
            setQuestions(items);
        })
        .catch((error) => {
          if (active) handleError(error, '读取问答失败', access, sourceId);
        });
    }
    return () => {
      active = false;
    };
  }, [baseId, scope, sessionId, current, handleError]);

  const refresh = async (
    id = selectedSession.current,
    access = generation.current,
    sourceId = source.current,
  ) => {
    if (!id || !current(access, sourceId)) return;
    const round = ++questionRead.current;
    try {
      const items = await listAiQuestions(id);
      if (
        !current(access, sourceId) ||
        round !== questionRead.current ||
        id !== selectedSession.current
      )
        return;
      setQuestions(items);
      // PROCESSING 或响应丢失时保留请求标识，读到终态后才结束这次提交意图。
      if (
        items.some(
          (item) =>
            item.requestId === submission.current?.requestId && item.status !== 'PROCESSING',
        )
      )
        submission.current = null;
    } catch (error) {
      handleError(error, '刷新失败', access, sourceId);
    }
  };

  const ask = async () => {
    if (question.trim().length < 2 || asking.current || denied.current) return;
    // 同步锁在创建会话之前生效，防止连续点击同时创建会话或提交相同付费问题。
    asking.current = true;
    setBusy(true);
    const access = generation.current,
      sourceId = source.current,
      text = question.trim();
    let id = selectedSession.current;
    try {
      if (!id) {
        const session = await createAiSession(baseId, text.slice(0, 50));
        if (!current(access, sourceId)) return;
        id = session.id;
        selectedSession.current = id;
        setSessions((items) => [session, ...items]);
        setSessionId(id);
      }
      // 处理中或响应未知时，同一会话、同一问题复用请求 ID；已结束或输入变化后创建新意图。
      if (submission.current?.sessionId !== id || submission.current.question !== text)
        submission.current = { sessionId: id, requestId: crypto.randomUUID(), question: text };
      const result = await askAiQuestion(id, submission.current.requestId, text);
      if (!current(access, sourceId)) return;
      if (result.status !== 'PROCESSING') {
        submission.current = null;
        setQuestion('');
      }
      await refresh(id, access, sourceId);
    } catch (error) {
      handleError(error, '回答未完成', access, sourceId);
      if (current(access, sourceId) && id) await refresh(id, access, sourceId);
    } finally {
      if (current(access, sourceId)) {
        asking.current = false;
        setBusy(false);
      }
    }
  };

  const download = async (citation: AiQuestion['citations'][number]) => {
    if (denied.current) return;
    const access = generation.current,
      sourceId = source.current;
    try {
      if (scope === 'TENANT')
        await downloadTenantKnowledgeFile(baseId, citation.fileId, citation.fileName, () =>
          current(access, sourceId),
        );
      else await downloadFile(citation.fileId, citation.fileName, () => current(access, sourceId));
    } catch (error) {
      handleError(error, '下载失败', access, sourceId);
    }
  };
  return (
    <section aria-label={scope === 'TENANT' ? '律所资料问答' : '案件资料问答'}>
      <h4 style={{ marginBottom: 8 }}>{scope === 'TENANT' ? '问律所资料' : '问案件资料'}</h4>
      {accessError && (
        <p role="alert" style={{ color: 'var(--danger-text)' }}>
          {accessError}
        </p>
      )}
      <p className="form-hint">
        根据本知识库原文回答并标注出处。资料不足时，会在你有权访问的本知识库内扩大检索一次；仍不足则说明无法作答。管理员须先配置
        LLM 和 Embedding
        Key；已配置的排序模型也会参与检索。点击提问可能产生模型费用，扩大检索可能增加调用，回答仍需你核对原件。
      </p>
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
        <select
          aria-label="问答会话"
          className="form-select"
          style={{ maxWidth: 300 }}
          value={sessionId}
          disabled={busy || loading || !!accessError}
          onChange={(event) => {
            setSessionId(event.target.value);
            submission.current = null;
          }}
        >
          <option value="">新会话</option>
          {sessions.map((session) => (
            <option key={session.id} value={session.id}>
              {session.title}
            </option>
          ))}
        </select>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy || !sessionId || !!accessError}
          onClick={() => void refresh()}
        >
          刷新问答记录
        </Button>
      </div>
      <div style={{ display: 'flex', gap: 10, alignItems: 'start', flexWrap: 'wrap' }}>
        <textarea
          className="form-input"
          style={{ flex: 1, minWidth: 240, maxWidth: 560 }}
          rows={2}
          value={question}
          maxLength={500}
          disabled={busy || !!accessError}
          aria-label="资料问答问题"
          placeholder="例如：合同约定何时付款？请列出原文依据。"
          onChange={(event) => setQuestion(event.target.value)}
        />
        <Button
          size="sm"
          onClick={() => void ask()}
          loading={busy}
          disabled={loading || !!accessError || question.trim().length < 2}
        >
          {busy ? '正在回答…' : '提问'}
        </Button>
      </div>
      {questions.length === 0 && (
        <p className="form-hint" style={{ marginTop: 12 }}>
          还没有问答记录。只有已完成索引的资料会参与回答。
        </p>
      )}
      {questions.map((item) => (
        <article
          key={item.id}
          style={{ padding: '16px 0', borderBottom: '1px solid var(--border)' }}
        >
          <strong>你：{item.question}</strong>
          <p style={{ whiteSpace: 'pre-wrap', margin: '8px 0', lineHeight: 1.7 }}>
            {item.sourcesUnavailable
              ? '原始资料已不可用，这段回答和引用已隐藏。'
              : item.status === 'PROCESSING'
                ? '正在处理，请稍后刷新记录。'
                : item.status === 'FAILED'
                  ? (item.error ?? '回答未完成，请重新提问。')
                  : item.answer}
          </p>
          {item.citations.map((source) => (
            <details key={source.id} style={{ marginTop: 8 }}>
              <summary>
                [{source.id}] {source.fileName} · v{source.fileVersion} ·{' '}
                {source.locatorKind === 'PAGE'
                  ? `第 ${source.locatorIndex} 页`
                  : `第 ${source.locatorIndex} 段`}
              </summary>
              <p style={{ whiteSpace: 'pre-wrap', margin: '8px 0' }}>{source.text}</p>
              <Button variant="ghost" size="sm" onClick={() => void download(source)}>
                下载原件
              </Button>
            </details>
          ))}
        </article>
      ))}
    </section>
  );
};
