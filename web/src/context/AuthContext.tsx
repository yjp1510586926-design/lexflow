import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { Identity, LoginParams } from '../types/auth';
import { getMe, login as apiLogin, logout as apiLogout } from '../api/auth';

interface AuthContextType {
  user: Identity | null;
  loading: boolean;
  login: (params: LoginParams) => Promise<void>;
  logout: () => Promise<void>;
  checkSession: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<Identity | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  const checkSession = useCallback(async () => {
    // 身份以服务端 HttpSession 为准，启动时读取 /me，避免从本地持久化恢复过期身份。
    try {
      const identity = await getMe();
      setUser(identity);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    checkSession();
  }, [checkSession]);

  const handleLogin = async (params: LoginParams) => {
    const identity = await apiLogin(params);
    setUser(identity);
  };

  const handleLogout = async () => {
    try {
      await apiLogout();
    } catch {
      // 退出请求失败时仍在本页面清除身份状态，服务端会话状态由后续会话检查确认。
    } finally {
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        login: handleLogin,
        logout: handleLogout,
        checkSession,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
