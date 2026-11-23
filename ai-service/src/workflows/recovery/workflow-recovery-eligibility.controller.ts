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
import { internalSecret } from '../../config/settings';
import {
  WorkflowRecoveryEligibilityService,
  WorkflowRecoveryResult,
  validateWorkflowRecoveryRequest,
} from './workflow-recovery-eligibility.service';

/** Java 的只读恢复探针入口；先验证内部凭据，再解析不含模型 Key 的请求。 */
@Controller('/internal/v1/workflow-recovery')
/**
 * Java 调用的内部恢复检查 HTTP 入口，接受不含模型 Key 的固定任务输入。
 * 先验证内部凭据再校验正文，只有通过请求校验后才进入连接池/快照探针。
 * 本入口不会执行或恢复工作流，只返回当前兼容性状态，恢复仍须走 Java 的任务权限与费用流程。
 */
export class WorkflowRecoveryEligibilityController {
  constructor(private readonly eligibility: WorkflowRecoveryEligibilityService) {}
  @Post('/eligibility')
  @HttpCode(200)
  /**
   * 验证内部凭据并提交只读恢复资格检查。
   * 长度检查先于 timingSafeEqual，既避免比较异常，也不把凭据值写入错误提示。
   * 请求格式错误统一为 400；凭据错误为 401；有效探针结果以 200 返回，具体是否可恢复由 status 表达。
   *
   * @param supplied X-Internal-Secret 请求头，只接受字符串并与部署配置比较。
   * @param input 未知 JSON，由任务类型对应校验器限制字段、身份和大小。
   * @returns 仅包含恢复检查状态的对象，不含凭据、材料或模型结果。
   */
  async check(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: unknown,
  ): Promise<WorkflowRecoveryResult> {
    const actual = Buffer.from(typeof supplied === 'string' ? supplied : '');
    const expected = Buffer.from(internalSecret());
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    let validated;
    try {
      validated = validateWorkflowRecoveryRequest(input);
    } catch {
      throw new BadRequestException('工作流恢复检查参数无效');
    }
    return this.eligibility.check(validated);
  }
}
