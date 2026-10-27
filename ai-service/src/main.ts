import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { MicroserviceOptions, Transport } from '@nestjs/microservices';
import { AppModule } from './app.module';
import { taskTransport } from './transport/task-transport';
import { NestExpressApplication } from '@nestjs/platform-express';
import { configureInternalJsonBody } from './http-body';

async function bootstrap() {
  // HTTP 服务（内部健康检查 + 内部回调接口）
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bodyParser: false });
  configureInternalJsonBody(app);
  const port = Number(process.env.AI_PORT ?? 3001);

  // RabbitMQ 微服务消费者（同一进程，混合应用模式）
  const rmqUrl = process.env.RABBITMQ_URL ?? 'amqp://lexflow:lexflow_mq_dev_only@127.0.0.1:15672';
  const selectedQueues = (
    process.env.AI_TASK_QUEUES ??
    'document.parse,contract.review,knowledge.ingest,document.generate,case.analysis'
  )
    .split(',')
    .map((q) => q.trim());
  const allowedQueues = new Set([
    'document.parse',
    'contract.review',
    'knowledge.ingest',
    'document.generate',
    'case.analysis',
  ]);
  if (
    !selectedQueues.length ||
    selectedQueues.some((q) => !allowedQueues.has(q)) ||
    new Set(selectedQueues).size !== selectedQueues.length
  )
    throw new Error('AI_TASK_QUEUES 包含无效或重复的队列');
  for (const queue of selectedQueues)
    app.connectMicroservice<MicroserviceOptions>({
      transport: Transport.RMQ,
      options: {
        urls: [rmqUrl],
        ...taskTransport(`lexflow.q.${queue}`),
        noAck: false, // 手动 ACK，处理成功后才 ACK
        prefetchCount: 1, // 每次只取一条，避免消息积压 OOM
      },
    });

  await app.startAllMicroservices();
  await app.listen(port, process.env.AI_HOST ?? '127.0.0.1');
  console.log(`[lexflow-ai] HTTP listening on :${port} | RMQ consumers connected`);
}

bootstrap().catch((e) => {
  console.error(e);
  process.exit(1);
});
