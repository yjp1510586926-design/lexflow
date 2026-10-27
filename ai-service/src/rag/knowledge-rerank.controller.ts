import {
  Body,
  Controller,
  Headers,
  HttpCode,
  Post,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import { EmbeddingConfiguration } from './knowledge-indexer';
import { rerankDocuments, RerankInput, Rank } from './knowledge-reranker';

@Controller('/internal/v1/knowledge')
export class KnowledgeRerankController {
  @Post('/rerank')
  @HttpCode(200)
  async rerank(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: RerankInput & EmbeddingConfiguration,
  ): Promise<{ ranks: Rank[] }> {
    const actual = Buffer.from(supplied ?? '');
    const expected = Buffer.from(
      process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
    );
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    try {
      return {
        ranks: await rerankDocuments(input, input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? ''),
      };
    } catch {
      // Provider responses may contain Key or original evidence; do not log the original exception.
      throw new ServiceUnavailableException('资料排序暂不可用，请检查排序模型配置或稍后重试');
    }
  }
}
