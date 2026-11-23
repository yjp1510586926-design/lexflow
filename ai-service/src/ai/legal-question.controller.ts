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
import { allowedModelHosts, internalSecret } from '../config/settings';
import type { ModelConfiguration } from './models/model-configuration';
import { configuredQuestionModel } from './models/legal-model';
import {
  answerQuestion,
  QuestionInput,
  QuestionAnswer,
  validateQuestion,
  INSUFFICIENT_ANSWER,
} from './legal-question';

/**
 * Java 调用的内部资料问答入口，不直接接受前端请求。
 * 内部凭据确认请求来自约定调用方，具体用户与原文访问权限由 Java 在调用前授权。
 * 控制器负责鉴权、模型装配与错误隔离，回答和引用的校验交给 legal-question.ts。
 */
@Controller('/internal/v1/ai')
export class LegalQuestionController {
  /**
   * POST /internal/v1/ai/answer：有材料时生成一次回答，无材料时直接返回固定拒答。
   * 鉴权失败在 try 之外返回 401；进入业务流程后的配置、参数和模型异常统一返回 503。
   * 这样不会把供应商错误或原文带入 HTTP 异常详情，也不会在接口层静默重试。
   * @param supplied Java 提供的 X-Internal-Secret 请求头。
   * @param input 问答字段与当前模型配置的组合，来源应已由 Java 授权。
   * @returns 经本地结构与引用编号检查的结果，由 Java 再次检查来源并保存。
   */
  @Post('/answer')
  @HttpCode(200)
  async answer(
    @Headers('x-internal-secret') supplied: string | undefined,
    @Body() input: QuestionInput & ModelConfiguration,
  ): Promise<QuestionAnswer> {
    const actual = Buffer.from(supplied ?? '');
    const expected = Buffer.from(internalSecret());
    // 先比较字节长度，避免 timingSafeEqual 因长度不同而抛出异常。
    if (
      !expected.length ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    ) {
      throw new UnauthorizedException();
    }
    try {
      validateQuestion(input);
      // 在构造模型前短路，空材料不要求有效模型配置，也不会发出模型请求。
      if (!input.evidence.length) {
        return { answer: INSUFFICIENT_ANSWER, citationIds: [], insufficientEvidence: true };
      }
      // await 保证异步失败也被当前 catch 接住，统一替换成不含供应商详情的提示。
      return await answerQuestion(input, configuredQuestionModel(input, allowedModelHosts()));
    } catch {
      // SDK 异常可能包含 Key 或原文，只对外返回固定提示，不记录原始异常。
      throw new ServiceUnavailableException('资料问答暂不可用，请检查模型配置或稍后重试');
    }
  }
}
