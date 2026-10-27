import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  getEvidence,
  listEvidence,
  saveEvidence,
  downloadEvidenceOriginal,
  EvidencePage,
  EvidenceView,
} from '../../api/evidence';
import { listCaseFiles } from '../../api/files';
import { FileView } from '../../types/file';
import { CaseView } from '../../types/case';
import { Button } from '../../components/common/Button';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';

const localTime = (value: string | null) => {
  if (!value) return '';
  const d = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const displayTime = (value: string | null) => (value ? new Date(value).toLocaleString() : '未记录');

export function CaseEvidenceTab({ caseInfo }: { caseInfo: CaseView }) {
  const [data, setData] = useState<EvidencePage | null>(null);
  const [files, setFiles] = useState<FileView[]>([]);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [denied, setDenied] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState('');
  const [version, setVersion] = useState(0);
  const [source, setSource] = useState('');
  const [collectedAt, setCollectedAt] = useState('');
  const [originalCollectedAt, setOriginalCollectedAt] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [sortOrder, setSortOrder] = useState('0');
  const generation = useRef(0);
  const saving = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current++;
    };
  }, []);
  const clear = () => {
    setData(null);
    setFiles([]);
    setOpen(false);
    setSource('');
    setDescription('');
    setCollectedAt('');
    setOriginalCollectedAt(null);
    setSelected('');
    setVersion(0);
    setSortOrder('0');
  };
  const handleError = (cause: any, inForm = false) => {
    const message = cause.message || '证据目录暂不可用';
    if ([401, 403].includes(cause.status)) {
      clear();
      setDenied(true);
      setError(message);
    } else if (inForm) setFormError(message);
    else setError(message);
  };
  const load = useCallback(async () => {
    const current = ++generation.current;
    setLoading(true);
    setError('');
    try {
      const [entries, available] = await Promise.all([
        listEvidence(caseInfo.id, page),
        listCaseFiles(caseInfo.id),
      ]);
      if (!mounted.current || current !== generation.current) return;
      setData(entries);
      setFiles(available.filter((f) => f.status === 'AVAILABLE' && f.versionId));
      setDenied(false);
    } catch (cause) {
      if (mounted.current && current === generation.current) handleError(cause);
    } finally {
      if (mounted.current && current === generation.current) setLoading(false);
    }
  }, [caseInfo.id, caseInfo.status, page]);
  useEffect(() => {
    clear();
    setBusy(false);
    setDenied(false);
    void load();
  }, [load]);
  const fill = (entry: EvidenceView | null) => {
    setVersion(entry?.version ?? 0);
    setSource(entry?.source ?? '');
    setCollectedAt(localTime(entry?.collectedAt ?? null));
    setOriginalCollectedAt(entry?.collectedAt ?? null);
    setDescription(entry?.description ?? '');
    setSortOrder(String(entry?.sortOrder ?? 0));
  };
  const select = async (id: string) => {
    const current = ++generation.current;
    setSelected(id);
    fill(null);
    setFormError('');
    setBusy(true);
    try {
      const entry = await getEvidence(caseInfo.id, id);
      if (mounted.current && current === generation.current) fill(entry);
    } catch (cause) {
      if (mounted.current && current === generation.current) {
        handleError(cause, true);
        setSelected('');
      }
    } finally {
      if (mounted.current && current === generation.current) setBusy(false);
    }
  };
  const edit = (entry?: EvidenceView) => {
    setFormError('');
    setOpen(true);
    if (entry) {
      setSelected(entry.fileVersionId);
      fill(entry);
    } else {
      setSelected('');
      fill(null);
    }
  };
  const close = () => {
    if (saving.current || busy) return;
    generation.current++;
    setOpen(false);
    setFormError('');
  };
  const save = async () => {
    if (saving.current || busy || !selected) return;
    const order = Number(sortOrder);
    if (!source.trim() || !Number.isInteger(order) || order < 0 || order > 100000) {
      setFormError('请填写证据来源和有效顺序');
      return;
    }
    let timestamp: string | null = null;
    if (collectedAt) {
      const d = new Date(collectedAt);
      if (!Number.isFinite(d.getTime())) {
        setFormError('收集时间无效');
        return;
      }
      timestamp = originalCollectedAt || d.toISOString();
    }
    saving.current = true;
    setBusy(true);
    setFormError('');
    const current = generation.current;
    try {
      await saveEvidence(caseInfo.id, selected, {
        version,
        source: source.trim(),
        collectedAt: timestamp,
        description: description.trim(),
        sortOrder: order,
      });
      if (!mounted.current || current !== generation.current) return;
      setOpen(false);
      await load();
    } catch (cause: any) {
      if (mounted.current && current === generation.current) handleError(cause, true);
    } finally {
      saving.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  const download = async (entry: EvidenceView) => {
    if (busy) return;
    setBusy(true);
    try {
      await downloadEvidenceOriginal(entry.fileId, entry.fileName);
    } catch (cause) {
      handleError(cause);
    } finally {
      if (mounted.current) setBusy(false);
    }
  };
  const editable = !!data?.canEdit && caseInfo.status !== 'ARCHIVED';
  return (
    <div>
      <p className="form-hint">
        为已上传的原件记录来源、收集时间和说明，数字较小的证据排在前面。目录信息单独保存，不修改原始文件。
      </p>
      {error && (
        <p role="alert" style={{ color: 'var(--danger-text)', margin: '12px 0' }}>
          {error}
        </p>
      )}
      <div style={{ display: 'flex', gap: 10, margin: '16px 0' }}>
        {editable && (
          <Button size="sm" disabled={busy || loading || files.length === 0} onClick={() => edit()}>
            记录证据
          </Button>
        )}
        <Button
          size="sm"
          variant="secondary"
          disabled={busy || loading}
          onClick={() => void load()}
        >
          刷新目录
        </Button>
      </div>
      {loading ? (
        <Spinner text="正在读取证据目录..." />
      ) : (
        !denied && (
          <>
            {!data?.items.length ? (
              <p>暂无证据目录。先上传文件，再记录证据来源。</p>
            ) : (
              <div className="table-responsive">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>顺序 / 原件</th>
                      <th>来源与收集时间</th>
                      <th>说明</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((entry) => (
                      <tr key={entry.id}>
                        <td>
                          <strong>
                            {entry.sortOrder} · {entry.fileName}
                          </strong>
                          <p className="form-hint">文件 v{entry.fileVersion}</p>
                        </td>
                        <td>
                          {entry.source}
                          <p className="form-hint">{displayTime(entry.collectedAt)}</p>
                        </td>
                        <td
                          style={{
                            whiteSpace: 'pre-wrap',
                            maxWidth: 360,
                            overflowWrap: 'anywhere',
                          }}
                        >
                          {entry.description || '未填写'}
                        </td>
                        <td>
                          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => void download(entry)}
                            >
                              下载原件
                            </Button>
                            {editable && (
                              <Button
                                size="sm"
                                variant="secondary"
                                disabled={busy}
                                onClick={() => edit(entry)}
                              >
                                编辑目录
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 16 }}>
              <span>
                共 {data?.total ?? 0} 项 · 第 {page} 页
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={page <= 1 || busy}
                onClick={() => setPage((n) => n - 1)}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={page * 20 >= (data?.total ?? 0) || busy}
                onClick={() => setPage((n) => n + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        )
      )}
      <Modal
        isOpen={open && editable}
        onClose={close}
        title="证据目录信息"
        subtitle="编辑目录不会替换原件，收集时间按你的本地时区输入。"
      >
        {formError && (
          <p role="alert" style={{ color: 'var(--danger-text)', marginBottom: 12 }}>
            {formError}
          </p>
        )}
        <div className="form-group">
          <label htmlFor="evidenceFile" className="form-label">
            原件
          </label>
          <select
            id="evidenceFile"
            className="form-select"
            value={selected}
            disabled={busy}
            onChange={(e) => {
              if (e.target.value) void select(e.target.value);
              else {
                setSelected('');
                fill(null);
              }
            }}
          >
            <option value="">请选择已上传的文件</option>
            {files.map((file) => (
              <option key={file.id} value={file.versionId!}>
                {file.displayName} · v{file.versionNo}
              </option>
            ))}
          </select>
        </div>
        <div className="form-group">
          <label htmlFor="evidenceSource" className="form-label">
            证据来源 *
          </label>
          <input
            id="evidenceSource"
            className="form-input"
            maxLength={500}
            value={source}
            disabled={busy}
            onChange={(e) => setSource(e.target.value)}
            placeholder="例如：客户提供的付款凭证"
          />
        </div>
        <div className="form-group">
          <label htmlFor="evidenceTime" className="form-label">
            收集时间
          </label>
          <input
            id="evidenceTime"
            className="form-input"
            type="datetime-local"
            value={collectedAt}
            disabled={busy}
            onChange={(e) => {
              setCollectedAt(e.target.value);
              setOriginalCollectedAt(null);
            }}
          />
        </div>
        <div className="form-group">
          <label htmlFor="evidenceDescription" className="form-label">
            说明
          </label>
          <textarea
            id="evidenceDescription"
            className="form-textarea"
            rows={3}
            maxLength={4000}
            value={description}
            disabled={busy}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>
        <div className="form-group">
          <label htmlFor="evidenceOrder" className="form-label">
            顺序
          </label>
          <input
            id="evidenceOrder"
            className="form-input"
            type="number"
            min={0}
            max={100000}
            step={1}
            value={sortOrder}
            disabled={busy}
            onChange={(e) => setSortOrder(e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', gap: 10, justifyContent: 'end' }}>
          <Button variant="secondary" disabled={busy} onClick={close}>
            取消
          </Button>
          <Button loading={busy} disabled={!selected || !source.trim()} onClick={() => void save()}>
            保存证据目录
          </Button>
        </div>
      </Modal>
    </div>
  );
}
