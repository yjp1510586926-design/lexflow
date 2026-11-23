// 装饰器元数据供 NestJS 推断构造函数依赖；在创建应用之前加载。
import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { MicroserviceOptions, Transport } from '@nestjs/microservices';
import { AppModule } from './app.module';
import { taskTransport } from './infrastructure/messaging/task-transport';
import { NestExpressApplication } from '@nestjs/platform-express';
import { configureInternalJsonBody } from './config/http-body';
import { configuredTaskQueues, httpHost, httpPort, rabbitMqUrl } from './config/settings';

/**
 * 进程启动入口：先创建 NestJS 应用，再注册消息消费者，最后启动消息与 HTTP 监听。
 * 同一进程同时服务 HTTP 与 RabbitMQ，称为混合应用；两类入口复用 AppModule 的服务。
 * 普通启动只装配运行时能力，不执行工作流表初始化；建表由 setup:workflow 显式完成。
 * 启动失败会交给文件末尾的 catch 退出进程，不把未完成启动伪装成可用服务。
 */
async function bootstrap(): Promise<void> {
  // 关闭默认解析器后按内部接口分别配置请求体大小，详见 http-body.ts。
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bodyParser: false });
  configureInternalJsonBody(app);
  const port = httpPort();
  const rmqUrl = rabbitMqUrl();

  // 每个选中队列注册一个 RMQ 传输端；队列持久化和死信参数统一由 taskTransport 提供。
  for (const queue of configuredTaskQueues()) {
    app.connectMicroservice<MicroserviceOptions>({
      transport: Transport.RMQ,
      options: {
        urls: [rmqUrl],
        ...taskTransport(`lexflow.q.${queue}`),
        noAck: false, // 关闭自动确认，由消费者在回调或投递恢复完成后决定 ACK/NACK。
        prefetchCount: 1, // 每个消费者限制一条未确认消息，约束单消费者的在途处理量。
      },
    });
  }

  // 等待消息传输启动，再开放 HTTP 端口；健康接口本身不主动检测外部依赖。
  await app.startAllMicroservices();
  await app.listen(port, httpHost());
  console.log(`[lexflow-ai] HTTP listening on :${port} | RMQ consumers connected`);
}

bootstrap().catch((error) => {
  console.error(error);
  process.exit(1);
});
