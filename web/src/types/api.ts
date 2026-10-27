export interface ApiResponse<T = any> {
  code: number;
  message: string;
  data: T;
  traceId: string;
}

export interface ApiError {
  status: number;
  message: string;
  traceId?: string;
}
