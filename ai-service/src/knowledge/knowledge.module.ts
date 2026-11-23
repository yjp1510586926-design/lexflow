import { Module } from '@nestjs/common';
import { TaskMessagingModule } from '../infrastructure/messaging/task-messaging.module';
import { IndexRecoveryEligibilityController } from './index-recovery-eligibility.controller';
import { IndexRecoveryEligibilityService } from './index-recovery-eligibility.service';
import { KnowledgeIndexConsumer } from './knowledge-index.consumer';
import { KnowledgeIndexWorkflowService } from './knowledge-index-workflow.service';
import { KnowledgeRerankController } from './knowledge-rerank.controller';
import { KnowledgeSearchController } from './knowledge-search.controller';
import { KnowledgeVectorRetirementController } from './knowledge-vector-retirement.controller';

/**
 * 将索引消费、检索、重排、精确向量回收和索引恢复检查作为同一知识功能注册。
 * 只有索引消费者通过 TaskMessagingModule 注入 Java 回调与消息投递服务。
 * 索引执行和恢复检查只服务本模块的入口，因此无需暴露给其他 NestJS 模块。
 */
@Module({
  imports: [TaskMessagingModule],
  controllers: [
    KnowledgeIndexConsumer,
    KnowledgeSearchController,
    KnowledgeRerankController,
    KnowledgeVectorRetirementController,
    IndexRecoveryEligibilityController,
  ],
  providers: [KnowledgeIndexWorkflowService, IndexRecoveryEligibilityService],
})
export class KnowledgeModule {}
