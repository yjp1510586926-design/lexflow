import React from 'react';
import {
  ScaleIcon,
  LayoutDashboardIcon,
  BriefcaseIcon,
  UsersIcon,
  FileTextIcon,
  SparklesIcon,
  LogOutIcon,
  ShieldIcon,
  ActivityIcon,
} from '../common/Icons';
import { useAuth } from '../../context/AuthContext';

export type NavTab =
  | 'dashboard'
  | 'cases'
  | 'organization'
  | 'documents'
  | 'tasks'
  | 'tenant-knowledge'
  | 'ai-studio'
  | 'ai-settings';

interface SidebarProps {
  currentTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ currentTab, onSelectTab }) => {
  const { user, logout } = useAuth();

  const navItems: { id: NavTab; label: string; icon: React.ReactNode; badge?: string }[] = [
    {
      id: 'dashboard',
      label: '工作台',
      icon: <LayoutDashboardIcon size={19} />,
    },
    {
      id: 'cases',
      label: '案件',
      icon: <BriefcaseIcon size={19} />,
    },
    {
      id: 'organization',
      label: '成员与权限',
      icon: <UsersIcon size={19} />,
    },
    {
      id: 'documents',
      label: '文书',
      icon: <FileTextIcon size={19} />,
    },
    {
      id: 'tasks',
      label: '任务记录',
      icon: <ActivityIcon size={19} />,
    },
    {
      id: 'tenant-knowledge',
      label: '律所知识库',
      icon: <FileTextIcon size={19} />,
    },
    {
      id: 'ai-studio',
      label: 'AI 助手',
      icon: <SparklesIcon size={19} />,
    },
    { id: 'ai-settings', label: 'AI 配置', icon: <ShieldIcon size={19} /> },
  ];

  return (
    <aside className="app-sidebar">
      <div className="sidebar-brand">
        <div className="brand-icon-box">
          <ScaleIcon size={24} className="brand-icon" />
        </div>
        <div className="brand-text-box">
          <span className="brand-title">LexFlow</span>
          <span className="brand-subtitle">法律业务与 AI 协作平台</span>
        </div>
      </div>

      <nav className="sidebar-nav">
        <div className="nav-section-title">核心业务</div>
        {navItems.slice(0, 3).map((item) => (
          <button
            key={item.id}
            className={`nav-item ${currentTab === item.id ? 'active' : ''}`}
            onClick={() => onSelectTab(item.id)}
          >
            <span className="nav-item-icon">{item.icon}</span>
            <span className="nav-item-label">{item.label}</span>
          </button>
        ))}

        <div className="nav-section-title">协作与智能扩展</div>
        {navItems.slice(3).map((item) => (
          <button
            key={item.id}
            className={`nav-item ${currentTab === item.id ? 'active' : ''}`}
            onClick={() => onSelectTab(item.id)}
          >
            <span className="nav-item-icon">{item.icon}</span>
            <span className="nav-item-label">{item.label}</span>
            {item.badge && <span className="nav-item-tag">{item.badge}</span>}
          </button>
        ))}
      </nav>

      <div className="sidebar-footer">
        <div className="user-profile-card">
          <div className="user-avatar">
            <ShieldIcon size={18} />
          </div>
          <div className="user-info">
            <span className="user-name" title={user?.name}>
              {user?.name || '未知用户'}
            </span>
            <span className="user-tenant">
              机构 #{user?.tenantId} · 工号 #{user?.id}
            </span>
          </div>
          <button
            className="user-logout-btn"
            onClick={logout}
            title="退出登录"
            aria-label="退出登录"
          >
            <LogOutIcon size={16} />
          </button>
        </div>
      </div>
    </aside>
  );
};
