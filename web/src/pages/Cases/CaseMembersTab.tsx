import React, { useState, useEffect, useCallback } from 'react';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { useToast } from '../../components/common/Toast';
import {
  UsersIcon,
  PlusIcon,
  TrashIcon,
  UserCheckIcon,
  EditIcon,
} from '../../components/common/Icons';
import {
  getCaseMembers,
  addCaseMember,
  removeCaseMember,
  changeMemberRole,
  transferCaseOwner,
  getTenantUsers,
} from '../../api/cases';
import { CaseMemberView, TenantUserView, CaseView } from '../../types/case';

interface CaseMembersTabProps {
  caseInfo: CaseView;
  onCaseUpdated: (updated: CaseView) => void;
}

export const CaseMembersTab: React.FC<CaseMembersTabProps> = ({ caseInfo, onCaseUpdated }) => {
  const toast = useToast();
  const [members, setMembers] = useState<CaseMemberView[]>([]);
  const [loading, setLoading] = useState<boolean>(true);

  // Add Member Modal
  const [addModalOpen, setAddModalOpen] = useState<boolean>(false);
  const [tenantUsers, setTenantUsers] = useState<TenantUserView[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string>('');
  const [selectedRole, setSelectedRole] = useState<'EDITOR' | 'READER'>('EDITOR');
  const [submittingAdd, setSubmittingAdd] = useState<boolean>(false);

  // Transfer Owner Modal
  const [transferModalOpen, setTransferModalOpen] = useState<boolean>(false);
  const [newOwnerId, setNewOwnerId] = useState<string>('');
  const [submittingTransfer, setSubmittingTransfer] = useState<boolean>(false);

  const fetchMembers = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getCaseMembers(caseInfo.id);
      setMembers(data);
    } catch (err: any) {
      toast.error(err.message || '获取案件成员失败');
    } finally {
      setLoading(false);
    }
  }, [caseInfo.id, toast]);

  useEffect(() => {
    fetchMembers();
  }, [fetchMembers]);

  const openAddModal = async () => {
    try {
      const users = await getTenantUsers();
      setTenantUsers(users);
      if (users.length > 0) {
        setSelectedUserId(users[0].id);
      }
      setAddModalOpen(true);
    } catch (err: any) {
      toast.error(err.message || '获取律所用户列表失败');
    }
  };

  const handleAddMember = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedUserId) return;

    setSubmittingAdd(true);
    try {
      await addCaseMember(caseInfo.id, {
        userId: parseInt(selectedUserId, 10),
        role: selectedRole,
      });
      toast.success('成员添加成功');
      setAddModalOpen(false);
      fetchMembers();
    } catch (err: any) {
      toast.error(err.message || '添加成员失败');
    } finally {
      setSubmittingAdd(false);
    }
  };

  const handleRemoveMember = async (userId: string, name: string) => {
    if (!window.confirm(`确定要将成员 [${name}] 从当前案件中移除吗？`)) return;

    try {
      await removeCaseMember(caseInfo.id, userId);
      toast.success(`已成功移除成员 [${name}]`);
      fetchMembers();
    } catch (err: any) {
      toast.error(err.message || '移除成员失败');
    }
  };

  const handleChangeRole = async (userId: string, currentRole: string) => {
    const nextRole = currentRole === 'EDITOR' ? 'READER' : 'EDITOR';
    try {
      await changeMemberRole(caseInfo.id, userId, { role: nextRole });
      toast.success(`角色已调整为 ${nextRole === 'EDITOR' ? '协作 (EDITOR)' : '只读 (READER)'}`);
      fetchMembers();
    } catch (err: any) {
      toast.error(err.message || '调整成员角色失败');
    }
  };

  const openTransferModal = async () => {
    try {
      const users = await getTenantUsers();
      // Filter out the current owner
      const eligibleUsers = users.filter((u) => u.id !== caseInfo.ownerId);
      setTenantUsers(eligibleUsers);
      if (eligibleUsers.length > 0) {
        setNewOwnerId(eligibleUsers[0].id);
      }
      setTransferModalOpen(true);
    } catch (err: any) {
      toast.error(err.message || '获取律所用户列表失败');
    }
  };

  const handleTransferOwner = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newOwnerId) return;

    setSubmittingTransfer(true);
    try {
      const updated = await transferCaseOwner(caseInfo.id, {
        userId: parseInt(newOwnerId, 10),
        version: caseInfo.version,
      });
      toast.success('案件负责人原子转交成功，锁版本已递增');
      onCaseUpdated(updated);
      setTransferModalOpen(false);
      fetchMembers();
    } catch (err: any) {
      toast.error(err.message || '转交负责人失败');
    } finally {
      setSubmittingTransfer(false);
    }
  };

  const isArchived = caseInfo.status === 'ARCHIVED';

  return (
    <div className="case-members-tab">
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div>
          <h4 style={{ fontSize: 16, fontWeight: 700, color: 'var(--navy-900)' }}>
            办案团队与权限分派 ({members.length})
          </h4>
          <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
            仅授权办案团队成员有权查阅及办理本案，主办律师享有成员指派与文书管理权。
          </p>
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          <Button
            variant="secondary"
            size="sm"
            disabled={isArchived}
            icon={<UserCheckIcon size={15} />}
            onClick={openTransferModal}
            title={isArchived ? '归档案件不可变更主办律师' : '转交主办律师'}
          >
            转交主办律师
          </Button>

          <Button
            variant="primary"
            size="sm"
            disabled={isArchived}
            icon={<PlusIcon size={15} />}
            onClick={openAddModal}
            title={isArchived ? '归档案件不可添加成员' : '添加团队成员'}
          >
            添加成员
          </Button>
        </div>
      </div>

      {isArchived && (
        <div
          style={{
            padding: '10px 14px',
            backgroundColor: 'var(--bg-subtle)',
            borderRadius: 'var(--radius-md)',
            border: '1px solid var(--border-default)',
            fontSize: 12,
            color: 'var(--text-secondary)',
            marginBottom: 16,
          }}
        >
          <strong>提示：</strong>
          当前案件处于已归档锁定状态，案卷材料及成员权限默认只读保护。如需调整人员，请先在基本信息中恢复案件办理。
        </div>
      )}

      {loading ? (
        <Spinner text="正在加载办案团队..." />
      ) : (
        <div className="table-responsive">
          <table className="data-table">
            <thead>
              <tr>
                <th>人员工号</th>
                <th>成员姓名</th>
                <th>案件角色</th>
                <th style={{ textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {members.map((m) => {
                const isOwner = m.role === 'OWNER';
                return (
                  <tr key={m.id}>
                    <td>
                      <span
                        style={{
                          fontFamily: 'monospace',
                          fontSize: 12,
                          color: 'var(--text-muted)',
                        }}
                      >
                        #{m.id}
                      </span>
                    </td>
                    <td style={{ fontWeight: 600 }}>{m.name}</td>
                    <td>
                      <Badge type="role" value={m.role} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {isOwner ? (
                        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                          主办律师 (不可直接移除)
                        </span>
                      ) : (
                        <div style={{ display: 'inline-flex', gap: 8 }}>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={isArchived}
                            icon={<EditIcon size={13} />}
                            onClick={() => handleChangeRole(m.id, m.role)}
                            title="切换编辑/只读权限"
                          >
                            设为 {m.role === 'EDITOR' ? '只读' : '协作'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={isArchived}
                            icon={<TrashIcon size={13} />}
                            style={{ color: 'var(--danger-text)' }}
                            onClick={() => handleRemoveMember(m.id, m.name)}
                          >
                            移除
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Add Member Modal */}
      <Modal
        isOpen={addModalOpen}
        onClose={() => setAddModalOpen(false)}
        title="指派办案团队成员"
        subtitle="从本所执业律师及辅助人员中选择成员加入本案"
        maxWidth="sm"
        footer={
          <>
            <Button variant="ghost" size="md" onClick={() => setAddModalOpen(false)}>
              取消
            </Button>
            <Button variant="primary" size="md" loading={submittingAdd} onClick={handleAddMember}>
              确定添加
            </Button>
          </>
        }
      >
        <form onSubmit={handleAddMember}>
          <div className="form-group">
            <label className="form-label" htmlFor="memberUser">
              选择本所执业人员 <span className="required">*</span>
            </label>
            <select
              id="memberUser"
              className="form-select"
              value={selectedUserId}
              onChange={(e) => setSelectedUserId(e.target.value)}
              required
            >
              {tenantUsers.map((u) => {
                const alreadyAdded = members.some((m) => m.id === u.id);
                return (
                  <option key={u.id} value={u.id} disabled={alreadyAdded}>
                    {u.name} (工号 #{u.id}) {alreadyAdded ? '— 已加入' : ''}
                  </option>
                );
              })}
            </select>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="memberRole">
              分配办案职责 <span className="required">*</span>
            </label>
            <select
              id="memberRole"
              className="form-select"
              value={selectedRole}
              onChange={(e) => setSelectedRole(e.target.value as any)}
            >
              <option value="EDITOR">协作律师 / 助理 — 可查阅并协同起草修改案件文书</option>
              <option value="READER">只读成员 — 仅允许查阅案件卷宗与材料</option>
            </select>
          </div>
        </form>
      </Modal>

      {/* Transfer Owner Modal */}
      <Modal
        isOpen={transferModalOpen}
        onClose={() => setTransferModalOpen(false)}
        title="转交案件主办律师"
        subtitle="转交后新主办律师总揽案卷管理权，原主办律师自动调整为协作成员"
        maxWidth="md"
        footer={
          <>
            <Button variant="ghost" size="md" onClick={() => setTransferModalOpen(false)}>
              取消
            </Button>
            <Button
              variant="primary"
              size="md"
              loading={submittingTransfer}
              onClick={handleTransferOwner}
            >
              确认转交主办权
            </Button>
          </>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            案件修订版本当前为 <strong>v{caseInfo.version}</strong>
            。转交主办律师将更新案件经办责任并记录合规审计。
          </p>
        </div>

        <form onSubmit={handleTransferOwner}>
          <div className="form-group">
            <label className="form-label" htmlFor="newOwner">
              选择新主办律师 <span className="required">*</span>
            </label>
            <select
              id="newOwner"
              className="form-select"
              value={newOwnerId}
              onChange={(e) => setNewOwnerId(e.target.value)}
              required
            >
              {tenantUsers.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name} (UID #{u.id})
                </option>
              ))}
            </select>
            <div className="form-hint">
              新主办律师必须为本所执业人员，若尚未加入案卷团队将自动移入。
            </div>
          </div>
        </form>
      </Modal>
    </div>
  );
};
