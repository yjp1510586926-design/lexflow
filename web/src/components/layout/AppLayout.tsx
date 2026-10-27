import React from 'react';
import { Sidebar, NavTab } from './Sidebar';
import { Header } from './Header';

interface AppLayoutProps {
  currentTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
  title: string;
  subtitle?: string;
  onRefresh?: () => void;
  isRefreshing?: boolean;
  children: React.ReactNode;
}

export const AppLayout: React.FC<AppLayoutProps> = ({
  currentTab,
  onSelectTab,
  title,
  subtitle,
  onRefresh,
  isRefreshing,
  children,
}) => {
  return (
    <div className="app-shell">
      <Sidebar currentTab={currentTab} onSelectTab={onSelectTab} />
      <div className="app-main-wrapper">
        <Header
          title={title}
          subtitle={subtitle}
          onRefresh={onRefresh}
          isRefreshing={isRefreshing}
        />
        <main className="app-content-body">{children}</main>
      </div>
    </div>
  );
};
