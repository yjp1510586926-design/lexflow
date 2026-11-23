import { Module } from '@nestjs/common';
import { ConfigurationTestController } from './configuration-test.controller';
import { ConfigurationTestService } from './configuration-test.service';
import { LegalQuestionController } from './legal-question.controller';

/** 注册资料问答和主动连接测试。models 下的供应商适配器也供其他功能调用。 */
@Module({
  controllers: [LegalQuestionController, ConfigurationTestController],
  providers: [ConfigurationTestService],
})
export class AiModule {}
