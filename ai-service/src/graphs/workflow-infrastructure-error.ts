/** Only operations outside runOnce may expose a retryable infrastructure classification. */
export class WorkflowInfrastructureError extends Error {
  constructor(
    readonly transient: boolean,
    message: string,
  ) {
    super(message);
  }
}

export function transientInfrastructureFailure(error: unknown): boolean {
  const value = error as { code?: string; response?: { status?: number } } | undefined;
  const status = value?.response?.status;
  return error instanceof WorkflowInfrastructureError
    ? error.transient
    : (typeof status === 'number' && (status === 429 || (status >= 500 && status <= 599))) ||
        [
          'ECONNREFUSED',
          'ECONNRESET',
          'ETIMEDOUT',
          'EAI_AGAIN',
          'PROTOCOL_CONNECTION_LOST',
          'ER_LOCK_DEADLOCK',
          'ER_LOCK_WAIT_TIMEOUT',
        ].includes(value?.code ?? '');
}

/** Keep classification, never keep HTTP bodies, request configuration or runtime credentials. */
export function infrastructureError(error: unknown, message: string): WorkflowInfrastructureError {
  return new WorkflowInfrastructureError(transientInfrastructureFailure(error), message);
}
