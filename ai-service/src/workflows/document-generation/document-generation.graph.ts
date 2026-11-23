/**
 * 文书起草的 LangGraph 入口。这里绑定生成操作日志，管理执行锁和图恢复，校验完成的草稿随后由 Java 接收。
 */

import { StateGraph, START, END } from '@langchain/langgraph';
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
  GenerationState,
  type GenerationInput,
  type GeneratedDraft,
  type GenerationRuntime,
} from './document-generation.state';
import {
  createDocumentGenerationNodes,
  validateGenerationInput,
  validateGeneratedDraft,
} from './document-generation.nodes';

export type { GenerationInput, GeneratedDraft } from './document-generation.state';
export { validateGenerationInput, validateGeneratedDraft } from './document-generation.nodes';

/**
 * 将不可变起草内容和模型身份计算为内容指纹。
 * 标题、原稿、起草要求和案件背景都可能改变正文，连同来源版本和 modelRevision 一起参与摘要。
 * attempt/stateVersion 只描述任务状态，apiKey 是运行时凭据，均不进入摘要；Key 变更由服务层另外检查。
 *
 * @param input Java 本次固定的起草输入，字段顺序应与恢复探针保持兼容。
 * @returns 用于快照匹配和 document-draft-v1 操作复用的 SHA-256 摘要。
 */
export function generationIdentity(input: GenerationInput): string {
  return createHash('sha256')
    .update(
      JSON.stringify([
        input.tenantId,
        input.taskId,
        input.caseId,
        input.documentId,
        input.sourceVersionId,
        input.title,
        input.baseContent,
        input.instructions,
        input.caseContext,
        input.modelRevision,
        input.provider,
        input.baseUrl,
        input.model,
      ]),
    )
    .digest('hex');
}

/**
 * 生成或恢复一份待复核文书草稿，图包含 generateDraft 与 validateDraft 两个节点。
 * 生成节点先通过 runOnce 保存操作意图和结果；校验节点再次检查实时授权并规范化草稿。
 * 模型完成而节点检查点尚未保存时，恢复可复用已保存操作结果；遗留 STARTED 或 FAILED 不在此重发。
 * 首次执行写入输入指纹，恢复时拒绝不匹配的内容；有未完成节点才继续执行，完成后仍复查授权。
 *
 * @param input 固定来源版本、案件背景、原稿和起草要求，不整包放入图状态。
 * @param saver 绑定当前租户的持久化存储器，负责线程锁、检查点和操作记录。
 * @param runtime 模型与实时授权函数，仅通过运行时闭包使用。
 * @returns 可回传 Java 的 GeneratedDraft；本函数不确认队列消息，也不决定是否采用正文。
 */
export async function runDocumentGeneration(
  input: GenerationInput,
  saver: TenantMySqlCheckpointer,
  runtime: GenerationRuntime,
): Promise<GeneratedDraft> {
  validateGenerationInput(input);
  const caseContext = JSON.parse(input.caseContext);
  const inputHash = generationIdentity(input);
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
      throw infrastructureError(error, '生成权限或输入已变化');
    }
  };
  // 操作日志单独保存付费结果，覆盖模型已返回但图快照尚未提交的中断窗口。
  const runDraft = (action: () => Promise<GeneratedDraft>) =>
    saver.runOnce(config, 'document-draft-v1', inputHash, action);
  const nodes = createDocumentGenerationNodes(input, runtime, caseContext, authorized, runDraft);
  const graph = new StateGraph(GenerationState)
    .addNode('generateDraft', nodes.generateDraft)
    .addNode('validateDraft', nodes.validateDraft)
    .addEdge(START, 'generateDraft')
    .addEdge('generateDraft', 'validateDraft')
    .addEdge('validateDraft', END)
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
      throw new WorkflowInfrastructureError(false, '生成输入与已保存工作流不同');
    }
    // 恢复时只续跑未完成节点；已有 draft 会从日志复用，最终仍需校验当前授权。
    if (!saved) {
      await graph.invoke({ inputHash: inputHash }, config);
    } else if ((await graph.getState(config)).next.length) {
      await graph.invoke(null, config);
    }
    const state = await graph.getState(config);
    await authorized();
    return validateGeneratedDraft(state.values.draft);
  });
}
