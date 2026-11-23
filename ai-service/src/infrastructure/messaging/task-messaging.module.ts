import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { TaskCallbackService } from '../java/task-callback.service';
import { TaskDeliveryService } from './task-delivery.service';

/**
 * 各任务消费者共用的服务装配，集中提供 Java 内部接口客户端和 RabbitMQ 投递恢复服务。
 * 导入该模块后，功能模块通过构造函数注入两个服务。任务队列仍由启动入口注册。
 * HTTP 默认限制用于 Java 客户端，个别请求可按材料大小、响应时限和重定向策略覆盖。
 */
@Module({
  imports: [HttpModule.register({ timeout: 30000, maxRedirects: 3 })],
  providers: [TaskCallbackService, TaskDeliveryService],
  exports: [TaskCallbackService, TaskDeliveryService],
})
export class TaskMessagingModule {}
