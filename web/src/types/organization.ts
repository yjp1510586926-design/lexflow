export type OrgUserRole = 'TENANT_ADMIN' | 'LAWYER' | 'READER';
export type OrgUserStatus = 'ACTIVE' | 'DISABLED';

export interface OrgUserView {
  id: string;
  username: string;
  name: string;
  status: OrgUserStatus;
  role: OrgUserRole;
}

export interface CreateOrgUserParams {
  username: string;
  password: string;
  name: string;
  role: OrgUserRole;
}

export interface UpdateOrgUserParams {
  status: OrgUserStatus;
  role: OrgUserRole;
}
