export interface Identity {
  id: string;
  tenantId: string;
  name: string;
}

export interface LoginParams {
  username: string;
  password: string;
  tenantId: number;
}

export interface CsrfTokenData {
  headerName: string;
  token: string;
}
