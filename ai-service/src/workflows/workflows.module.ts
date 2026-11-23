import { Module } from '@nestjs/common';
import { TaskMessagingModule } from '../infrastructure/messaging/task-messaging.module';
import { CaseAnalysisConsumer } from './case-analysis/case-analysis.consumer';
import { CaseAnalysisWorkflowService } from './case-analysis/case-analysis-workflow.service';
import { ContractReviewConsumer } from './contract-review/contract-review.consumer';
import { ContractWorkflowService } from './contract-review/contract-workflow.service';
import { DocumentGenerationConsumer } from './document-generation/document-generation.consumer';
import { DocumentGenerationWorkflowService } from './document-generation/document-generation-workflow.service';
import { WorkflowRecoveryEligibilityController } from './recovery/workflow-recovery-eligibility.controller';
import { WorkflowRecoveryEligibilityService } from './recovery/workflow-recovery-eligibility.service';

/** 装配三条 LangGraph 工作流的消费者、运行时服务和只读恢复入口。 */
@Module({
  imports: [TaskMessagingModule],
  controllers: [
    ContractReviewConsumer,
    CaseAnalysisConsumer,
    DocumentGenerationConsumer,
    WorkflowRecoveryEligibilityController,
  ],
  providers: [
    ContractWorkflowService,
    CaseAnalysisWorkflowService,
    DocumentGenerationWorkflowService,
    WorkflowRecoveryEligibilityService,
  ],
})
export class WorkflowsModule {}
