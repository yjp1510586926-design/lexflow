import React, { useState, useEffect } from 'react';
import { ActivityIcon, RefreshCwIcon } from '../common/Icons';
import { getHealth, HealthStatus } from '../../api/system';
import { useAuth } from '../../context/AuthContext';

interface HeaderProps {
  title: string;
  subtitle?: string;
  onRefresh?: () => void;
  isRefreshing?: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  title,
  subtitle,
  onRefresh,
  isRefreshing = false,
}) => {
  const { user } = useAuth();
  const [health, setHealth] = useState<HealthStatus | null>(null);
  const [checkingHealth, setCheckingHealth] = useState<boolean>(false);

  const checkStatus = async () => {
    setCheckingHealth(true);
    try {
      const res = await getHealth();
      setHealth(res);
    } catch {
      setHealth({ status: 'DOWN' });
    } finally {
      setCheckingHealth(false);
    }
  };

  useEffect(() => {
    checkStatus();
    const timer = setInterval(checkStatus, 30000);
    return () => clearInterval(timer);
  }, []);

  const isUp = health?.status === 'UP';

  return (
    <header className="app-header">
      <div className="header-left">
        <h1 className="header-title">{title}</h1>
        {subtitle && <p className="header-subtitle">{subtitle}</p>}
      </div>

      <div className="header-right">
        {onRefresh && (
          <button
            className={`btn btn-ghost btn-sm ${isRefreshing ? 'is-loading' : ''}`}
            onClick={onRefresh}
            title="刷新当前数据"
            aria-label="刷新当前数据"
          >
            <RefreshCwIcon size={16} />
            <span className="btn-label">刷新</span>
          </button>
        )}

        <div
          className={`health-badge ${isUp ? 'health-up' : 'health-down'}`}
          onClick={checkStatus}
          title={isUp ? '律所协同服务运行正常 (点击重新检测)' : '云端协同服务无法连接或未就绪'}
          role="button"
          tabIndex={0}
        >
          <span className="health-dot" />
          <ActivityIcon size={14} className="health-icon" />
          <span className="health-label">
            {checkingHealth ? '检测中…' : isUp ? '服务在线' : '离线/异常'}
          </span>
        </div>

        <div className="tenant-chip">
          <span className="tenant-chip-label">机构</span>
          <span className="tenant-chip-value">#{user?.tenantId}</span>
        </div>
      </div>
    </header>
  );
};
