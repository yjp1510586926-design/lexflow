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
  WorkflowRecoveryEligibilityService,
  WorkflowRecoveryResult,
  validateWorkflowRecoveryRequest,
} from './workflow-recovery-eligibility.service';

@Controller('/internal/v1/workflow-recovery')
export class WorkflowRecoveryEligibilityController {
  constructor(private readonly eligibility: WorkflowRecoveryEligibilityService) {}
  @Post('/eligibility')
  @HttpCode(200)
  async check(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<WorkflowRecoveryResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : ''),
      expected = Buffer.from(process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod');
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    let validated;
    try {
      validated = validateWorkflowRecoveryRequest(input);
    } catch {
      throw new BadRequestException('工作流恢复检查参数无效');
    }
    return this.eligibility.check(validated);
  }
}
