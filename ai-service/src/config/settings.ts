/**
 * 集中定义 AI 服务的部署配置和开发默认值。
 * 函数在调用时读取环境变量，兼容 NestJS 的配置加载，也可供独立初始化命令使用。
 * 各环境仍由启动进程注入自己的值。租户模型 API Key 来自 Java 的授权输入。
 */
export const DEFAULT_INTERNAL_SECRET = 'dev-internal-secret-change-in-prod';
export const DEFAULT_JAVA_CORE_URL = 'http://127.0.0.1:8080';

const TASK_QUEUE_NAMES = [
  'document.parse',
  'contract.review',
  'knowledge.ingest',
  'document.generate',
  'case.analysis',
];

/**
 * 解析 AI_TASK_QUEUES。未设置时订阅全部任务，显式空项、未知名称和重复名称会阻止启动。
 * @returns 保持配置顺序的短队列名，启动时再补上 lexflow.q. 前缀。
 */
export function configuredTaskQueues(value = process.env.AI_TASK_QUEUES): string[] {
  const selectedQueues = (value ?? TASK_QUEUE_NAMES.join(','))
    .split(',')
    .map((queueName) => queueName.trim());
  const allowedQueues = new Set(TASK_QUEUE_NAMES);
  if (
    selectedQueues.some((queueName) => !allowedQueues.has(queueName)) ||
    new Set(selectedQueues).size !== selectedQueues.length
  ) {
    throw new Error('AI_TASK_QUEUES 包含无效或重复的队列');
  }
  return selectedQueues;
}

export function httpPort(): number {
  return Number(process.env.AI_PORT ?? 3001);
}

export function httpHost(): string {
  return process.env.AI_HOST ?? '127.0.0.1';
}

export function rabbitMqUrl(): string {
  return process.env.RABBITMQ_URL ?? 'amqp://lexflow:lexflow_mq_dev_only@127.0.0.1:15672';
}

export function internalSecret(): string {
  return process.env.INTERNAL_SECRET ?? DEFAULT_INTERNAL_SECRET;
}

/** 空白名单交给模型适配器判为未授权。 */
export function allowedModelHosts(): string {
  return process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '';
}

export function milvusAddress(): string {
  return process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530';
}

/** 工作流存储需要独立数据库，连接池负责校验地址与库名。 */
export function workflowDatabaseUrl(): string | undefined {
  return process.env.AI_WORKFLOW_DATABASE_URL;
}
