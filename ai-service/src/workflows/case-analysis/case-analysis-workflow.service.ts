import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { allowedModelHosts, workflowDatabaseUrl } from '../../config/settings';
import { TaskCallbackService, TaskContext } from '../../infrastructure/java/task-callback.service';
import { workflowPool } from '../../infrastructure/persistence/workflow-pool';
import { TenantMySqlCheckpointer } from '../../infrastructure/persistence/mysql-checkpointer';
import { configuredQuestionModel } from '../../ai/models/legal-model';
import { runCaseAnalysis, analysisIdentity, validateAnalysisInput } from './case-analysis.graph';
import { infrastructureError } from '../../infrastructure/errors/workflow-infrastructure-error';

/**
 * 案件分析消费者与纯工作流编排之间的适配层。
 * 从 Java begin 接口认领固定输入，确认队列身份与原件版本一致，再创建模型并注入实时授权函数。
 * Java 保留业务数据所有权；本服务只连接独立 AI 工作流库，不直接读取业务表或自行发布报告。
 * 连接池按需创建并在模块销毁时释放，模型 Key 仅存在于单次执行闭包中。
 */
@Injectable()
export class CaseAnalysisWorkflowService implements OnModuleDestroy {
  // 首个任务到来时才建立连接池，应用关闭时统一释放。
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }
  /**
   * 认领并执行一份案件分析，恢复已有任务时也走相同身份与授权复查流程。
   * begin 可能把 stateVersion 推进一次，所以只接受传入版本或紧接着的认领版本，不能任意跳过版本。
   * 校验成功后立刻通过 claimed 回传当前上下文；即使随后模型失败，消费者也能使用正确版本向 Java 回调。
   * 后续每次授权检查重新向 Java 读取固定输入，并分别比较内容指纹、API Key 和 decisionId。
   *
   * @param task 消费者从 Java 获取并与消息核对过的任务上下文。
   * @param claimed 接收认领后 RUNNING 版本的同步回调，不能据此视为业务处理已完成。
   * @returns 分析图的结果；报告发布和 RabbitMQ ACK 留给调用者完成。
   * @throws 输入不匹配、配置不合法或图执行失败时向消费者传播异常。
   */
  async run(task: TaskContext, claimed: (task: TaskContext) => void) {
    this.pool ??= workflowPool(workflowDatabaseUrl());
    const input = await this.callback.analysisInput(task, true);
    // 认领可能已将 QUEUED 推进为 RUNNING；只接受队列版本或紧接着的认领版本。
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.caseId !== task.caseId ||
      input.attempt !== task.attempt ||
      input.sources[0]?.fileVersionId !== task.resourceVersionId ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    ) {
      throw new Error('案件分析输入与任务不符');
    }
    validateAnalysisInput(input);
    // 使用认领后版本回调，并在运行中重新读取 Java 固定输入以发现撤权或配置变更。
    const current = { ...task, status: 'RUNNING', stateVersion: input.stateVersion };
    claimed(current);
    const identity = analysisIdentity(input);
    let model;
    try {
      model = configuredQuestionModel(input, allowedModelHosts());
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
        ) {
          throw new Error('分析权限或固定输入已变化');
        }
      },
    });
  }
}
