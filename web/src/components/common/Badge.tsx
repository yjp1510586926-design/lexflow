import React from 'react';
import { CaseStatus, CaseMemberRole } from '../../types/case';
import { OrgUserRole, OrgUserStatus } from '../../types/organization';

type BadgeType = 'status' | 'role' | 'org-role' | 'org-status' | 'custom';

interface BadgeProps {
  type?: BadgeType;
  value?: CaseStatus | CaseMemberRole | OrgUserRole | OrgUserStatus | string;
  variant?: 'blue' | 'emerald' | 'amber' | 'purple' | 'slate' | 'rose';
  children?: React.ReactNode;
  className?: string;
}

const statusConfig: Record<CaseStatus, { label: string; variant: string }> = {
  DRAFT: { label: '草稿', variant: 'slate' },
  ACTIVE: { label: '进行中', variant: 'emerald' },
  ARCHIVED: { label: '已归档', variant: 'amber' },
};

const caseRoleConfig: Record<CaseMemberRole, { label: string; variant: string }> = {
  OWNER: { label: '负责人', variant: 'purple' },
  EDITOR: { label: '协作', variant: 'blue' },
  READER: { label: '只读', variant: 'slate' },
};

const orgRoleConfig: Record<OrgUserRole, { label: string; variant: string }> = {
  TENANT_ADMIN: { label: '律所管理员', variant: 'purple' },
  LAWYER: { label: '主办律师', variant: 'blue' },
  READER: { label: '只读人员', variant: 'slate' },
};

const orgStatusConfig: Record<OrgUserStatus, { label: string; variant: string }> = {
  ACTIVE: { label: '正常', variant: 'emerald' },
  DISABLED: { label: '已停用', variant: 'rose' },
};

export const Badge: React.FC<BadgeProps> = ({
  type = 'custom',
  value,
  variant,
  children,
  className = '',
}) => {
  let label = children;
  let colorVariant = variant || 'slate';

  if (type === 'status' && value && value in statusConfig) {
    const item = statusConfig[value as CaseStatus];
    label = item.label;
    colorVariant = item.variant as any;
  } else if (type === 'role' && value && value in caseRoleConfig) {
    const item = caseRoleConfig[value as CaseMemberRole];
    label = item.label;
    colorVariant = item.variant as any;
  } else if (type === 'org-role' && value && value in orgRoleConfig) {
    const item = orgRoleConfig[value as OrgUserRole];
    label = item.label;
    colorVariant = item.variant as any;
  } else if (type === 'org-status' && value && value in orgStatusConfig) {
    const item = orgStatusConfig[value as OrgUserStatus];
    label = item.label;
    colorVariant = item.variant as any;
  }

  return <span className={`badge badge-${colorVariant} ${className}`}>{label}</span>;
};
