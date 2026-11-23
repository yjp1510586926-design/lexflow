/**
 * 合同审查的输入、条款、依据、报告与状态通道。首轮和补材轮次使用同一套契约，图通过独立命名空间区分执行记录。
 */

import { Annotation } from '@langchain/langgraph';
import type { QuestionModel } from '../../ai/models/legal-model';
import type { ModelConfiguration } from '../../ai/models/model-configuration';

/** Java 固定的合同审查输入；round=0 为首轮，后续轮次包含补材及上一轮报告。 */
export interface ContractInput extends ModelConfiguration {
  tenantId: string;
  taskId: string;
  /** 当前被审查的不可变合同文件版本，恢复时不能切换。 */
  fileVersionId: string;
  /** 合同原件字节摘要，下载解析时核对，报告保留以便追溯。 */
  sha256: string;
  mimeType: string;
  /** Java 任务的业务执行轮次，重投沿用原轮次。 */
  attempt: number;
  /** 当前任务状态版本；后续下载、检索、授权检查和结果回调必须一致。 */
  stateVersion: number;
  /** 审查立场：甲方、乙方或中立；影响建议并参与内容指纹。 */
  stance: 'PARTY_A' | 'PARTY_B' | 'NEUTRAL';
  /** 用户关注点，有界字符串；为空时用合同类型与条款标题构造检索问题。 */
  focus: string;
  /** 明确选择的授权知识库；null 表示不检索，不自动选择其他库。 */
  knowledgeBaseId: string | null;
  /** Java 已保存的复核决定，用于恢复 humanReview，而不是模型可生成的内容。 */
  decisionId: string | null;
  /** 0/缺省为首轮；1–10 为补材轮次，各轮隔离快照和操作键。 */
  round?: number;
  /** 补材轮次的固定补充说明，作为分析数据而非系统指令。 */
  instructions?: string;
  /** 新增事实材料，不能自动替代检索到的法律参考依据。 */
  supplements?: ContractSupplement[];
  /** 补材轮次必须携带的上一轮报告，原文与条款会重新校验。 */
  previousReport?: ContractReport | null;
}

/** 补充事实材料的不可变版本及页段定位，与知识库中的法律参考依据分开校验。 */
export interface ContractSupplement {
  parseTaskId: string;
  fileVersionId: string;
  sha256: string;
  fileName: string;
  text: string;
  segments: { kind: 'PAGE' | 'PARAGRAPH'; index: number; startOffset: number; endOffset: number }[];
}

/** 从合同逐字提取的条款；字符偏移由服务端计算，采用 UTF-16 半开区间。 */
export interface ContractClause {
  /** 报告内唯一的条款编号，风险分析必须引用已有编号。 */
  id: string;
  title: string;
  /** 合同原文中的连续文字，由服务器查找后重建位置。 */
  quote: string;
  /** quote 首次出现在完整原文中的 UTF-16 起点。 */
  startOffset: number;
  /** UTF-16 结束位置，不包含该位置。 */
  endOffset: number;
}

/** 由 Java 实时授权的知识切片，id 用于约束模型的依据引用。 */
export interface ContractEvidence {
  id: string;
  text: string;
}

export interface ContractRisk {
  /** 绑定已提取条款；同一条款最多一项综合风险。 */
  clauseId: string;
  /** 风险等级 HIGH/MEDIUM/LOW；已有 HIGH 在深度复核后仍保留。 */
  level: 'HIGH' | 'MEDIUM' | 'LOW';
  /** 基于本次材料的风险解释，不能据格式正确就认定法律结论正确。 */
  reason: string;
  /** 供律师核对的修改建议，不自动修改合同正文。 */
  suggestion: string;
  /** 当前授权依据的编号列表，可为空，但不允许未知或重复编号。 */
  evidenceIds: string[];
}

/** 报告保留原文及引用，便于人工逐项核对；needsHuman 不随审核完成而抹去。 */
export interface ContractReport {
  sourceSha256: string;
  /** 固定合同原文，供人工逐字核对，也是补材轮次复用条款的依据。 */
  sourceText: string;
  contractType: string;
  clauses: ContractClause[];
  risks: ContractRisk[];
  /** 需要补充的事实或参考材料，初步与深度意见按集合合并。 */
  missingMaterials: string[];
  /** 本轮实际检索且已授权的知识切片，用于追溯风险引用。 */
  evidence: ContractEvidence[];
  /** 生成报告时的人工作业标记；完成复核后不会清除该历史风险判断。 */
  needsHuman: boolean;
  /** 实际引用依据之间的冲突；旧报告可能缺省，按空数组兼容。 */
  basisConflicts?: ContractBasisConflict[];
}

export interface ContractBasisConflict {
  description: string;
  evidenceIds: string[];
}

export interface Analysis {
  risks: ContractRisk[];
  missingMaterials: string[];
  insufficientEvidence: boolean;
  basisConflicts: ContractBasisConflict[];
}

// 这里只放可序列化的业务结果；模型、下载函数及凭据保留在 ContractRuntime 闭包中。
export const ContractState = Annotation.Root({
  inputHash: Annotation<string>(),
  text: Annotation<string>(),
  contractType: Annotation<string>(),
  clauses: Annotation<ContractClause[]>(),
  evidence: Annotation<ContractEvidence[]>(),
  analysis: Annotation<Analysis>(),
  report: Annotation<ContractReport>(),
  decisionId: Annotation<string>(),
});

/** 外部能力由服务层注入，图负责流程编排与持久化，Java 负责业务授权。 */
export interface ContractRuntime {
  /** 只在运行时调用的模型实例，不能序列化到检查点。 */
  model: QuestionModel;
  /** 按当前认领版本向 Java 重新核对固定输入、Key 和人工决定。 */
  assertAuthorized: () => Promise<void>;
  /** 从任务专用接口下载并验证原件，返回完整合同正文。 */
  parse: () => Promise<string>;
  /** 只在明确选定知识库时使用，由 Java 按任务当前权限返回原文依据。 */
  retrieve: (query: string) => Promise<ContractEvidence[]>;
}
