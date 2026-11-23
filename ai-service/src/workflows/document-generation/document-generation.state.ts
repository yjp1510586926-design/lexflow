/**
 * 文书生成的输入、待复核草稿与状态通道。图状态只需要保存输入摘要和草稿，模型与授权能力由运行时闭包提供。
 */

import { Annotation } from '@langchain/langgraph';
import type { QuestionModel } from '../../ai/models/legal-model';

/** Java 固定的源版本、案件背景与起草要求；apiKey 仅在本次执行的闭包内使用。 */
export interface GenerationInput {
  tenantId: string;
  taskId: string;
  caseId: string;
  documentId: string;
  /** 本次固定的文书源版本，生成草稿不得覆盖其他版本。 */
  sourceVersionId: string;
  /** 业务任务执行轮次，不是模型 SDK 的重试次数。 */
  attempt: number;
  /** Java 任务状态版本，认领后值用于实时授权与最终回调。 */
  stateVersion: number;
  title: string;
  /** 起草参考原稿，最长 3 万 UTF-16 代码单元，允许为空。 */
  baseContent: string;
  /** 本次固定起草要求；与材料一样作为不可信数据传给模型。 */
  instructions: string;
  /** 案件背景 JSON 字符串，须解析为非数组对象；事实权限由 Java 负责。 */
  caseContext: string;
  /** 模型配置修订 UUID，参与生成内容指纹。 */
  modelRevision: string;
  provider: string;
  baseUrl: string;
  model: string;
  /** 仅供本次模型调用使用的运行时凭据，不放入图通道、摘要或日志。 */
  apiKey: string;
}

/** 模型产出的是待复核草稿，缺少的事实必须显式列出，不能自行补写。 */
export interface GeneratedDraft {
  /** 待复核草稿正文，提示要求事实缺口用明确占位符，校验器只验证格式和长度。 */
  content: string;
  /** 最多十项待补信息；不代表服务端已独立核实模型列全了所有缺口。 */
  missingInformation: string[];
}

// 图只保存输入指纹与草稿，避免整份输入中的凭据进入检查点。
export const GenerationState = Annotation.Root({
  inputHash: Annotation<string>(),
  draft: Annotation<GeneratedDraft>(),
});

/** 模型与 Java 授权复查在运行时注入，不写入检查点。 */
export interface GenerationRuntime {
  model: QuestionModel;
  assertAuthorized: () => Promise<void>;
}
