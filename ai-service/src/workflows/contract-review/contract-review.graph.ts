/**
 * 合同审查的 LangGraph 入口。这里登记首轮与补材轮次的操作身份，连接风险分支，并从快照继续图或等待人工决定。
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
  ContractState,
  type ContractInput,
  type ContractReport,
  type ContractRuntime,
} from './contract-review.state';
import { createContractReviewNodes, validateContractInput } from './contract-review.nodes';

export type {
  ContractInput,
  ContractSupplement,
  ContractClause,
  ContractEvidence,
  ContractRisk,
  ContractReport,
  ContractBasisConflict,
  ContractRuntime,
} from './contract-review.state';
export {
  validateContractInput,
  validateContractEvidence,
  validateContractExtraction,
  validateContractAnalysis,
} from './contract-review.nodes';

/**
 * 计算合同审查内容身份，执行与恢复探针必须采用相同字段及顺序。
 * 首轮保留原有摘要格式；round>0 时才追加轮次、补充要求、补材和上一轮报告，使历史快照仍可匹配。
 * attempt、stateVersion 和 decisionId 属于执行/复核状态；Key 属于运行时凭据，均不参与内容身份。
 * 模型配置在此使用 provider/baseUrl/model，凭据是否仍有效由服务层单独复查。
 *
 * @param input 当前轮次的固定合同审查输入，服务商等字符串须先满足基本校验。
 * @returns 用于快照校验和操作摘要组合的 SHA-256 指纹。
 */
export function contractIdentity(input: ContractInput): string {
  const identity: unknown[] = [
    input.tenantId,
    input.taskId,
    input.fileVersionId,
    input.sha256,
    input.stance,
    input.focus,
    input.knowledgeBaseId,
    input.provider.trim(),
    input.baseUrl.trim(),
    input.model.trim(),
  ];
  // 首轮保留既有摘要格式，保证旧快照兼容；补材轮次将新增材料和上一轮报告纳入指纹。
  if (input.round) {
    identity.push(input.round, input.instructions, input.supplements, input.previousReport);
  }
  return createHash('sha256').update(JSON.stringify(identity)).digest('hex');
}

/**
 * 执行或恢复一轮合同审查，业务任务状态与最终发布仍由 Java 掌握。
 * 首轮解析原件并提取条款；补材轮次复用上一轮原文和条款，但重新检索、分析及汇总风险。
 * HIGH 风险或依据冲突进入 deepReview；二次意见不能删除已有 HIGH 风险，冲突、缺材料及依据不足会合并保留。
 * 报告遇到依据不足、无知识依据、缺材料、冲突或 HIGH 风险时中断，等待 Java 已保存的决定编号恢复。
 * 每轮隔离 checkpoint 命名空间并区分操作键；所有图执行和恢复都在同一任务线程锁内完成。
 *
 * @param input 本轮固定输入，decisionId 只用于恢复既有人工复核节点。
 * @param saver 当前租户的存储器；补材时从它派生带轮次前缀的快照存储器。
 * @param runtime 已配置模型、解析/检索能力和实时授权复查函数，不随图状态序列化。
 * @returns WAITING_HUMAN 或 SUCCEEDED 以及保留原文的报告，消费者负责随后回调 Java。
 * @throws 输入指纹不符、授权变更、输出引用无效或操作状态禁止复用时终止。
 */
export async function runContractReview(
  input: ContractInput,
  saver: TenantMySqlCheckpointer,
  runtime: ContractRuntime,
): Promise<{ outcome: 'WAITING_HUMAN' | 'SUCCEEDED'; report: ContractReport }> {
  validateContractInput(input);
  const inputHash = contractIdentity(input);
  const round = input.round ?? 0;
  // 各补材轮次隔离快照，并使用带轮次的操作键，首轮结果不会覆盖本轮计算。
  if (round) {
    saver = saver.forNamespace(`contract-round-${round}`);
  }
  const config: RunnableConfig & { durability: 'sync' } = {
    configurable: {
      tenant_id: input.tenantId,
      thread_id: workflowThreadId(input.tenantId, input.taskId),
    },
    metadata: { lexflowInputHash: inputHash },
    durability: 'sync',
  };
  const authorized = async () => {
    try {
      await runtime.assertAuthorized();
    } catch (error) {
      throw infrastructureError(error, '审查权限或模型配置已变化');
    }
  };
  const call = async <T>(step: string, data: unknown, action: () => Promise<T>) =>
    saver.runOnce(
      config,
      round ? `${step}-round-${round}` : step,
      contractOperationHash(inputHash, data),
      async () => {
        await authorized();
        return action();
      },
    );
  const nodes = createContractReviewNodes(input, runtime, round, authorized, call);
  const graph = new StateGraph(ContractState)
    .addNode('parseDocument', nodes.parseDocument)
    .addNode('extractClauses', nodes.extractClauses)
    .addNode('retrieveKnowledge', nodes.retrieveKnowledge)
    .addNode('analyzeRisk', nodes.analyzeRisk)
    .addNode('deepReview', nodes.deepReview)
    .addNode('generateReport', nodes.generateReport)
    .addNode('humanReview', nodes.humanReview)
    .addEdge(START, 'parseDocument')
    .addEdge('parseDocument', 'extractClauses')
    .addEdge('extractClauses', 'retrieveKnowledge')
    .addEdge('retrieveKnowledge', 'analyzeRisk')
    .addConditionalEdges(
      'analyzeRisk',
      (state) =>
        state.analysis.risks.some((risk) => risk.level === 'HIGH') ||
        !!state.analysis.basisConflicts?.length
          ? 'deepReview'
          : 'generateReport',
      ['deepReview', 'generateReport'],
    )
    .addEdge('deepReview', 'generateReport')
    .addConditionalEdges(
      'generateReport',
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
        inputHash
    ) {
      throw new WorkflowInfrastructureError(false, '审查输入与已保存工作流不同');
    }
    // 首次执行建图；已有快照从未完成节点续跑，人工节点须等待明确决定。
    if (!saved) {
      if (input.decisionId) {
        throw new WorkflowInfrastructureError(false, '待恢复的审查快照缺失');
      }
      await graph.invoke({ inputHash: inputHash }, config);
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
      throw new Error('审查报告缺失');
    }
    // 人工复核推进任务完成状态，报告自身仍保留生成时的 needsHuman 风险标记。
    return {
      outcome: state.next.includes('humanReview') ? 'WAITING_HUMAN' : 'SUCCEEDED',
      report: state.values.report,
    };
  });
}

/**
 * 计算合同单步操作摘要，避免相同操作键复用不同依赖的数据。
 * 整轮身份涵盖合同和补材；节点数据再绑定本步的原文、检索问题、条款/依据或初步风险分析。
 *
 * @param identity contractIdentity 返回的本轮内容身份。
 * @param data 当前节点的实际依赖，不应包含凭据或原始服务商异常。
 * @returns 供 runOnce 比较的 SHA-256 摘要；JSON 数组顺序不可随意调整。
 */
export function contractOperationHash(identity: string, data: unknown): string {
  return createHash('sha256')
    .update(JSON.stringify([identity, data]))
    .digest('hex');
}
