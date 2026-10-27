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
  ConfigurationTestService,
  ConfigurationTestResult,
  validateConfigurationTest,
} from './configuration-test.service';

@Controller('/internal/v1/ai')
export class ConfigurationTestController {
  constructor(private readonly tests: ConfigurationTestService) {}

  @Post('/configuration-test')
  @HttpCode(200)
  async test(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<ConfigurationTestResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : '');
    const expected = Buffer.from(
      process.env.INTERNAL_SECRET ?? 'dev-internal-secret-change-in-prod',
    );
    if (!expected.length || actual.length !== expected.length || !timingSafeEqual(actual, expected))
      throw new UnauthorizedException();
    let validated;
    try {
      validated = validateConfigurationTest(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '');
    } catch {
      throw new BadRequestException('连接测试参数或部署环境授权无效');
    }
    return this.tests.test(validated);
  }
}
