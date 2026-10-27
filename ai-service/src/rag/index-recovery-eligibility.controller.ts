import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  Post,
  UnauthorizedException,
} from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import {
  IndexRecoveryEligibilityService,
  IndexRecoveryResult,
  validateIndexRecoveryRequest,
} from './index-recovery-eligibility.service';

@Controller('/internal/v1/index-recovery')
export class IndexRecoveryEligibilityController {
  constructor(private readonly eligibility: IndexRecoveryEligibilityService) {}

  @Post('/eligibility')
  @HttpCode(200)
  async check(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<IndexRecoveryResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : '');
    const expected = Buffer.from(
      process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
    );
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    let validated;
    try {
      validated = validateIndexRecoveryRequest(input);
    } catch {
      throw new BadRequestException('索引恢复检查参数无效');
    }
    return this.eligibility.check(validated);
  }
}
