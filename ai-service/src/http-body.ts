import { NestExpressApplication } from '@nestjs/platform-express';

/** 50 excerpts × 1000 UTF-16 characters plus credentials can exceed Express's default 100 KB. */
export function configureInternalJsonBody(app: NestExpressApplication): void {
  app.useBodyParser('json', {
    limit: '8mb',
    type: (request) =>
      request.method === 'POST' &&
      request.url?.split('?')[0] === '/internal/v1/workflow-recovery/eligibility' &&
      /^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? ''),
  });
  // This read-only probe receives all of a legal index task's bounded excerpts, without its Key.
  app.useBodyParser('json', {
    limit: '16mb',
    type: (request) =>
      request.method === 'POST' &&
      request.url?.split('?')[0] === '/internal/v1/index-recovery/eligibility' &&
      /^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? ''),
  });
  app.useBodyParser('json', { limit: '256kb' });
}
