import React, { useState, useEffect } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Spinner } from '../../components/common/Spinner';
import {
  BriefcaseIcon,
  UsersIcon,
  ShieldIcon,
  SparklesIcon,
  PlusIcon,
  ArrowRightIcon,
  FileTextIcon,
  CheckIcon,
} from '../../components/common/Icons';
import { listCases } from '../../api/cases';
import { getTenantUsers } from '../../api/cases';
import { CaseView } from '../../types/case';
import { useAuth } from '../../context/AuthContext';
import { NavTab } from '../../components/layout/Sidebar';

interface DashboardPageProps {
  onNavigate: (tab: NavTab) => void;
  onOpenCaseDetail: (caseId: string) => void;
  onOpenCreateCase: () => void;
}

export const DashboardPage: React.FC<DashboardPageProps> = ({
  onNavigate,
  onOpenCaseDetail,
  onOpenCreateCase,
}) => {
  const { user } = useAuth();
  const [loading, setLoading] = useState<boolean>(true);
  const [recentCases, setRecentCases] = useState<CaseView[]>([]);
  const [totalCases, setTotalCases] = useState<number>(0);
  const [activeCases, setActiveCases] = useState<number>(0);
  const [draftCases, setDraftCases] = useState<number>(0);
  const [memberCount, setMemberCount] = useState<number>(0);

  const loadData = async () => {
    setLoading(true);
    try {
      const [allCasesRes, activeRes, draftRes, usersRes] = await Promise.allSettled([
        listCases({ page: 1, size: 5 }),
        listCases({ status: 'ACTIVE', size: 1 }),
        listCases({ status: 'DRAFT', size: 1 }),
        getTenantUsers(),
      ]);

      if (allCasesRes.status === 'fulfilled') {
        setRecentCases(allCasesRes.value.items);
        setTotalCases(allCasesRes.value.total);
      }
      if (activeRes.status === 'fulfilled') {
        setActiveCases(activeRes.value.total);
      }
      if (draftRes.status === 'fulfilled') {
        setDraftCases(draftRes.value.total);
      }
      if (usersRes.status === 'fulfilled') {
        setMemberCount(usersRes.value.length);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  return (
    <div className="dashboard-page">
      {/* Precision Metric Cards */}
      <div className="dashboard-metrics-grid">
        <div className="metric-card">
          <div className="metric-card-top">
            <span className="metric-label">可访问案件总数</span>
            <BriefcaseIcon size={16} className="metric-icon-small" />
          </div>
          <div className="metric-value">{totalCases}</div>
          <div className="metric-hint">包含负责及授权参与案件</div>
        </div>

        <div className="metric-card">
          <div className="metric-card-top">
            <span className="metric-label">进行中案卷</span>
            <ShieldIcon size={16} className="metric-icon-small" />
          </div>
          <div className="metric-value">{activeCases}</div>
          <div className="metric-hint">当前活跃法务流程</div>
        </div>

        <div className="metric-card">
          <div className="metric-card-top">
            <span className="metric-label">草稿案卷</span>
            <FileTextIcon size={16} className="metric-icon-small" />
          </div>
          <div className="metric-value">{draftCases}</div>
          <div className="metric-hint">待审核或流转案卷</div>
        </div>

        <div className="metric-card">
          <div className="metric-card-top">
            <span className="metric-label">律所有效成员</span>
            <UsersIcon size={16} className="metric-icon-small" />
          </div>
          <div className="metric-value">{memberCount}</div>
          <div className="metric-hint">本租户在职执业人员</div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 20, marginBottom: 24 }}>
        {/* Recent Cases */}
        <Card
          title="最新案件动态"
          subtitle="本律所您有权访问的最新创建与流转案件"
          actions={
            <Button
              variant="outline"
              size="sm"
              icon={<ArrowRightIcon size={13} />}
              onClick={() => onNavigate('cases')}
            >
              全部案件
            </Button>
          }
        >
          {loading ? (
            <Spinner text="正在查询案件数据..." />
          ) : recentCases.length === 0 ? (
            <div className="empty-state">
              <div className="empty-state-icon">
                <BriefcaseIcon size={24} />
              </div>
              <h4 className="empty-state-title">暂无案件数据</h4>
              <p className="empty-state-description">
                当前律所尚未创建任何案件，您可以立即创建首个案件。
              </p>
              <Button
                variant="primary"
                size="sm"
                icon={<PlusIcon size={14} />}
                onClick={onOpenCreateCase}
              >
                新建首个案件
              </Button>
            </div>
          ) : (
            <div className="table-responsive">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>案件编号</th>
                    <th>案件标题</th>
                    <th>业务类别</th>
                    <th>状态</th>
                    <th>修订版本</th>
                    <th style={{ textAlign: 'right' }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {recentCases.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <span className="detail-case-no">{c.caseNo}</span>
                      </td>
                      <td style={{ fontWeight: 500 }}>{c.title}</td>
                      <td>{c.type}</td>
                      <td>
                        <Badge type="status" value={c.status} />
                      </td>
                      <td>
                        <span
                          style={{
                            fontSize: 11,
                            color: 'var(--text-muted)',
                            fontFamily: 'var(--font-mono)',
                          }}
                        >
                          v{c.version}
                        </span>
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Button variant="ghost" size="sm" onClick={() => onOpenCaseDetail(c.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {/* Quick Operations & Session Info */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Card title="核心业务入口" subtitle="案件与组织操作">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Button
                variant="primary"
                size="md"
                icon={<PlusIcon size={15} />}
                onClick={onOpenCreateCase}
                style={{ justifyContent: 'flex-start' }}
              >
                立案新建案件
              </Button>
              <Button
                variant="secondary"
                size="md"
                icon={<BriefcaseIcon size={15} />}
                onClick={() => onNavigate('cases')}
                style={{ justifyContent: 'flex-start' }}
              >
                管理案件与成员
              </Button>
              <Button
                variant="secondary"
                size="md"
                icon={<UsersIcon size={15} />}
                onClick={() => onNavigate('organization')}
                style={{ justifyContent: 'flex-start' }}
              >
                组织架构与人员
              </Button>
              <Button
                variant="secondary"
                size="md"
                icon={<SparklesIcon size={15} />}
                onClick={() => onNavigate('ai-studio')}
                style={{ justifyContent: 'flex-start' }}
              >
                AI 助手
              </Button>
            </div>
          </Card>

          <Card title="当前执业账号" subtitle="所属机构与权限上下文">
            <div style={{ fontSize: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-muted)' }}>登录用户:</span>
                <strong style={{ color: 'var(--text-main)' }}>{user?.name}</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-muted)' }}>机构编号:</span>
                <span className="tenant-chip-value">#{user?.tenantId}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-muted)' }}>用户标识:</span>
                <span style={{ fontFamily: 'var(--font-mono)' }}>UID {user?.id}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ color: 'var(--text-muted)' }}>数据安全保护:</span>
                <span style={{ color: 'var(--success-text)', fontWeight: 500 }}>
                  行级隔离已启用
                </span>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* Professional Practice & Compliance Guidance */}
      <Card
        title="律所业务协同与数据合规指引"
        subtitle="保障执业合规、利益冲突防范与办案全流程追溯"
      >
        <div className="phase-roadmap-grid">
          <div className="phase-card active-phase">
            <div className="phase-card-header">
              <span className="phase-card-title">立案与案卷管理规范</span>
              <Badge variant="blue">工作流程</Badge>
            </div>
            <ul className="phase-card-items">
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>立案文号全所唯一，支持草稿、进行中与归档流转</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>已归档案卷自动开启防篡改只读保护，需恢复后方可编辑</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>多人并发协作支持版本冲突自动检测，杜绝数据相互覆盖</span>
              </li>
            </ul>
          </div>

          <div className="phase-card">
            <div className="phase-card-header">
              <span className="phase-card-title">主办律师与成员权限隔离</span>
              <Badge variant="slate">权限管控</Badge>
            </div>
            <ul className="phase-card-items">
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>每起案件设置唯一主办律师，享有成员与文书管理权</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>支持添加协作律师（编辑权）或实习/助理人员（只读权）</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>案卷信息仅对授权成员开放，严格执行执业利益冲突隔离</span>
              </li>
            </ul>
          </div>

          <div className="phase-card">
            <div className="phase-card-header">
              <span className="phase-card-title">证据材料与 AI 审查规范</span>
              <Badge variant="slate">安全合规</Badge>
            </div>
            <ul className="phase-card-items">
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>涉案证据支持电子化完整性校验，不可随意篡改或遗失</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>在案件知识库中提问，根据原文回答并查看出处</span>
              </li>
              <li>
                <CheckIcon size={13} style={{ color: '#16a34a' }} />
                <span>合同审查的高风险或缺少依据结果须人工复核后确认</span>
              </li>
            </ul>
          </div>
        </div>
      </Card>
    </div>
  );
};
