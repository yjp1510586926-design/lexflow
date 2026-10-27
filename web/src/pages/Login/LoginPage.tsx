import React, { useState } from 'react';
import { ScaleIcon, ShieldIcon, LockIcon, AlertCircleIcon } from '../../components/common/Icons';
import { Button } from '../../components/common/Button';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../components/common/Toast';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();
  const toast = useToast();

  const [tenantId, setTenantId] = useState<string>('');
  const [username, setUsername] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);

    const parsedTenantId = parseInt(tenantId, 10);
    if (isNaN(parsedTenantId) || parsedTenantId <= 0) {
      setErrorMsg('请输入有效的律所编号（正整数）');
      return;
    }
    if (!username.trim()) {
      setErrorMsg('请输入用户名');
      return;
    }
    if (!password) {
      setErrorMsg('请输入密码');
      return;
    }

    setLoading(true);
    try {
      await login({
        tenantId: parsedTenantId,
        username: username.trim(),
        password,
      });
      toast.success('登录成功，欢迎使用 LexFlow');
    } catch (err: any) {
      const msg = err.message || '登录失败，请核对租户标识、用户名或密码';
      setErrorMsg(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card-container">
        {/* Brand Header */}
        <div className="login-brand-header">
          <div className="login-emblem">
            <ScaleIcon size={22} strokeWidth={2} />
          </div>
          <h1 className="login-app-title">LexFlow 律所业务协作平台</h1>
          <p className="login-app-subtitle">管理案件、协作办案、整理材料</p>
        </div>

        {errorMsg && (
          <div className="login-error-banner" role="alert">
            <AlertCircleIcon size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="tenantId">
              律所编号 <span className="required">*</span>
            </label>
            <div className="input-with-icon">
              <ShieldIcon size={15} className="input-icon" />
              <input
                id="tenantId"
                type="number"
                min="1"
                className="form-input"
                placeholder="例如: 1"
                value={tenantId}
                onChange={(e) => setTenantId(e.target.value)}
                required
              />
            </div>
            <div className="form-hint">请向律所系统管理员获取所属机构的租户编号</div>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="username">
              用户名 <span className="required">*</span>
            </label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              className="form-input"
              placeholder="请输入您的律所账号"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="password">
              密码 <span className="required">*</span>
            </label>
            <div className="input-with-icon">
              <LockIcon size={15} className="input-icon" />
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                className="form-input"
                placeholder="请输入登录密码"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
          </div>

          <Button
            type="submit"
            variant="primary"
            size="lg"
            loading={loading}
            className="login-submit-btn"
          >
            登 录
          </Button>
        </form>

        <div className="login-security-footer">
          <div>请使用你的律所账号登录</div>
          <div style={{ marginTop: 4 }}>如需重置密码或开通执业账号，请联系本所系统管理员</div>
        </div>
      </div>
    </div>
  );
};
