import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { HttpModule } from '@nestjs/axios';
import { HealthController } from './health/health.controller';
import { DocumentParseConsumer } from './consumers/document-parse.consumer';
import { ContractReviewConsumer } from './consumers/contract-review.consumer';
import { KnowledgeIndexConsumer } from './consumers/knowledge-index.consumer';
import { KnowledgeSearchController } from './rag/knowledge-search.controller';
import { LegalQuestionController } from './ai/legal-question.controller';
import { KnowledgeRerankController } from './rag/knowledge-rerank.controller';
import { TaskCallbackService } from './services/task-callback.service';
import { ContractWorkflowService } from './graphs/contract-workflow.service';
import { ConfigurationTestController } from './ai/configuration-test.controller';
import { ConfigurationTestService } from './ai/configuration-test.service';
import { DocumentGenerationConsumer } from './consumers/document-generation.consumer';
import { DocumentGenerationWorkflowService } from './graphs/document-generation-workflow.service';
import { KnowledgeIndexWorkflowService } from './rag/knowledge-index-workflow.service';
import { TaskDeliveryService } from './transport/task-delivery.service';
import { IndexRecoveryEligibilityController } from './rag/index-recovery-eligibility.controller';
import { IndexRecoveryEligibilityService } from './rag/index-recovery-eligibility.service';
import { CaseAnalysisConsumer } from './consumers/case-analysis.consumer';
import { CaseAnalysisWorkflowService } from './graphs/case-analysis-workflow.service';
import { WorkflowRecoveryEligibilityController } from './graphs/workflow-recovery-eligibility.controller';
import { WorkflowRecoveryEligibilityService } from './graphs/workflow-recovery-eligibility.service';
import { KnowledgeVectorRetirementController } from './rag/knowledge-vector-retirement.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    HttpModule.register({
      timeout: 30000,
      maxRedirects: 3,
    }),
  ],
  controllers: [
    HealthController,
    DocumentParseConsumer,
    ContractReviewConsumer,
    CaseAnalysisConsumer,
    KnowledgeIndexConsumer,
    KnowledgeSearchController,
    LegalQuestionController,
    KnowledgeRerankController,
    ConfigurationTestController,
    DocumentGenerationConsumer,
    IndexRecoveryEligibilityController,
    WorkflowRecoveryEligibilityController,
    KnowledgeVectorRetirementController,
  ],
  providers: [
    TaskCallbackService,
    ContractWorkflowService,
    ConfigurationTestService,
    DocumentGenerationWorkflowService,
    KnowledgeIndexWorkflowService,
    TaskDeliveryService,
    IndexRecoveryEligibilityService,
    CaseAnalysisWorkflowService,
    WorkflowRecoveryEligibilityService,
  ],
})
export class AppModule {}
