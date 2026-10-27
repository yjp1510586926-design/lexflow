import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { TaskCallbackService, TaskContext } from '../services/task-callback.service';
import { workflowPool } from './persistence/workflow-pool';
import { TenantMySqlCheckpointer } from './persistence/mysql-checkpointer';
import { configuredQuestionModel } from '../ai/legal-question';
import { runCaseAnalysis, analysisIdentity, validateAnalysisInput } from './case-analysis.graph';
import { infrastructureError } from './workflow-infrastructure-error';

@Injectable()
export class CaseAnalysisWorkflowService implements OnModuleDestroy {
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async run(task: TaskContext, claimed: (task: TaskContext) => void) {
    this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
    const input = await this.callback.analysisInput(task, true);
    // 认领可能已将 QUEUED 推进为 RUNNING；只接受队列版本或紧接着的认领版本。
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.caseId !== task.caseId ||
      input.attempt !== task.attempt ||
      input.sources[0]?.fileVersionId !== task.resourceVersionId ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    )
      throw new Error('案件分析输入与任务不符');
    validateAnalysisInput(input);
    // 使用认领后版本回调，并在运行中重新读取 Java 固定输入以发现撤权或配置变更。
    const current = { ...task, status: 'RUNNING', stateVersion: input.stateVersion };
    claimed(current);
    const identity = analysisIdentity(input);
    let model;
    try {
      model = configuredQuestionModel(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '');
    } catch (error) {
      throw infrastructureError(error, '案件分析模型配置无效');
    }
    return runCaseAnalysis(input, new TenantMySqlCheckpointer(this.pool, input.tenantId), {
      model,
      // 指纹只验证固定内容；凭据与决定另查，防止撤权或复核记录变化后继续旧执行。
      assertAuthorized: async () => {
        const live = await this.callback.analysisInput(current);
        if (
          analysisIdentity(live) !== identity ||
          live.apiKey !== input.apiKey ||
          live.decisionId !== input.decisionId
        )
          throw new Error('分析权限或固定输入已变化');
      },
    });
  }
}
