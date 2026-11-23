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
import { internalSecret } from '../config/settings';

/**
 * Java 在人工恢复索引前调用的只读资格检查入口。
 * 请求包含完整固定输入但去除 apiKey；服务读取已有批次日志，不触发恢复、模型或向量写入。
 * 大输入容量由专用 HTTP body 配置控制，业务校验仍限制最多 10000 个有界切片。
 */
@Controller('/internal/v1/index-recovery')
export class IndexRecoveryEligibilityController {
  constructor(private readonly eligibility: IndexRecoveryEligibilityService) {}

  /**
   * 按“内部鉴权 → 参数校验 → 查询状态”的顺序处理，非法输入不会获得数据库连接。
   * 鉴权失败返回 401，结构无效返回本地固定 400 提示；探针无法完成时返回 UNAVAILABLE。
   * HTTP 200 只表示检查接口正常返回，不代表任务已恢复或允许重发不确定的付费调用。
   *
   * @param supplied Java 提供的共享内部凭据，其他类型不会被当成合法字符串。
   * @param input 未经信任的 JSON，必须经过运行时校验，不能仅用类型断言替代检查。
   * @returns 固定状态枚举，不回传材料、向量或任何模型配置凭据。
   */
  @Post('/eligibility')
  @HttpCode(200)
  async check(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<IndexRecoveryResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : '');
    const expected = Buffer.from(internalSecret());
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    let validated: ReturnType<typeof validateIndexRecoveryRequest>;
    try {
      validated = validateIndexRecoveryRequest(input);
    } catch {
      throw new BadRequestException('索引恢复检查参数无效');
    }
    return this.eligibility.check(validated);
  }
}
