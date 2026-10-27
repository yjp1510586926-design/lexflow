import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { TaskCallbackService } from '../services/task-callback.service';
import { configuredQuestionModel } from '../ai/legal-question';
import { workflowPool } from './persistence/workflow-pool';
import { TenantMySqlCheckpointer } from './persistence/mysql-checkpointer';
import {
  GenerationInput,
  generationIdentity,
  runDocumentGeneration,
  validateGenerationInput,
} from './document-generation.graph';
import { infrastructureError } from './workflow-infrastructure-error';

@Injectable()
export class DocumentGenerationWorkflowService implements OnModuleDestroy {
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async run(task: GenerationInput, claimed: (task: GenerationInput) => void) {
    this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
    const input = await this.callback.generationInput(task, true);
    if (
      input.tenantId !== task.tenantId ||
      input.taskId !== task.taskId ||
      input.attempt !== task.attempt ||
      generationIdentity(input) !== generationIdentity(task) ||
      !(input.stateVersion === task.stateVersion || input.stateVersion === task.stateVersion + 1)
    )
      throw new Error('生成输入与任务不符');
    // 认领后的输入仍须匹配原草稿和配置指纹，避免旧任务套用新的起草要求。
    validateGenerationInput(input);
    claimed(input);
    const hash = generationIdentity(input);
    let model;
    try {
      model = configuredQuestionModel(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '', {
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
        if (generationIdentity(live) !== hash || live.apiKey !== input.apiKey)
          throw new Error('生成输入或模型配置已变化');
      },
    });
  }
}
