/**
 * 案件分析的 LangGraph 入口。这里绑定输入摘要、模型操作日志、节点与边，并协调执行锁及人工中断恢复。
 */

import { StateGraph, START, END, Command } from '@langchain/langgraph';
import type { RunnableConfig } from '@langchain/core/runnables';
import { createHash } from 'node:crypto';
import {
  TenantMySqlCheckpointer,
  workflowThreadId,
} from '../../infrastructure/persistence/mysql-checkpointer';
import {
  infrastructureError,
  WorkflowInfrastructureError,
} from '../../infrastructure/errors/workflow-infrastructure-error';
import {
  AnalysisState,
  type CaseAnalysisInput,
  type CaseAnalysisReport,
  type AnalysisRuntime,
} from './case-analysis.state';
import { createCaseAnalysisNodes, validateAnalysisInput } from './case-analysis.nodes';

export type {
  AnalysisSource,
  CaseAnalysisInput,
  AnalysisCitation,
  AnalysisFact,
  AnalysisIssue,
  CaseAnalysisReport,
  AnalysisRuntime,
} from './case-analysis.state';
export {
  validateAnalysisInput,
  parseAnalysisModelOutput,
  analysisModelSources,
  validateExtracted,
  validateAnalysisAssessment,
} from './case-analysis.nodes';

const hashJson = (value: unknown) =>
  createHash('sha256').update(JSON.stringify(value)).digest('hex');

/**
 * 计算一份案件分析内容的稳定指纹，供执行器和只读恢复探针共同使用。
 * 固定材料、背景、要求及模型配置修订都影响结果，必须参与摘要；JSON 数组顺序属于持久化兼容约定。
 * attempt/stateVersion 是投递状态，decisionId 是稍后产生的人工决定，均不改变分析内容身份。
 * API Key 不进入摘要；是否仍能使用该凭据由服务层单独比较并重新授权。
 *
 * @param input 已完成基本校验的固定分析输入。
 * @returns SHA-256 十六进制摘要，用于拒绝输入变化后的旧快照或旧操作结果。
 */
export function analysisIdentity(input: CaseAnalysisInput): string {
  return hashJson([
    input.tenantId,
    input.taskId,
    input.caseId,
    input.background,
    input.requirements,
    input.sources,
    input.provider.trim(),
    input.baseUrl.trim(),
    input.model.trim(),
    input.configurationRevision,
  ]);
}

/**
 * 执行或恢复案件材料分析，在持久化状态和操作日志的配合下产出待复核报告。
 * 节点顺序为：提取事实 → 分析争议 → 有 HIGH 风险时二次复核 → 合并报告 → 必要时人工中断。
 * 缺事实、待核实事项、缺材料、依据不足或高风险任一存在，都会进入 humanReview；复核不会自动抹去报告风险标记。
 * 整个图在同一线程锁内执行，durability=sync 要求节点进度同步保存；模型步骤另由 runOnce 缓存结果。
 * 恢复前必须匹配输入指纹；没有快照却带人工决定时拒绝从头重算，避免把新报告视为已审核旧报告。
 *
 * @param input Java 本轮认领的固定内容与可选人工决定；凭据不写入图状态。
 * @param saver 绑定当前租户的检查点存储器，也负责执行锁和外部操作日志。
 * @param runtime 模型及实时授权函数，授权在外部步骤和最终返回前复查。
 * @returns WAITING_HUMAN 或 SUCCEEDED 及报告；消费者还须把结果回传 Java 后再确认消息。
 * @throws 输入变化、授权失效、输出无效或操作失败/不确定时终止，不在本函数内擅自重发模型请求。
 */
export async function runCaseAnalysis(
  input: CaseAnalysisInput,
  saver: TenantMySqlCheckpointer,
  runtime: AnalysisRuntime,
): Promise<{ outcome: 'WAITING_HUMAN' | 'SUCCEEDED'; report: CaseAnalysisReport }> {
  validateAnalysisInput(input);
  const identity = analysisIdentity(input);
  const config: RunnableConfig & { durability: 'sync' } = {
    configurable: {
      tenant_id: input.tenantId,
      thread_id: workflowThreadId(input.tenantId, input.taskId),
    },
    metadata: { lexflowInputHash: identity },
    durability: 'sync',
  };
  const authorized = async () => {
    try {
      await runtime.assertAuthorized();
    } catch (error) {
      throw infrastructureError(error, '案件分析权限或固定输入已变化');
    }
  };
  // 图检查点记录节点进度，runOnce 另存外部调用结果，避免返回成功后尚未写快照时再次付费。
  const call = async <T>(step: string, data: unknown, action: () => Promise<T>) =>
    saver.runOnce(config, step, analysisOperationHash(identity, data), async () => {
      await authorized();
      return action();
    });
  const nodes = createCaseAnalysisNodes(input, runtime, authorized, call);
  const graph = new StateGraph(AnalysisState)
    .addNode('extractMaterialFacts', nodes.extractMaterialFacts)
    .addNode('analyzeDisputes', nodes.analyzeDisputes)
    .addNode('reviewHighRisk', nodes.reviewHighRisk)
    .addNode('buildAnalysisReport', nodes.buildAnalysisReport)
    .addNode('humanReview', nodes.humanReview)
    .addEdge(START, 'extractMaterialFacts')
    .addEdge('extractMaterialFacts', 'analyzeDisputes')
    .addConditionalEdges(
      'analyzeDisputes',
      (state) =>
        state.assessment.issues.some((issue) => issue.level === 'HIGH')
          ? 'reviewHighRisk'
          : 'buildAnalysisReport',
      ['reviewHighRisk', 'buildAnalysisReport'],
    )
    .addEdge('reviewHighRisk', 'buildAnalysisReport')
    .addConditionalEdges(
      'buildAnalysisReport',
      (state) => (state.report.needsHuman ? 'humanReview' : END),
      ['humanReview', END],
    )
    .addEdge('humanReview', END)
    .compile({ checkpointer: saver });
  return saver.withThreadLock(config, async () => {
    await authorized();
    const saved = await saver.getTuple(config);
    if (
      saved &&
      (saved.checkpoint.channel_values.inputHash ??
        (saved.metadata as { lexflowInputHash?: string } | undefined)?.lexflowInputHash) !==
        identity
    ) {
      throw new WorkflowInfrastructureError(false, '案件分析输入与已保存工作流不符');
    }
    // 有决定却无快照时不能从头重算，否则可能把新的报告当成已复核的旧报告。
    if (!saved) {
      if (input.decisionId) {
        throw new WorkflowInfrastructureError(false, '待复核的案件分析快照缺失');
      }
      await graph.invoke({ inputHash: identity }, config);
    } else {
      const state = await graph.getState(config);
      if (state.next.includes('humanReview')) {
        if (input.decisionId) {
          await graph.invoke(new Command({ resume: input.decisionId }), config);
        }
      } else if (state.next.length) {
        await graph.invoke(null, config);
      }
    }
    const state = await graph.getState(config);
    await authorized();
    if (!state.values.report) {
      throw new Error('案件分析报告缺失');
    }
    // needsHuman 保留报告生成时的风险判断；是否已复核完成由 outcome/任务状态表达。
    return {
      outcome: state.next.includes('humanReview') ? 'WAITING_HUMAN' : 'SUCCEEDED',
      report: state.values.report,
    };
  });
}

/**
 * 将整份任务身份和当前步骤的数据一起摘要，绑定一次可复用的外部操作。
 * 例如争议分析依赖已提取事实，高风险复核依赖初步分析；任一依赖变化，都不能沿用旧操作结果。
 *
 * @param identity analysisIdentity 返回的固定任务指纹。
 * @param data 当前步骤实际使用的依赖数据，不能含模型 Key。
 * @returns 与 runOnce 的 inputHash 对应的 SHA-256 摘要。
 */
export function analysisOperationHash(identity: string, data: unknown): string {
  return hashJson([identity, data]);
}
