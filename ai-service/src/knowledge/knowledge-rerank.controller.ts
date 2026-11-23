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
import type { ModelConfiguration } from '../ai/models/model-configuration';
import { rerankDocuments, RerankInput, Rank } from '../ai/models/rerank-model';
import { allowedModelHosts, internalSecret } from '../config/settings';

/**
 * 内部排序入口，Java 提供已重新授权的候选原文和本次人工模型配置。
 * 原文只用于向排序服务发起请求；输出缩减为编号与分数，Java 再映射回授权来源。
 * 不在此处保存配置、操作日志或问答，也不自行扩充候选列表。
 */
@Controller('/internal/v1/knowledge')
export class KnowledgeRerankController {
  /**
   * 验证 Java 内部凭据后进行一次重排调用。
   * 只有通过内部凭据检查才进入模型流程；之后参数、地址、网络或响应校验失败均转换为
   * 固定 503 提示，避免原始异常中的材料、Key 或供应商细节进入 HTTP 响应。
   *
   * @param supplied 内部共享凭据，长度匹配后再做 timingSafeEqual 比较。
   * @param input 同时包含排序参数与运行时模型配置，不应整包记录或持久化。
   * @returns ranks 中的 index 严格指向本次输入数组，最终原文和权限仍由 Java 复核。
   */
  @Post('/rerank')
  @HttpCode(200)
  async rerank(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: RerankInput & ModelConfiguration,
  ): Promise<{ ranks: Rank[] }> {
    const actual = Buffer.from(supplied ?? '');
    const expected = Buffer.from(internalSecret());
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    try {
      return {
        ranks: await rerankDocuments(input, input, allowedModelHosts()),
      };
    } catch {
      // 供应商异常可能包含 Key 或原文；不记录原异常，只返回本地固定提示。
      throw new ServiceUnavailableException('资料排序暂不可用，请检查排序模型配置或稍后重试');
    }
  }
}
