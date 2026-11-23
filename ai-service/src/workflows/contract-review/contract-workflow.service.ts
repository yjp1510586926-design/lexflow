import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { allowedModelHosts, workflowDatabaseUrl } from '../../config/settings';
import { TaskCallbackService, TaskContext } from '../../infrastructure/java/task-callback.service';
import { workflowPool } from '../../infrastructure/persistence/workflow-pool';
import { TenantMySqlCheckpointer } from '../../infrastructure/persistence/mysql-checkpointer';
import { configuredQuestionModel } from '../../ai/models/legal-model';
import { parseDocument } from '../../documents/parsers/document.parser';
import {
  runContractReview,
  contractIdentity,
  validateContractInput,
} from './contract-review.graph';
import { infrastructureError } from '../../infrastructure/errors/workflow-infrastructure-error';

/**
 * 合同消费者调用的工作流适配层，负责连接 Java 协议与 LangGraph 运行能力。
 * 通过 begin 认领任务并核对不可变文件版本/摘要，模型、下载与检索都从已授权的当前任务上下文构造。
 * 图可以保存业务分析结果，但凭据和外部函数不进入检查点；每个关键步骤再回 Java 确认授权仍有效。
 * 本类不保存 Java 业务报告，也不决定 ACK，正常或失败结果都由消费者回调后处理。
 */
@Injectable()
export class ContractWorkflowService implements OnModuleDestroy {
  // 连接池按需建立；模型 Key 只存在于单次 run 的局部变量中。
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }
  /**
   * 认领合同审查并注入模型、解析、检索与实时授权检查。
   * 校验固定文件版本、摘要、attempt 和认领前后 stateVersion，拒绝把旧队列事件套用到新来源。
   * claimed 在模型配置/图执行前通知消费者当前认领版本，供后续成功或失败回调使用。
   * parse 通过任务专用下载接口取原件，并用预期摘要调用解析器；retrieve 只向 Java 请求当前授权的知识依据。
   *
   * @param task 已与消息核对的 Java 任务上下文，不能由客户端直接指定下载位置。
   * @param claimed 更新消费者持有的 RUNNING 上下文，不代表图已完成。
   * @returns 审查图的待人工复核或完成结果，调用者负责回传 Java。
   * @throws 内容、凭据或复核决定变化时终止，不自动换用新输入继续旧图。
   */
  async run(task: TaskContext, claimed: (task: TaskContext) => void) {
    this.pool ??= workflowPool(workflowDatabaseUrl());
    const input = await this.callback.contractInput(task, true);
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.attempt !== task.attempt ||
      input.fileVersionId !== task.resourceVersionId ||
      input.sha256 !== task.sha256 ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    ) {
      throw new Error('审查输入与任务不符');
    }
    validateContractInput(input);
    // begin 已认领任务，后续回调和授权复查使用 Java 返回的当前状态版本。
    const current = { ...task, status: 'RUNNING', stateVersion: input.stateVersion };
    claimed(current);
    let model;
    try {
      model = configuredQuestionModel(input, allowedModelHosts());
    } catch (error) {
      throw infrastructureError(error, '审查模型配置无效');
    }
    const inputHash = contractIdentity(input);
    return runContractReview(input, new TenantMySqlCheckpointer(this.pool, input.tenantId), {
      model,
      // 每个外部步骤及最终回传前复查固定输入、凭据和复核决定，撤权后停止工作流。
      assertAuthorized: async () => {
        const live = await this.callback.contractInput(current);
        if (
          contractIdentity(live) !== inputHash ||
          live.apiKey !== input.apiKey ||
          live.decisionId !== input.decisionId
        ) {
          throw new Error('审查输入已变化');
        }
      },
      parse: async () => {
        const bytes = await this.callback.downloadSource(current);
        return (await parseDocument(bytes, input.mimeType, input.sha256)).text;
      },
      retrieve: (query) => this.callback.contractEvidence(current, query),
    });
  }
}
