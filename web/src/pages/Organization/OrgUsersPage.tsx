import React, { useState, useEffect, useCallback } from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  UsersIcon,
  PlusIcon,
  ShieldIcon,
  LockIcon,
  EditIcon,
  AlertCircleIcon,
} from '../../components/common/Icons';
import { listOrgUsers, createOrgUser, updateOrgUser } from '../../api/organization';
import {
  OrgUserView,
  OrgUserRole,
  OrgUserStatus,
  CreateOrgUserParams,
} from '../../types/organization';

export const OrgUsersPage: React.FC = () => {
  const toast = useToast();
  const [users, setUsers] = useState<OrgUserView[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [denied, setDenied] = useState<boolean>(false);

  // Create User Modal
  const [createModalOpen, setCreateModalOpen] = useState<boolean>(false);
  const [createForm, setCreateForm] = useState<CreateOrgUserParams>({
    username: '',
    password: '',
    name: '',
    role: 'LAWYER',
  });
  const [creating, setCreating] = useState<boolean>(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit User Modal
  const [editModalOpen, setEditModalOpen] = useState<boolean>(false);
  const [editingUser, setEditingUser] = useState<OrgUserView | null>(null);
  const [editRole, setEditRole] = useState<OrgUserRole>('LAWYER');
  const [editStatus, setEditStatus] = useState<OrgUserStatus>('ACTIVE');
  const [savingEdit, setSavingEdit] = useState<boolean>(false);

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    setDenied(false);
    try {
      const data = await listOrgUsers();
      setUsers(data);
    } catch (err: any) {
      if (err.status === 403) {
        setDenied(true);
      } else {
        toast.error(err.message || '获取律所成员列表失败');
      }
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateError(null);

    if (!createForm.username.trim()) {
      setCreateError('请输入用户名');
      return;
    }
    if (!createForm.password || createForm.password.length < 12) {
      setCreateError('密码长度至少为 12 个字符');
      return;
    }
    if (new TextEncoder().encode(createForm.password).length > 72) {
      setCreateError('密码 UTF-8 编码不得超过 72 字节');
      return;
    }
    if (!createForm.name.trim()) {
      setCreateError('请输入成员显示姓名');
      return;
    }

    setCreating(true);
    try {
      await createOrgUser({
        username: createForm.username.trim(),
        password: createForm.password,
        name: createForm.name.trim(),
        role: createForm.role,
      });
      toast.success(`律所成员 [${createForm.name}] 创建成功`);
      setCreateModalOpen(false);
      setCreateForm({
        username: '',
        password: '',
        name: '',
        role: 'LAWYER',
      });
      fetchUsers();
    } catch (err: any) {
      setCreateError(err.message || '创建成员失败');
    } finally {
      setCreating(false);
    }
  };

  const openEditModal = (user: OrgUserView) => {
    setEditingUser(user);
    setEditRole(user.role);
    setEditStatus(user.status);
    setEditModalOpen(true);
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingUser) return;

    setSavingEdit(true);
    try {
      await updateOrgUser(editingUser.id, {
        status: editStatus,
        role: editRole,
      });
      toast.success(`成员 [${editingUser.name}] 权限与状态更新成功`);
      setEditModalOpen(false);
      fetchUsers();
    } catch (err: any) {
      toast.error(err.message || '更新成员失败');
    } finally {
      setSavingEdit(false);
    }
  };

  if (denied) {
    return (
      <div className="empty-state">
        <div
          className="empty-state-icon"
          style={{ backgroundColor: 'var(--danger-bg)', color: 'var(--danger-text)' }}
        >
          <ShieldIcon size={32} />
        </div>
        <h4 className="empty-state-title">无权访问组织架构与人员管理</h4>
        <p className="empty-state-description">
          您当前登录账号不具备律所人员管理权限。本模块仅限律所系统管理员（TENANT_ADMIN）访问与维护。若需新增人员或调整权限，请联系管理员协助。
        </p>
      </div>
    );
  }

  return (
    <div className="org-users-page">
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 20,
          flexWrap: 'wrap',
          gap: 16,
        }}
      >
        <div>
          <h2 style={{ fontSize: 20, fontWeight: 700, color: 'var(--navy-900)' }}>
            律所成员与角色授权
          </h2>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 2 }}>
            本租户内所有律师与协作人员账号；支持分配律所管理员、律师及只读权限。
          </p>
        </div>

        <Button
          variant="primary"
          size="md"
          icon={<PlusIcon size={16} />}
          onClick={() => setCreateModalOpen(true)}
        >
          添加律所成员
        </Button>
      </div>

      <Card>
        {loading ? (
          <Spinner text="正在加载组织人员数据..." />
        ) : (
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th style={{ width: '12%' }}>用户标识</th>
                  <th style={{ width: '22%' }}>用户名 (账号)</th>
                  <th style={{ width: '22%' }}>显示姓名</th>
                  <th style={{ width: '16%' }}>系统角色</th>
                  <th style={{ width: '14%' }}>账号状态</th>
                  <th style={{ width: '14%', textAlign: 'right' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td>
                      <span
                        style={{
                          fontFamily: 'monospace',
                          fontSize: 12,
                          color: 'var(--text-muted)',
                        }}
                      >
                        UID #{u.id}
                      </span>
                    </td>
                    <td style={{ fontWeight: 600 }}>{u.username}</td>
                    <td>{u.name}</td>
                    <td>
                      <Badge type="org-role" value={u.role} />
                    </td>
                    <td>
                      <Badge type="org-status" value={u.status} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={<EditIcon size={14} />}
                        onClick={() => openEditModal(u)}
                      >
                        编辑权限
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Create User Modal */}
      <Modal
        isOpen={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        title="添加律所新成员"
        subtitle="为本律所执业律师及辅助人员开设独立办公账号"
        maxWidth="md"
        footer={
          <>
            <Button variant="ghost" size="md" onClick={() => setCreateModalOpen(false)}>
              取消
            </Button>
            <Button variant="primary" size="md" loading={creating} onClick={handleCreateSubmit}>
              确认创建
            </Button>
          </>
        }
      >
        {createError && (
          <div className="login-error-banner" style={{ marginBottom: 16 }}>
            <AlertCircleIcon size={16} />
            <span>{createError}</span>
          </div>
        )}

        <form onSubmit={handleCreateSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="newUsername">
              登录用户名 <span className="required">*</span>
            </label>
            <input
              id="newUsername"
              type="text"
              className="form-input"
              placeholder="例如: zhang.san"
              value={createForm.username}
              onChange={(e) => setCreateForm({ ...createForm, username: e.target.value })}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="newPassword">
              初始密码 <span className="required">*</span>
            </label>
            <input
              id="newPassword"
              type="password"
              className="form-input"
              placeholder="至少 12 位安全字符"
              value={createForm.password}
              onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })}
              required
            />
            <div className="form-hint">
              安全密码要求：至少 12 位字符，建议包含大小写字母与数字。
            </div>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="newName">
              成员姓名 (Display Name) <span className="required">*</span>
            </label>
            <input
              id="newName"
              type="text"
              className="form-input"
              placeholder="例如: 张三 律师"
              value={createForm.name}
              onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="newRole">
              分配系统角色 <span className="required">*</span>
            </label>
            <select
              id="newRole"
              className="form-select"
              value={createForm.role}
              onChange={(e) => setCreateForm({ ...createForm, role: e.target.value as any })}
            >
              <option value="LAWYER">主办律师 (LAWYER) — 拥有案件立案、编辑与业务执行权限</option>
              <option value="TENANT_ADMIN">
                律所管理员 (TENANT_ADMIN) — 具备组织成员及本律所全量权限
              </option>
              <option value="READER">只读成员 (READER) — 仅可查看被授权案件，禁止写入修改</option>
            </select>
          </div>
        </form>
      </Modal>

      {/* Edit User Modal */}
      <Modal
        isOpen={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        title="编辑成员角色与状态"
        subtitle={`用户: ${editingUser?.name} (${editingUser?.username})`}
        maxWidth="sm"
        footer={
          <>
            <Button variant="ghost" size="md" onClick={() => setEditModalOpen(false)}>
              取消
            </Button>
            <Button variant="primary" size="md" loading={savingEdit} onClick={handleEditSubmit}>
              保存修改
            </Button>
          </>
        }
      >
        <form onSubmit={handleEditSubmit}>
          <div className="form-group">
            <label className="form-label">账号状态</label>
            <select
              className="form-select"
              value={editStatus}
              onChange={(e) => setEditStatus(e.target.value as any)}
            >
              <option value="ACTIVE">正常活跃 (ACTIVE)</option>
              <option value="DISABLED">已停用禁用 (DISABLED)</option>
            </select>
            <div className="form-hint">
              停用后用户旧会话立即失效，且不能再登录；最后一名有效管理员不可被禁用。
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">分配角色</label>
            <select
              className="form-select"
              value={editRole}
              onChange={(e) => setEditRole(e.target.value as any)}
            >
              <option value="LAWYER">主办律师 (LAWYER)</option>
              <option value="TENANT_ADMIN">律所管理员 (TENANT_ADMIN)</option>
              <option value="READER">只读成员 (READER)</option>
            </select>
            <div className="form-hint">最后一名管理员不能被降级为非管理员。</div>
          </div>
        </form>
      </Modal>
    </div>
  );
};
