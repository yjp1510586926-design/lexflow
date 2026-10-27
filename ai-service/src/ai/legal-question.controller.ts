import {
  Body,
  Controller,
  Headers,
  HttpCode,
  Post,
  UnauthorizedException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import { EmbeddingConfiguration } from '../rag/knowledge-indexer';
import {
  answerQuestion,
  configuredQuestionModel,
  QuestionInput,
  QuestionAnswer,
  validateQuestion,
  INSUFFICIENT_ANSWER,
} from './legal-question';

@Controller('/internal/v1/ai')
export class LegalQuestionController {
  @Post('/answer')
  @HttpCode(200)
  async answer(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: QuestionInput & EmbeddingConfiguration,
  ): Promise<QuestionAnswer> {
    const actual = Buffer.from(supplied ?? '');
    const expected = Buffer.from(
      process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
    );
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    try {
      validateQuestion(input);
      if (!input.evidence.length)
        return { answer: INSUFFICIENT_ANSWER, citationIds: [], insufficientEvidence: true };
      return await answerQuestion(
        input,
        configuredQuestionModel(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? ''),
      );
    } catch {
      // Never expose or log the SDK exception: provider errors can contain Key or source text.
      throw new ServiceUnavailableException('资料问答暂不可用，请检查模型配置或稍后重试');
    }
  }
}
