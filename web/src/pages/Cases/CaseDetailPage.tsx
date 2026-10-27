import React, { useState, useEffect, useCallback } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  ArrowLeftIcon,
  RefreshCwIcon,
  BriefcaseIcon,
  UsersIcon,
  FileTextIcon,
  SparklesIcon,
  AlertCircleIcon,
  CheckIcon,
} from '../../components/common/Icons';
import { getCase, updateCase } from '../../api/cases';
import { CaseView, CaseStatus } from '../../types/case';
import { CaseMembersTab } from './CaseMembersTab';
import { CaseFilesTab } from './CaseFilesTab';
import { CaseDocumentsTab } from './CaseDocumentsTab';
import { CaseKnowledgeTab } from './CaseKnowledgeTab';
import { CaseEvidenceTab } from './CaseEvidenceTab';
import { CaseAnalysisTab } from './CaseAnalysisTab';

interface CaseDetailPageProps {
  caseId: string;
  onBack: () => void;
}

export const CaseDetailPage: React.FC<CaseDetailPageProps> = ({ caseId, onBack }) => {
  const toast = useToast();
  const [caseInfo, setCaseInfo] = useState<CaseView | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [activeTab, setActiveTab] = useState<
    'info' | 'members' | 'files' | 'evidence' | 'documents' | 'knowledge' | 'analysis'
  >('info');

  // Edit form state
  const [editTitle, setEditTitle] = useState<string>('');
  const [editDescription, setEditDescription] = useState<string>('');
  const [editFiledOn, setEditFiledOn] = useState<string>('');
  const [editStatus, setEditStatus] = useState<CaseStatus>('DRAFT');
  const [saving, setSaving] = useState<boolean>(false);
  const [conflictError, setConflictError] = useState<string | null>(null);

  const fetchCaseDetail = useCallback(async () => {
    setLoading(true);
    setConflictError(null);
    try {
      const data = await getCase(caseId);
      setCaseInfo(data);
      setEditTitle(data.title);
      setEditDescription(data.description || '');
      setEditFiledOn(data.filedOn || '');
      setEditStatus(data.status);
    } catch (err: any) {
      toast.error(err.message || '获取案件详情失败');
    } finally {
      setLoading(false);
    }
  }, [caseId, toast]);

  useEffect(() => {
    fetchCaseDetail();
  }, [fetchCaseDetail]);

  const handleUpdate = async (newStatus?: CaseStatus) => {
    if (!caseInfo) return;
    setConflictError(null);
    setSaving(true);

    const targetStatus = newStatus || editStatus;

    try {
      const updated = await updateCase(caseId, {
        version: caseInfo.version,
        title: editTitle.trim(),
        description: editDescription.trim(),
        status: targetStatus,
        filedOn: editFiledOn || null,
      });
      setCaseInfo(updated);
      setEditTitle(updated.title);
      setEditDescription(updated.description || '');
      setEditFiledOn(updated.filedOn || '');
      setEditStatus(updated.status);
      toast.success('案件信息已成功更新');
    } catch (err: any) {
      if (err.status === 409) {
        setConflictError('数据已被他人修改或版本已过时，请点击下方刷新按钮同步最新数据后再重试。');
        toast.error('案件已被其他办案成员更新，请刷新获取最新内容后再试');
      } else {
        toast.error(err.message || '更新案件失败');
      }
    } finally {
      setSaving(false);
    }
  };

  if (loading && !caseInfo) {
    return (
      <div style={{ padding: 48 }}>
        <Spinner text="正在加载案件详情..." />
      </div>
    );
  }

  if (!caseInfo) {
    return (
      <div className="empty-state">
        <AlertCircleIcon size={36} style={{ color: 'var(--rose-500)', marginBottom: 12 }} />
        <h4 className="empty-state-title">未能找到案件或已被移出访问权限</h4>
        <Button
          variant="secondary"
          size="md"
          icon={<ArrowLeftIcon size={16} />}
          onClick={onBack}
          style={{ marginTop: 16 }}
        >
          返回案件列表
        </Button>
      </div>
    );
  }

  return (
    <div className="case-detail-page">
      {/* Top Navigation & Status Bar */}
      <div style={{ marginBottom: 20 }}>
        <Button
          variant="ghost"
          size="sm"
          icon={<ArrowLeftIcon size={15} />}
          onClick={onBack}
          style={{ marginBottom: 12 }}
        >
          返回案件列表
        </Button>
      </div>

      {conflictError && (
        <div
          className="login-error-banner"
          style={{
            marginBottom: 20,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <AlertCircleIcon size={20} />
            <span>{conflictError}</span>
          </div>
          <Button
            variant="secondary"
            size="sm"
            icon={<RefreshCwIcon size={14} />}
            onClick={fetchCaseDetail}
          >
            立即刷新
          </Button>
        </div>
      )}

      {/* Case Header Card */}
      <Card className="detail-header-card">
        <div className="detail-top-meta">
          <div className="detail-title-group">
            <span className="detail-case-no">{caseInfo.caseNo}</span>
            <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--navy-900)' }}>
              {caseInfo.title}
            </h2>
            <Badge type="status" value={caseInfo.status} />
            <Badge variant="slate">类型: {caseInfo.type}</Badge>
            <Badge variant="blue">锁版本: v{caseInfo.version}</Badge>
          </div>

          {/* Quick Lifecycle Status Transitions */}
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            {caseInfo.status === 'DRAFT' && (
              <Button
                variant="primary"
                size="sm"
                loading={saving}
                icon={<CheckIcon size={14} />}
                onClick={() => handleUpdate('ACTIVE')}
              >
                转为进行中
              </Button>
            )}

            {caseInfo.status === 'ACTIVE' && (
              <Button
                variant="secondary"
                size="sm"
                loading={saving}
                style={{ color: 'var(--warning-text)', borderColor: 'var(--warning-border)' }}
                onClick={() => handleUpdate('ARCHIVED')}
              >
                归档案件
              </Button>
            )}

            {caseInfo.status === 'ARCHIVED' && (
              <Button
                variant="primary"
                size="sm"
                loading={saving}
                icon={<RefreshCwIcon size={14} />}
                onClick={() => handleUpdate('ACTIVE')}
              >
                恢复办理
              </Button>
            )}
          </div>
        </div>
      </Card>

      {/* Sub Tabs */}
      <div className="nav-tabs">
        <button
          className={`tab-btn ${activeTab === 'analysis' ? 'active' : ''}`}
          onClick={() => setActiveTab('analysis')}
        >
          <SparklesIcon size={16} />
          <span>案件分析</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'info' ? 'active' : ''}`}
          onClick={() => setActiveTab('info')}
        >
          <BriefcaseIcon size={16} />
          <span>基本信息与状态</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'members' ? 'active' : ''}`}
          onClick={() => setActiveTab('members')}
        >
          <UsersIcon size={16} />
          <span>办案人员分配</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'files' ? 'active' : ''}`}
          onClick={() => setActiveTab('files')}
        >
          <FileTextIcon size={16} />
          <span>文件与证据管理</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'evidence' ? 'active' : ''}`}
          onClick={() => setActiveTab('evidence')}
        >
          <FileTextIcon size={16} />
          <span>证据目录</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'documents' ? 'active' : ''}`}
          onClick={() => setActiveTab('documents')}
        >
          <SparklesIcon size={16} />
          <span>案件文书</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'knowledge' ? 'active' : ''}`}
          onClick={() => setActiveTab('knowledge')}
        >
          <FileTextIcon size={16} />
          <span>案件知识库</span>
        </button>
      </div>

      {/* Tab 1: Info & Edit */}
      {activeTab === 'info' && (
        <Card title="案件基础信息" subtitle="维护案件审级、案由、争议诉求与进展阶段">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleUpdate();
            }}
          >
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
              <div className="form-group">
                <label className="form-label">案件编号</label>
                <input type="text" className="form-input" value={caseInfo.caseNo} disabled />
                <div className="form-hint">案件立案后文号不可随意修改</div>
              </div>

              <div className="form-group">
                <label className="form-label">业务类别</label>
                <input type="text" className="form-input" value={caseInfo.type} disabled />
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
              <div className="form-group">
                <label className="form-label" htmlFor="editFiledOn">
                  立案日期
                </label>
                <input
                  id="editFiledOn"
                  type="date"
                  className="form-input"
                  value={editFiledOn}
                  disabled={caseInfo.status === 'ARCHIVED'}
                  onChange={(e) => setEditFiledOn(e.target.value)}
                />
              </div>
              <div className="form-group">
                <label className="form-label">归档时间</label>
                <p style={{ padding: '10px 0' }}>
                  {caseInfo.archivedAt
                    ? new Date(caseInfo.archivedAt).toLocaleString()
                    : '尚未归档'}
                </p>
              </div>
            </div>
            <div className="form-group">
              <label className="form-label" htmlFor="editTitle">
                案件标题 <span className="required">*</span>
              </label>
              <input
                id="editTitle"
                type="text"
                className="form-input"
                value={editTitle}
                disabled={caseInfo.status === 'ARCHIVED'}
                onChange={(e) => setEditTitle(e.target.value)}
                required
              />
            </div>

            <div className="form-group">
              <label className="form-label" htmlFor="editDesc">
                案情简介及诉求
              </label>
              <textarea
                id="editDesc"
                className="form-textarea"
                rows={5}
                value={editDescription}
                disabled={caseInfo.status === 'ARCHIVED'}
                onChange={(e) => setEditDescription(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">案卷流转状态</label>
              <select
                className="form-select"
                value={editStatus}
                disabled={caseInfo.status === 'ARCHIVED'}
                onChange={(e) => setEditStatus(e.target.value as any)}
                style={{ maxWidth: 280 }}
              >
                <option value="DRAFT">草稿状态</option>
                <option value="ACTIVE">进行中</option>
                <option value="ARCHIVED">已归档</option>
              </select>
              <div className="form-hint">
                办案流程流转：立案草稿进入正式办理；办结案件归档锁定；归档案件支持按权限恢复。
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <Button
                type="submit"
                variant="primary"
                size="md"
                loading={saving}
                disabled={caseInfo.status === 'ARCHIVED'}
              >
                保存变更
              </Button>
            </div>
          </form>
        </Card>
      )}

      {/* Tab 2: Members */}
      {activeTab === 'members' && (
        <Card>
          <CaseMembersTab caseInfo={caseInfo} onCaseUpdated={(updated) => setCaseInfo(updated)} />
        </Card>
      )}

      {/* Tab 3: Files & Evidence */}
      {activeTab === 'files' && (
        <Card
          title="案件文件与证据管理"
          subtitle="上传及归档涉案证据材料、诉讼文书、答辩状及判决文件"
        >
          <CaseFilesTab caseInfo={caseInfo} />
        </Card>
      )}

      {activeTab === 'evidence' && (
        <Card title="证据目录" subtitle="记录原件来源、收集时间、说明和排列顺序">
          <CaseEvidenceTab key={caseInfo.id} caseInfo={caseInfo} />
        </Card>
      )}
      {/* Tab 4: Documents & AI */}
      {activeTab === 'documents' && (
        <Card title="案件文书" subtitle="起草文书、保存与恢复历史版本、导出已保存内容">
          <CaseDocumentsTab caseInfo={caseInfo} />
        </Card>
      )}
      {activeTab === 'knowledge' && (
        <Card title="案件知识库" subtitle="整理本案件资料，按权限检索原文并查看页码或段落来源">
          <CaseKnowledgeTab caseInfo={caseInfo} />
        </Card>
      )}
      {activeTab === 'analysis' && (
        <Card title="案件材料分析" subtitle="事实、待核实事项、争议、证据引用及办案建议边界">
          <CaseAnalysisTab key={caseInfo.id} caseInfo={caseInfo} />
        </Card>
      )}
    </div>
  );
};
