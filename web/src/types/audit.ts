export interface AuditLogView {
  id: string;
  action: string;
  objectType: string | null;
  objectId: string | null;
  result: string;
  actorId: string | null;
  actorName: string | null;
  traceId: string;
  createdAt: string;
  detail: Record<string, unknown> | null;
}

export interface AuditPage {
  items: AuditLogView[];
  total: number;
  page: number;
  size: number;
}
