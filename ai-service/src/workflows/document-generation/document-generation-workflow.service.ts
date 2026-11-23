import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { allowedModelHosts, workflowDatabaseUrl } from '../../config/settings';
import { TaskCallbackService } from '../../infrastructure/java/task-callback.service';
import { configuredQuestionModel } from '../../ai/models/legal-model';
import { workflowPool } from '../../infrastructure/persistence/workflow-pool';
import { TenantMySqlCheckpointer } from '../../infrastructure/persistence/mysql-checkpointer';
import {
  GenerationInput,
  generationIdentity,
  runDocumentGeneration,
  validateGenerationInput,
} from './document-generation.graph';
import { infrastructureError } from '../../infrastructure/errors/workflow-infrastructure-error';

/**
 * 文书生成任务的运行时装配层。
 * 先向 Java 认领固定来源与起草要求，比较内容指纹，再创建具有文书输出预算的模型。
 * 运行时授权检查会重新读取同一任务输入，拒绝正文要求、模型身份或 Key 已变化后的旧执行。
 * 该类只返回草稿，Java 决定草稿版本如何保存，消费者决定何时确认消息。
 */
@Injectable()
export class DocumentGenerationWorkflowService implements OnModuleDestroy {
  // 延迟建立独立 AI 工作流连接池，避免普通模块装配产生数据库操作。
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }
  /**
   * 认领并执行文书起草，保持队列输入与 Java 当前固定输入一致。
   * 内容指纹包含来源版本、背景、原稿、要求及模型修订；attempt 必须相同，状态版本只允许正常认领推进一次。
   * 通过 claimed 暴露最新输入后再进入图，后续授权复查沿用认领版本，避免迟到回调覆盖其他任务状态。
   *
   * @param task 消费者预先读取并核对的起草输入。
   * @param claimed 接收认领后的 GenerationInput，供生成结果回调使用。
   * @returns 规范化待复核草稿；本方法不会自动采用正文或完成消息确认。
   */
  async run(task: GenerationInput, claimed: (task: GenerationInput) => void) {
    this.pool ??= workflowPool(workflowDatabaseUrl());
    const input = await this.callback.generationInput(task, true);
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.attempt !== task.attempt ||
      generationIdentity(input) !== generationIdentity(task) ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    ) {
      throw new Error('生成输入与任务不符');
    }
    // 认领后的输入仍须匹配原草稿和配置指纹，避免旧任务套用新的起草要求。
    validateGenerationInput(input);
    claimed(input);
    const inputHash = generationIdentity(input);
    let model;
    try {
      model = configuredQuestionModel(input, allowedModelHosts(), {
        maxTokens: 6000,
        timeout: 60000,
      });
    } catch (error) {
      throw infrastructureError(error, '生成模型配置无效');
    }
    return runDocumentGeneration(input, new TenantMySqlCheckpointer(this.pool, input.tenantId), {
      model,
      assertAuthorized: async () => {
        const live = await this.callback.generationInput(input);
        if (generationIdentity(live) !== inputHash || live.apiKey !== input.apiKey) {
          throw new Error('生成输入或模型配置已变化');
        }
      },
    });
  }
}
