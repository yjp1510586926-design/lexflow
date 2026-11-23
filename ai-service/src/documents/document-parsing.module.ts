import { Module } from '@nestjs/common';
import { TaskMessagingModule } from '../infrastructure/messaging/task-messaging.module';
import { DocumentParseConsumer } from './document-parse.consumer';

/**
 * 注册独立的文件解析消息入口，并通过共享模块取得 Java 回调与消息恢复能力。
 * parsers 中的函数只依赖传入的文件字节和摘要，合同审查等调用方可直接复用，
 * 无需注册或触发文书解析消费者，也无需建立模型连接。
 */
@Module({
  imports: [TaskMessagingModule],
  controllers: [DocumentParseConsumer],
})
export class DocumentParsingModule {}
