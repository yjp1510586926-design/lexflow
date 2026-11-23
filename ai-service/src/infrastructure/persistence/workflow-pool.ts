import { createPool, Pool } from 'mysql2/promise';

/**
 * 创建独立 AI 工作流连接池，连接配置只能来自部署环境。
 * 要求 mysql URL、账号密码和受限的 lexflow_ai 库名，拒绝 root 与 Java 默认业务账号，避免误连业务库。
 * 校验限制的是配置格式和约定账号，不替代数据库权限隔离；部署仍应只授予独立 AI 库的必要权限。
 * 连接池返回不表示已连通数据库或表已初始化，真正使用时仍可能因网络、凭据或缺表失败。
 *
 * @param databaseUrl AI_WORKFLOW_DATABASE_URL，不能从公开业务请求或图状态取得。
 * @returns 最多 6 个连接、等待队列有界的池；调用方负责在生命周期结束时 end()。
 * @throws URL 或隔离约定不满足时立即抛错，不创建数据库或用户。
 */
export function workflowPool(databaseUrl: string | undefined): Pool {
  let url: URL;
  try {
    url = new URL(databaseUrl ?? '');
  } catch {
    throw new Error('未配置 AI 工作流数据库');
  }
  const database = url.pathname.slice(1);
  if (
    url.protocol !== 'mysql:' ||
    !url.username ||
    !url.password ||
    url.search ||
    url.hash ||
    database.length > 64 ||
    !/^lexflow_ai(?:_[a-z0-9_]+)?$/.test(database)
  ) {
    throw new Error('AI 工作流必须使用独立的 lexflow_ai 数据库及账号');
  }
  if (
    decodeURIComponent(url.username) === 'root' ||
    decodeURIComponent(url.username) === 'lexflow'
  ) {
    throw new Error('AI 工作流不能使用 root 或 Java 业务账号');
  }
  return createPool({
    host: url.hostname,
    port: Number(url.port || 3306),
    user: decodeURIComponent(url.username),
    password: decodeURIComponent(url.password),
    database,
    connectionLimit: 6,
    waitForConnections: true,
    queueLimit: 30,
    connectTimeout: 10000,
    // Java Long 可能超过 JavaScript 安全整数范围，数据库大整数必须以字符串返回。
    supportBigNumbers: true,
    bigNumberStrings: true,
    timezone: 'Z',
    charset: 'utf8mb4',
    multipleStatements: false,
  });
}
