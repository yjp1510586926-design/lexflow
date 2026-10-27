import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { TaskCallbackService, TaskContext } from '../services/task-callback.service';
import { workflowPool } from './persistence/workflow-pool';
import { TenantMySqlCheckpointer } from './persistence/mysql-checkpointer';
import { configuredQuestionModel } from '../ai/legal-question';
import { parseDocument } from '../parsers/document.parser';
import {
  runContractReview,
  contractIdentity,
  validateContractInput,
} from './contract-review.graph';
import { infrastructureError } from './workflow-infrastructure-error';

@Injectable()
export class ContractWorkflowService implements OnModuleDestroy {
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async run(task: TaskContext, claimed: (task: TaskContext) => void) {
    this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
    const input = await this.callback.contractInput(task, true);
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.attempt !== task.attempt ||
      input.fileVersionId !== task.resourceVersionId ||
      input.sha256 !== task.sha256 ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    )
      throw new Error('审查输入与任务不符');
    validateContractInput(input);
    // begin 已认领任务，后续回调和授权复查使用 Java 返回的当前状态版本。
    const current = { ...task, status: 'RUNNING', stateVersion: input.stateVersion };
    claimed(current);
    let model;
    try {
      model = configuredQuestionModel(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '');
    } catch (error) {
      throw infrastructureError(error, '审查模型配置无效');
    }
    const hash = contractIdentity(input);
    return runContractReview(input, new TenantMySqlCheckpointer(this.pool, input.tenantId), {
      model,
      // 每个外部步骤及最终回传前复查固定输入、凭据和复核决定，撤权后停止工作流。
      assertAuthorized: async () => {
        const live = await this.callback.contractInput(current);
        if (
          contractIdentity(live) !== hash ||
          live.apiKey !== input.apiKey ||
          live.decisionId !== input.decisionId
        )
          throw new Error('审查输入已变化');
      },
      parse: async () => {
        const bytes = await this.callback.downloadSource(current);
        return (await parseDocument(bytes, input.mimeType, input.sha256)).text;
      },
      retrieve: (query) => this.callback.contractEvidence(current, query),
    });
  }
}
