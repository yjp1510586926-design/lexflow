/**
 * 案件分析的输入、报告、引用和状态通道。节点通过这些类型传递固定材料和分析结果，模型及授权函数仅在本次运行时注入。
 */

import { Annotation } from '@langchain/langgraph';
import type { QuestionModel } from '../../ai/models/legal-model';
import type { ModelConfiguration } from '../../ai/models/model-configuration';

/** Java 固定的解析任务及原件版本；页段偏移采用与 Java String 一致的 UTF-16 半开区间。 */
export interface AnalysisSource {
  /** Java 已完成的解析任务编号，用它选择引用对应哪一份解析原文。 */
  parseTaskId: string;
  /** 不可变文件版本身份；与 sha256 一起绑定引用的实际来源。 */
  fileVersionId: string;
  /** 逻辑文件编号，与具体 fileVersionId 区分。 */
  fileId: string;
  fileName: string;
  /** 用户可见的正整数版本序号，不代替不可变版本主键。 */
  fileVersion: number;
  /** 原始文件字节摘要，不是提取正文的摘要。 */
  sha256: string;
  /** 本次固定的完整解析正文，后续引用只在这里定位。 */
  text: string;
  /** 页或段的真实编号及 UTF-16 半开区间；编号递增、区间有序且不重叠。 */
  segments: { kind: 'PAGE' | 'PARAGRAPH'; index: number; startOffset: number; endOffset: number }[];
}

/** Java 已认领并固定的分析输入；apiKey 仅供运行时使用，不能整体保存为图状态。 */
export interface CaseAnalysisInput extends ModelConfiguration {
  /** 已认证租户编号，参与线程身份，不能由模型输出改变。 */
  tenantId: string;
  /** Java 已保存的分析任务，重投及恢复沿用此编号。 */
  taskId: string;
  caseId: string;
  /** 业务任务执行轮次；与投递重试头不同，不参与内容指纹。 */
  attempt: number;
  /** Java 状态乐观版本，认领可能增加一次，回调必须使用认领后值。 */
  stateVersion: number;
  /** 用户陈述的案件背景，不因传入模型就成为材料已证明的事实。 */
  background: string;
  /** 本次固定分析要求；作为不可信数据传给模型，不能覆盖系统规则。 */
  requirements: string;
  /** 明确选定的固定材料，事实与建议的引用只能来自这些原文。 */
  sources: AnalysisSource[];
  /** 模型配置修订身份，参与指纹，配置变更后不能沿用旧分析。 */
  configurationRevision: string;
  /** Java 已持久化的人工决定 UUID；首次分析为 null，仅用于恢复既有人工节点。 */
  decisionId: string | null;
}

/** 服务器核验后的引用，绑定原件版本、摘要及精确页段位置。 */
export interface AnalysisCitation {
  /** 本份报告内的 C1 等引用编号，事实和争议通过 citationIds 引用它。 */
  id: string;
  /** 引用所属解析任务，由当前固定材料核对。 */
  parseTaskId: string;
  /** 由服务器从原文来源复制，不信任模型自报的文件身份。 */
  fileVersionId: string;
  sha256: string;
  kind: 'PAGE' | 'PARAGRAPH';
  /** 固定来源中的真实页码/段号，不是引用在数组中的下标。 */
  index: number;
  /** 引文在完整正文中的 UTF-16 起点，包含该位置。 */
  startOffset: number;
  /** 引文末尾之后的位置，可直接配合 slice/Java substring 使用。 */
  endOffset: number;
  /** 指定页段内连续、逐字存在的原文，不接受改写或省略。 */
  quote: string;
}

export interface AnalysisFact {
  statement: string;
  citationIds: string[];
}

export interface AnalysisIssue {
  title: string;
  level: 'LOW' | 'MEDIUM' | 'HIGH';
  analysis: string;
  suggestion: string;
  citationIds: string[];
}

/** 供律师复核的报告；needsHuman 记录生成时风险，完成状态由工作流 outcome 表达。 */
export interface CaseAnalysisReport {
  /** 有已核验引用的材料事实表述，仍需人工阅读原文核对其含义。 */
  facts: AnalysisFact[];
  /** 材料提取阶段发现的待核实事项，区别于已证事实。 */
  pendingVerification: string[];
  /** 基于已提取事实形成的争议分析及核查建议。 */
  issues: AnalysisIssue[];
  /** 争议分析发现需要补充的材料，初步与高风险复核结果合并保留。 */
  missingMaterials: string[];
  citations: AnalysisCitation[];
  /** 明确说明本次建议受哪些证据和适用条件限制。 */
  recommendationBoundary: string;
  /** 模型指出证据不足；高风险复核不能清除初步已发现的不足。 */
  insufficientEvidence: boolean;
  /** 报告生成时是否需要人工复核；最终任务是否完成由 outcome 表达，不能只看此字段。 */
  needsHuman: boolean;
}

export interface Extracted {
  facts: AnalysisFact[];
  pendingVerification: string[];
  citations: AnalysisCitation[];
}

export interface Assessment {
  issues: AnalysisIssue[];
  missingMaterials: string[];
  insufficientEvidence: boolean;
  recommendationBoundary: string;
}

// Annotation 定义可持久化的状态通道；节点只返回自己更新的部分，框架合并并保存检查点。
export const AnalysisState = Annotation.Root({
  inputHash: Annotation<string>(),
  extracted: Annotation<Extracted>(),
  assessment: Annotation<Assessment>(),
  report: Annotation<CaseAnalysisReport>(),
  decisionId: Annotation<string>(),
});

/** 模型实例与授权函数通过闭包注入，避免凭据随图状态落库。 */
export interface AnalysisRuntime {
  /** 已配置且关闭隐式重试的模型能力，实例只留在闭包，不放入图状态。 */
  model: QuestionModel;
  /** 向 Java 复查当前任务输入/凭据/决定，撤权或固定内容变化时抛错。 */
  assertAuthorized: () => Promise<void>;
}
