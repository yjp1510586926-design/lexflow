import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  SearchIcon,
  PlusIcon,
  BriefcaseIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  RefreshCwIcon,
} from '../../components/common/Icons';
import { listCases, createCase, getCaseOwners, CaseOwner } from '../../api/cases';
import { CaseView, CaseStatus, CreateCaseParams } from '../../types/case';

const CASE_TYPES = [
  '民事诉讼',
  '商事仲裁',
  '刑事辩护',
  '知识产权',
  '劳动争议',
  '公司合规与并购',
  '常年法律顾问',
  '其他业务',
];

interface CaseListPageProps {
  onSelectCase: (caseId: string) => void;
  createModalOpen?: boolean;
  onCloseCreateModal?: () => void;
}

export const CaseListPage: React.FC<CaseListPageProps> = ({
  onSelectCase,
  createModalOpen: externalModalOpen,
  onCloseCreateModal: externalCloseModal,
}) => {
  const toast = useToast();
  const [cases, setCases] = useState<CaseView[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [page, setPage] = useState<number>(1);
  const pageSize = 15;
  const [loading, setLoading] = useState<boolean>(true);

  // Filters
  const [keyword, setKeyword] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<CaseStatus | undefined>(undefined);
  const [typeFilter, setTypeFilter] = useState<string>('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [owners, setOwners] = useState<CaseOwner[]>([]);
  const [ownersLoading, setOwnersLoading] = useState(true);
  const [ownersError, setOwnersError] = useState('');
  const alive = useRef(true);
  const listRead = useRef(0);

  const accessGeneration = useRef(0),
    deniedRef = useRef(false);
  const [denied, setDenied] = useState(false);
  const externalClose = useRef(externalCloseModal);
  externalClose.current = externalCloseModal;

  // Create Modal state
  const [internalModalOpen, setInternalModalOpen] = useState<boolean>(false);
  const isCreateOpen = Boolean(externalModalOpen || internalModalOpen);
  const closeCreateModal = () => {
    setInternalModalOpen(false);
    externalCloseModal?.();
  };

  const [createForm, setCreateForm] = useState<CreateCaseParams>({
    caseNo: '',
    title: '',
    type: '民事诉讼',
    description: '',
  });
  const [creating, setCreating] = useState<boolean>(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const loseAccess = useCallback(() => {
    deniedRef.current = true;
    accessGeneration.current++;
    listRead.current++;
    setCases([]);
    setTotal(0);
    setOwners([]);
    setOwnerFilter('');
    setOwnersError('');
    setInternalModalOpen(false);
    externalClose.current?.();
    setCreating(false);
    setCreateError(null);
    setCreateForm({ caseNo: '', title: '', type: '民事诉讼', description: '' });
    setLoading(false);
    setOwnersLoading(false);
    setDenied(true);
  }, []);

  useEffect(() => {
    alive.current = true;
    const access = accessGeneration.current;
    void getCaseOwners()
      .then((items) => {
        if (alive.current && !deniedRef.current && access === accessGeneration.current)
          setOwners(items);
      })
      .catch((error) => {
        if (!alive.current || deniedRef.current || access !== accessGeneration.current) return;
        if (error.status === 401 || error.status === 403) loseAccess();
        else {
          setOwners([]);
          setOwnersError(error instanceof Error ? error.message : '加载负责人失败');
        }
      })
      .finally(() => {
        if (alive.current && !deniedRef.current && access === accessGeneration.current)
          setOwnersLoading(false);
      });
    return () => {
      alive.current = false;
      accessGeneration.current++;
      listRead.current++;
    };
  }, [loseAccess]);

  const fetchCaseList = useCallback(async () => {
    if (deniedRef.current) return;
    const access = accessGeneration.current;
    const round = ++listRead.current;
    setLoading(true);
    try {
      const data = await listCases({
        page,
        size: pageSize,
        keyword: keyword.trim() || undefined,
        status: statusFilter,
        type: typeFilter.trim() || undefined,
        ownerId: ownerFilter || undefined,
      });
      if (
        !alive.current ||
        deniedRef.current ||
        access !== accessGeneration.current ||
        round !== listRead.current
      )
        return;
      setCases(data.items);
      setTotal(data.total);
    } catch (err: any) {
      if (!alive.current || deniedRef.current || access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess();
      if (err.status === 401 || err.status === 403 || round === listRead.current)
        toast.error(err.message || '加载案件列表失败');
    } finally {
      if (alive.current && access === accessGeneration.current && round === listRead.current)
        setLoading(false);
    }
  }, [page, keyword, statusFilter, typeFilter, ownerFilter, toast, loseAccess]);

  useEffect(() => {
    fetchCaseList();
  }, [fetchCaseList]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(1);
    if (page === 1) void fetchCaseList();
  };

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (deniedRef.current) return;
    const access = accessGeneration.current;
    setCreateError(null);

    if (!createForm.caseNo.trim()) {
      setCreateError('请输入案件编号');
      return;
    }
    if (!createForm.title.trim()) {
      setCreateError('请输入案件名称');
      return;
    }

    setCreating(true);
    try {
      const newCase = await createCase({
        caseNo: createForm.caseNo.trim(),
        title: createForm.title.trim(),
        type: createForm.type,
        description: createForm.description.trim(),
        filedOn: createForm.filedOn || null,
      });
      if (!alive.current || deniedRef.current || access !== accessGeneration.current) return;
      toast.success(`案件 [${newCase.caseNo}] 创建成功，您已自动成为负责人`);
      closeCreateModal();
      setCreateForm({
        caseNo: '',
        title: '',
        type: '民事诉讼',
        description: '',
      });
      setPage(1);
      fetchCaseList();
      onSelectCase(newCase.id);
    } catch (err: any) {
      if (!alive.current || deniedRef.current || access !== accessGeneration.current) return;
      if (err.status === 401 || err.status === 403) loseAccess();
      else setCreateError(err.message || '创建案件失败');
    } finally {
      if (alive.current && access === accessGeneration.current) setCreating(false);
    }
  };

  const totalPages = Math.ceil(total / pageSize) || 1;

  if (denied)
    return (
      <p role="alert" style={{ color: 'var(--danger-text)' }}>
        登录状态或案件权限已失效。旧案件与负责人资料已清空，请重新打开页面或联系管理员。
      </p>
    );

  return (
    <div className="case-list-page">
      {/* Search & Filter Bar */}
      <div className="filter-bar">
        <form onSubmit={handleSearchSubmit} className="filter-group-left">
          <div className="search-box">
            <SearchIcon size={16} className="search-icon" />
            <input
              type="text"
              className="form-input"
              placeholder="搜索案件编号或标题..."
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>

          <div className="status-tabs" role="tablist">
            <button
              type="button"
              className={`status-tab-btn ${statusFilter === undefined ? 'active' : ''}`}
              onClick={() => {
                setStatusFilter(undefined);
                setPage(1);
              }}
            >
              全部状态
            </button>
            <button
              type="button"
              className={`status-tab-btn ${statusFilter === 'ACTIVE' ? 'active' : ''}`}
              onClick={() => {
                setStatusFilter('ACTIVE');
                setPage(1);
              }}
            >
              进行中
            </button>
            <button
              type="button"
              className={`status-tab-btn ${statusFilter === 'DRAFT' ? 'active' : ''}`}
              onClick={() => {
                setStatusFilter('DRAFT');
                setPage(1);
              }}
            >
              草稿
            </button>
            <button
              type="button"
              className={`status-tab-btn ${statusFilter === 'ARCHIVED' ? 'active' : ''}`}
              onClick={() => {
                setStatusFilter('ARCHIVED');
                setPage(1);
              }}
            >
              已归档
            </button>
          </div>

          <select
            className="form-select"
            style={{ width: 'auto' }}
            value={typeFilter}
            onChange={(e) => {
              setTypeFilter(e.target.value);
              setPage(1);
            }}
          >
            <option value="">全部业务类型</option>
            {CASE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>

          <select
            className="form-select"
            aria-label="案件负责人"
            style={{ width: 'auto', maxWidth: 200 }}
            value={ownerFilter}
            disabled={ownersLoading || !!ownersError}
            onChange={(event) => {
              setOwnerFilter(event.target.value);
              setPage(1);
            }}
          >
            <option value="">{ownersLoading ? '加载负责人...' : '全部负责人'}</option>
            {owners.map((owner) => (
              <option key={owner.id} value={owner.id}>
                {owner.name}
              </option>
            ))}
          </select>
          {ownersError && (
            <span className="form-hint" role="alert">
              负责人筛选暂不可用：{ownersError}
            </span>
          )}

          <Button type="submit" variant="secondary" size="md">
            筛选
          </Button>

          {(keyword || statusFilter || typeFilter || ownerFilter) && (
            <Button
              type="button"
              variant="ghost"
              size="md"
              onClick={() => {
                setKeyword('');
                setStatusFilter(undefined);
                setTypeFilter('');
                setOwnerFilter('');
                setPage(1);
              }}
            >
              重置
            </Button>
          )}
        </form>

        <div>
          <Button
            variant="primary"
            size="md"
            icon={<PlusIcon size={16} />}
            onClick={() => setInternalModalOpen(true)}
          >
            立案新建
          </Button>
        </div>
      </div>

      {/* Main Cases Table Card */}
      <Card>
        {loading ? (
          <Spinner text="正在查询案件列表..." />
        ) : cases.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon">
              <BriefcaseIcon size={28} />
            </div>
            <h4 className="empty-state-title">未查询到符合条件的案件</h4>
            <p className="empty-state-description">
              您可以调整搜索过滤条件，或者直接点击立案按钮创建新的案件卷宗。
            </p>
            <Button
              variant="primary"
              size="sm"
              icon={<PlusIcon size={16} />}
              onClick={() => setInternalModalOpen(true)}
            >
              立案新建
            </Button>
          </div>
        ) : (
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th style={{ width: '15%' }}>案件编号</th>
                  <th style={{ width: '28%' }}>案件标题</th>
                  <th style={{ width: '15%' }}>业务类别</th>
                  <th style={{ width: '12%' }}>案卷状态</th>
                  <th style={{ width: '10%' }}>修订版本</th>
                  <th style={{ width: '20%', textAlign: 'right' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {cases.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <span className="detail-case-no">{c.caseNo}</span>
                    </td>
                    <td>
                      <span
                        style={{
                          fontWeight: 600,
                          cursor: 'pointer',
                          color: 'var(--navy-900)',
                        }}
                        onClick={() => onSelectCase(c.id)}
                      >
                        {c.title}
                      </span>
                      {c.description && (
                        <p
                          style={{
                            fontSize: 12,
                            color: 'var(--text-muted)',
                            marginTop: 2,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            maxWidth: 320,
                          }}
                        >
                          {c.description}
                        </p>
                      )}
                    </td>
                    <td>{c.type}</td>
                    <td>
                      <Badge type="status" value={c.status} />
                    </td>
                    <td>
                      <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>v{c.version}</span>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Button variant="secondary" size="sm" onClick={() => onSelectCase(c.id)}>
                        案件工作台
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        <div className="pagination-wrapper">
          <span>
            共 <strong>{total}</strong> 条记录 · 当前第 {page} / {totalPages} 页
          </span>
          <div className="pagination-actions">
            <Button
              variant="secondary"
              size="sm"
              icon={<ChevronLeftIcon size={14} />}
              disabled={page <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="secondary"
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
              <ChevronRightIcon size={14} />
            </Button>
          </div>
        </div>
      </Card>

      {/* Create Case Modal */}
      <Modal
        isOpen={isCreateOpen}
        onClose={closeCreateModal}
        title="立案新建案件"
        subtitle="请录入案件文号与基本案情，创建后您将担任本案主办律师"
        maxWidth="md"
        footer={
          <>
            <Button variant="ghost" size="md" onClick={closeCreateModal}>
              取消
            </Button>
            <Button variant="primary" size="md" loading={creating} onClick={handleCreateSubmit}>
              确认立案
            </Button>
          </>
        }
      >
        {createError && (
          <div className="login-error-banner" style={{ marginBottom: 16 }}>
            <span>{createError}</span>
          </div>
        )}

        <form onSubmit={handleCreateSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="caseNo">
              案件编号 <span className="required">*</span>
            </label>
            <input
              id="caseNo"
              type="text"
              className="form-input"
              placeholder="例如: (2026) 京0105民初1024号"
              value={createForm.caseNo}
              onChange={(e) => setCreateForm({ ...createForm, caseNo: e.target.value })}
              required
            />
            <div className="form-hint">支持输入法院案号、仲裁案号或律所内部归档文号</div>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="title">
              案件标题 <span className="required">*</span>
            </label>
            <input
              id="title"
              type="text"
              className="form-input"
              placeholder="例如: 某科技公司与某文化传媒合同纠纷案"
              value={createForm.title}
              onChange={(e) => setCreateForm({ ...createForm, title: e.target.value })}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="type">
              业务类别 <span className="required">*</span>
            </label>
            <select
              id="type"
              className="form-select"
              value={createForm.type}
              onChange={(e) => setCreateForm({ ...createForm, type: e.target.value })}
            >
              {CASE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="filedOn">
              立案日期
            </label>
            <input
              id="filedOn"
              type="date"
              className="form-input"
              value={createForm.filedOn || ''}
              onChange={(e) => setCreateForm({ ...createForm, filedOn: e.target.value || null })}
            />
            <p className="form-hint">未知时可以留空，之后在案件详情补充。</p>
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor="description">
              案情简介及诉求
            </label>
            <textarea
              id="description"
              className="form-textarea"
              rows={4}
              placeholder="请输入争议要点、标的额、诉讼请求或客户背景..."
              value={createForm.description}
              onChange={(e) => setCreateForm({ ...createForm, description: e.target.value })}
            />
          </div>
        </form>
      </Modal>
    </div>
  );
};
