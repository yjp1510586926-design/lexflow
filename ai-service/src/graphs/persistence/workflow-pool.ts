import { createPool, Pool } from 'mysql2/promise';

/** Credentials belong to deployment configuration, never to a graph state or public request. */
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
  )
    throw new Error('AI 工作流必须使用独立的 lexflow_ai 数据库及账号');
  if (decodeURIComponent(url.username) === 'root' || decodeURIComponent(url.username) === 'lexflow')
    throw new Error('AI 工作流不能使用 root 或 Java 业务账号');
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
    supportBigNumbers: true,
    bigNumberStrings: true,
    timezone: 'Z',
    charset: 'utf8mb4',
    multipleStatements: false,
  });
}
