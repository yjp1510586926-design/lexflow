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
import { allowedModelHosts, internalSecret } from '../config/settings';
import {
  ConfigurationTestService,
  ConfigurationTestResult,
  validateConfigurationTest,
} from './configuration-test.service';

/**
 * 主动连接测试的内部 HTTP 入口，连接到 Java 已创建的测试记录。
 * 控制器先核验内部凭据和参数，执行服务再负责线程锁、操作日志和实际探针。
 * 此处不创建业务测试记录，也不自行确认费用；这些步骤由 Java 完成。
 */
@Controller('/internal/v1/ai')
export class ConfigurationTestController {
  constructor(private readonly tests: ConfigurationTestService) {}

  /**
   * POST /internal/v1/ai/configuration-test：返回探针状态，不返回供应商响应正文。
   * 校验失败返回 400，内部凭据不符返回 401；有效请求的失败或不确定状态由结果对象表达。
   * 控制器预校验便于区分请求错误；服务层仍独立校验，避免其他调用入口跳过检查。
   * @param supplied Java 使用的内部凭据，非字符串值按空凭据处理。
   * @param input 尚未信任的请求体，包含测试身份、用途和配置版本。
   */
  @Post('/configuration-test')
  @HttpCode(200)
  async test(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<ConfigurationTestResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : '');
    const expected = Buffer.from(internalSecret());
    // timingSafeEqual 要求两侧字节长度相等，先用短路判断处理空值与长度不符。
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    let validated;
    try {
      validated = validateConfigurationTest(input, allowedModelHosts());
    } catch {
      throw new BadRequestException('连接测试参数或部署环境授权无效');
    }
    return this.tests.test(validated);
  }
}
