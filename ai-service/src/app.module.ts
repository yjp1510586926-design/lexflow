import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { AiModule } from './ai/ai.module';
import { DocumentParsingModule } from './documents/document-parsing.module';
import { HealthController } from './health/health.controller';
import { KnowledgeModule } from './knowledge/knowledge.module';
import { WorkflowsModule } from './workflows/workflows.module';

/**
 * NestJS 根模块负责装配应用。各功能模块在自己的目录注册 HTTP 入口、消息消费者和执行服务。
 * 跨功能共用的 Java 回调和消息投递由 TaskMessagingModule 提供，通过显式 imports 注入。
 */
@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    DocumentParsingModule,
    WorkflowsModule,
    KnowledgeModule,
    AiModule,
  ],
  controllers: [HealthController],
})
export class AppModule {}
