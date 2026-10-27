import { AiSettingsPage } from './pages/Settings/AiSettingsPage';
import React, { useState } from 'react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ToastProvider } from './components/common/Toast';
import { LoginPage } from './pages/Login/LoginPage';
import { DashboardPage } from './pages/Dashboard/DashboardPage';
import { CaseListPage } from './pages/Cases/CaseListPage';
import { CaseDetailPage } from './pages/Cases/CaseDetailPage';
import { OrgUsersPage } from './pages/Organization/OrgUsersPage';
import { DocumentsPage } from './pages/Documents/DocumentsPage';
import { TasksPage } from './pages/Tasks/TasksPage';
import { AiStudioPage } from './pages/AiStudio/AiStudioPage';
import { TenantKnowledgePage } from './pages/Knowledge/TenantKnowledgePage';
import { AppLayout } from './components/layout/AppLayout';
import { NavTab } from './components/layout/Sidebar';
import { Spinner } from './components/common/Spinner';

const MainApp: React.FC = () => {
  const { user, loading } = useAuth();
  const [currentTab, setCurrentTab] = useState<NavTab>('dashboard');
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [createCaseModalOpen, setCreateCaseModalOpen] = useState<boolean>(false);
  const [refreshKey, setRefreshKey] = useState<number>(0);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

  if (loading) {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'var(--navy-950)',
          color: '#fff',
        }}
      >
        <Spinner size="lg" text="正在登录..." />
      </div>
    );
  }

  if (!user) {
    return <LoginPage />;
  }

  const handleRefresh = () => {
    setIsRefreshing(true);
    setRefreshKey((k) => k + 1);
    setTimeout(() => setIsRefreshing(false), 500);
  };

  const titles: Record<NavTab, { title: string; subtitle: string }> = {
    dashboard: {
      title: '工作台',
      subtitle: `欢迎回来，${user.name} 律师 · 机构编号 #${user.tenantId}`,
    },
    cases: {
      title: selectedCaseId ? '案件详情' : '案件',
      subtitle: '创建案件、添加成员、整理材料',
    },
    organization: {
      title: '成员与权限',
      subtitle: '管理律所成员及角色',
    },
    documents: {
      title: '文书',
      subtitle: '查看案件文书与历史版本',
    },
    tasks: {
      title: '任务记录',
      subtitle: '查看后台任务进度与失败原因',
    },
    'ai-settings': { title: 'AI 配置', subtitle: '管理本律所的模型服务与 Key' },
    'tenant-knowledge': { title: '律所知识库', subtitle: '共用法律与办案资料，检索原文并核对出处' },
    'ai-studio': {
      title: 'AI 助手',
      subtitle: '合同审查与资料问答',
    },
  };

  return (
    <AppLayout
      currentTab={currentTab}
      onSelectTab={(tab) => {
        setCurrentTab(tab);
        setSelectedCaseId(null);
      }}
      title={titles[currentTab].title}
      subtitle={titles[currentTab].subtitle}
      onRefresh={handleRefresh}
      isRefreshing={isRefreshing}
    >
      {currentTab === 'dashboard' && (
        <DashboardPage
          key={refreshKey}
          onNavigate={(tab) => {
            setCurrentTab(tab);
            setSelectedCaseId(null);
          }}
          onOpenCaseDetail={(caseId) => {
            setSelectedCaseId(caseId);
            setCurrentTab('cases');
          }}
          onOpenCreateCase={() => {
            setCurrentTab('cases');
            setSelectedCaseId(null);
            setCreateCaseModalOpen(true);
          }}
        />
      )}

      {currentTab === 'cases' &&
        (selectedCaseId ? (
          <CaseDetailPage
            key={`${selectedCaseId}-${refreshKey}`}
            caseId={selectedCaseId}
            onBack={() => setSelectedCaseId(null)}
          />
        ) : (
          <CaseListPage
            key={refreshKey}
            onSelectCase={(id) => setSelectedCaseId(id)}
            createModalOpen={createCaseModalOpen}
            onCloseCreateModal={() => setCreateCaseModalOpen(false)}
          />
        ))}

      {currentTab === 'organization' && <OrgUsersPage key={refreshKey} />}

      {currentTab === 'documents' && <DocumentsPage key={refreshKey} />}

      {currentTab === 'tasks' && <TasksPage key={refreshKey} />}

      {currentTab === 'tenant-knowledge' && <TenantKnowledgePage key={refreshKey} />}

      {currentTab === 'ai-settings' && <AiSettingsPage key={refreshKey} />}

      {currentTab === 'ai-studio' && (
        <AiStudioPage
          key={refreshKey}
          onOpenCases={() => setCurrentTab('cases')}
          onOpenTasks={() => setCurrentTab('tasks')}
          onOpenKnowledge={() => setCurrentTab('tenant-knowledge')}
        />
      )}
    </AppLayout>
  );
};

export const App: React.FC = () => {
  return (
    <ToastProvider>
      <AuthProvider>
        <MainApp />
      </AuthProvider>
    </ToastProvider>
  );
};

export default App;
