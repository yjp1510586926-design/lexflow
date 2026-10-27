'use strict';
// 人工调用的 V1 备份恢复基础工具；恢复只准备离线环境，不启动调度器或模型请求。
const fs = require('node:fs/promises');
const nativeFs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { createRequire } = require('node:module');
const mysql = createRequire(path.resolve(__dirname, '../../ai-service/package.json'))('mysql2');
const REPOSITORY = path.resolve(__dirname, '../..');
const MAGIC = Buffer.from('LFBK0001');
const AI_TABLES = [
  'workflow_checkpoint',
  'workflow_checkpoint_write',
  'workflow_operation',
  'workflow_thread',
];
const MAX_LINE = 75 * 1024 * 1024;
const MAX_ARCHIVE = 1024 * 1024 * 1024;
const MAX_ROWS = 1000000;
const MAX_OBJECT = 50 * 1024 * 1024;
class BackupFailure extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}
const fail = (code) => {
  throw new BackupFailure(code);
};
const digest = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const identifier = (value) =>
  typeof value === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(value)
    ? value
    : fail('invalid_identifier');
const quote = (value) => '`' + identifier(value) + '`';
const fields = (value, expected) => {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !expected.includes(key))
  )
    fail('invalid_fields');
};
// 配置、密钥与明文暂存必须属于当前用户，并使用私有权限，拒绝符号链接。
async function privateDirectory(directory) {
  const info = await fs.lstat(directory);
  if (
    !info.isDirectory() ||
    info.isSymbolicLink() ||
    (info.mode & 0o777) !== 0o700 ||
    info.uid !== process.getuid()
  )
    fail('unsafe_directory');
}
async function privateFile(file, maximum = MAX_ARCHIVE) {
  await privateDirectory(path.dirname(path.resolve(file)));
  const info = await fs.lstat(file);
  if (
    !info.isFile() ||
    info.isSymbolicLink() ||
    (info.mode & 0o777) !== 0o600 ||
    info.uid !== process.getuid() ||
    info.nlink !== 1 ||
    info.size > maximum
  )
    fail('unsafe_file');
  return info;
}
async function readPrivateJson(file) {
  await privateFile(file, 65536);
  const handle = await fs.open(file, nativeFs.constants.O_RDONLY | nativeFs.constants.O_NOFOLLOW);
  try {
    return JSON.parse(await handle.readFile('utf8'));
  } finally {
    await handle.close();
  }
}
async function readKey(file) {
  const info = await privateFile(file, 32);
  if (info.size !== 32) fail('invalid_key');
  const handle = await fs.open(file, nativeFs.constants.O_RDONLY | nativeFs.constants.O_NOFOLLOW);
  try {
    return await handle.readFile();
  } finally {
    await handle.close();
  }
}
async function forceDirectory(directory) {
  const handle = await fs.open(directory, 'r');
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}
async function createPrivate(file) {
  return fs.open(file, 'wx', 0o600);
}
async function keygen(file) {
  await privateDirectory(path.dirname(path.resolve(file)));
  const handle = await createPrivate(file);
  try {
    await handle.writeFile(crypto.randomBytes(32));
    await handle.sync();
  } finally {
    await handle.close();
  }
  await forceDirectory(path.dirname(path.resolve(file)));
}
// 固定本地 MySQL 部署约定，并将大整数与时间按字符串保真导出。
function databaseConfig(config) {
  fields(config, ['host', 'port', 'database', 'user', 'password']);
  if (
    config.host !== '127.0.0.1' ||
    config.port !== 13306 ||
    typeof config.password !== 'string' ||
    !config.password ||
    typeof config.user !== 'string' ||
    !/^[a-z][a-z0-9_]{0,31}$/.test(config.user) ||
    ['root', 'mysql', 'sys'].includes(config.user)
  )
    fail('unsupported_database_configuration');
  identifier(config.database);
  return {
    ...config,
    dateStrings: true,
    supportBigNumbers: true,
    bigNumberStrings: true,
    jsonStrings: true,
    connectTimeout: 5000,
    multipleStatements: false,
    timezone: 'Z',
  };
}
async function connection(config, trustedMigrationStatements = false) {
  const client = mysql.createConnection({
    ...databaseConfig(config),
    multipleStatements: trustedMigrationStatements,
  });
  const db = client.promise();
  try {
    await db.query("SET SESSION time_zone='+00:00', lock_wait_timeout=5");
    const [identity] = await db.query('SELECT DATABASE() db,CURRENT_USER() account');
    if (identity[0].db !== config.database || identity[0].account.split('@')[0] !== config.user)
      fail('database_identity_mismatch');
    const [grants] = await db.query('SHOW GRANTS FOR CURRENT_USER()');
    if (
      grants.some((row) =>
        Object.values(row).some(
          (text) =>
            !/^GRANT USAGE ON \*\.\* /.test(text) &&
            !text.includes(' ON `' + config.database + '`.* TO '),
        ),
      )
    )
      fail('unscoped_database_account');
    return { client, db, config };
  } catch (error) {
    await db.end().catch(() => {});
    throw error;
  }
}
async function tableNames(source) {
  const [rows] = await source.db.query(
    'SELECT TABLE_NAME name,TABLE_TYPE type FROM information_schema.tables WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME',
    [source.config.database],
  );
  if (rows.some((row) => row.type !== 'BASE TABLE')) fail('unsupported_database_objects');
  const [extras] = await source.db.query(
    `SELECT
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=?) +
    (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=?) +
    (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=?) total`,
    Array(3).fill(source.config.database),
  );
  if (Number(extras[0].total) !== 0) fail('unsupported_database_objects');
  return rows.map((row) => identifier(row.name));
}
async function migrationFiles() {
  const directory = path.join(REPOSITORY, 'service/src/main/resources/db/migration');
  const names = (await fs.readdir(directory))
    .filter((name) => /^V[1-9][0-9]*__[^/]+\.sql$/.test(name))
    .sort((a, b) => Number(a.match(/^V(\d+)/)[1]) - Number(b.match(/^V(\d+)/)[1]));
  return Promise.all(
    names.map(async (name, index) => {
      const version = Number(name.match(/^V(\d+)/)[1]);
      if (version !== index + 1) fail('migration_sequence_gap');
      const bytes = await fs.readFile(path.join(directory, name));
      return { name, version, sha256: digest(bytes), sql: bytes.toString('utf8') };
    }),
  );
}
async function expectedBusinessTables(migrations) {
  const tables = new Set(['flyway_schema_history']);
  for (const migration of migrations)
    for (const match of migration.sql.matchAll(
      /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([a-z][a-z0-9_]*)`?/gi,
    ))
      tables.add(identifier(match[1]));
  return [...tables].sort();
}
const normalizeDdl = (ddl) => ddl.replace(/ AUTO_INCREMENT=\d+/g, '');
async function descriptors(source, names, component) {
  const result = [];
  for (const name of names) {
    const [create] = await source.db.query('SHOW CREATE TABLE ' + quote(name));
    const [columns] = await source.db.query(
      'SELECT COLUMN_NAME name,COLUMN_TYPE type,IS_NULLABLE nullable,COLLATION_NAME collation,EXTRA extra FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION',
      [source.config.database, name],
    );
    const [table] = await source.db.query(
      'SELECT AUTO_INCREMENT nextId FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?',
      [source.config.database, name],
    );
    const [primary] = await source.db.query(
      "SELECT COLUMN_NAME name FROM information_schema.statistics WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",
      [source.config.database, name],
    );
    if (!primary.length) fail('unsupported_table_without_primary_key');
    result.push({
      component,
      name,
      columns,
      primary: primary.map((row) => row.name),
      ddlHash: digest(normalizeDdl(create[0]['Create Table'])),
      nextId: table[0].nextId === null ? null : String(table[0].nextId),
    });
  }
  return result;
}
function encodeRow(row) {
  return row.map((value) => {
    if (Buffer.isBuffer(value)) return { binary: value.toString('base64') };
    if (
      value === null ||
      typeof value === 'string' ||
      (typeof value === 'number' && Number.isFinite(value))
    )
      return value;
    return fail('unsupported_database_value');
  });
}
function decodeRow(row, length) {
  if (!Array.isArray(row) || row.length !== length) fail('invalid_row');
  return row.map((value) => {
    if (
      value === null ||
      typeof value === 'string' ||
      (typeof value === 'number' && Number.isFinite(value))
    )
      return value;
    fields(value, ['binary']);
    if (typeof value.binary !== 'string') fail('invalid_row');
    const bytes = Buffer.from(value.binary, 'base64');
    if (bytes.toString('base64') !== value.binary || bytes.length > 16 * 1024 * 1024)
      fail('invalid_row');
    return bytes;
  });
}
// 格式为 MAGIC + 12 字节 IV + 加密 JSONL + 16 字节 GCM 标签，头部同时作为 AAD。
async function encryptedWriter(file, key) {
  await privateDirectory(path.dirname(path.resolve(file)));
  const handle = await createPrivate(file);
  const iv = crypto.randomBytes(12);
  const header = Buffer.concat([MAGIC, iv]);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(header);
  await writeFully(handle, header);
  let size = 0;
  return {
    async frame(value) {
      const bytes = Buffer.from(JSON.stringify(value) + '\n');
      size += bytes.length;
      if (bytes.length > MAX_LINE || size > MAX_ARCHIVE) fail('archive_limit');
      await writeFully(handle, cipher.update(bytes));
    },
    async finish() {
      await writeFully(handle, cipher.final());
      await writeFully(handle, cipher.getAuthTag());
      await handle.sync();
      await handle.close();
      await forceDirectory(path.dirname(path.resolve(file)));
    },
    async abort() {
      await handle.close().catch(() => {});
      await fs.unlink(file).catch(() => {});
    },
  };
}
async function writeFully(handle, bytes) {
  let offset = 0;
  while (offset < bytes.length) {
    const result = await handle.write(bytes, offset, bytes.length - offset);
    if (result.bytesWritten < 1) fail('local_storage_unavailable');
    offset += result.bytesWritten;
  }
}
// 明文只写入私有暂存目录，GCM 认证完成后才返回路径供归档校验。
async function decrypt(file, key, staging) {
  const info = await privateFile(file, MAX_ARCHIVE + 36);
  if (info.size < 36) fail('invalid_archive');
  const source = await fs.open(file, nativeFs.constants.O_RDONLY | nativeFs.constants.O_NOFOLLOW);
  const header = Buffer.alloc(20),
    tag = Buffer.alloc(16);
  await source.read(header, 0, 20, 0);
  await source.read(tag, 0, 16, info.size - 16);
  if (!header.subarray(0, 8).equals(MAGIC)) {
    await source.close();
    fail('unsupported_archive_version');
  }
  const destination = path.join(staging, 'payload.jsonl');
  const output = await createPrivate(destination);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, header.subarray(8));
  decipher.setAAD(header);
  decipher.setAuthTag(tag);
  try {
    for await (const chunk of source.createReadStream({
      start: 20,
      end: info.size - 17,
      autoClose: false,
    }))
      await writeFully(output, decipher.update(chunk));
    await writeFully(output, decipher.final());
    await output.sync();
    return destination;
  } catch (error) {
    fail('archive_authentication_failed');
  } finally {
    await output.close();
    await source.close();
  }
}
function storageConfig(config) {
  fields(config, ['endpoint', 'accessKey', 'secretKey', 'bucket']);
  const url = new URL(config.endpoint);
  if (
    !(
      (url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port === '19000') ||
      url.protocol === 'https:'
    ) ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash ||
    !/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(config.bucket) ||
    !config.accessKey ||
    !config.secretKey ||
    typeof config.accessKey !== 'string' ||
    typeof config.secretKey !== 'string'
  )
    fail('invalid_storage_configuration');
  return config;
}
const awsEncode = (value) =>
  encodeURIComponent(value).replace(
    /[!'()*]/g,
    (character) => '%' + character.charCodeAt(0).toString(16).toUpperCase(),
  );
async function objectRequest(
  config,
  method,
  key = null,
  body = Buffer.alloc(0),
  query = '',
  contentType = null,
) {
  storageConfig(config);
  if (
    key !== null &&
    (typeof key !== 'string' ||
      !key ||
      Buffer.byteLength(key) > 512 ||
      /[\x00-\x1f\x7f]/.test(key) ||
      key.split('/').some((segment) => segment === '.' || segment === '..'))
  )
    fail('invalid_object_key');
  const now = new Date().toISOString().replace(/[:-]|\.\d{3}/g, ''),
    day = now.slice(0, 8);
  const origin = new URL(config.endpoint),
    pathname =
      '/' +
      awsEncode(config.bucket) +
      (key === null ? '' : '/' + key.split('/').map(awsEncode).join('/'));
  const payloadHash = digest(body),
    signed = 'host;x-amz-content-sha256;x-amz-date';
  const canonical = [
    method,
    pathname,
    query,
    `host:${origin.host}\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${now}\n`,
    signed,
    payloadHash,
  ].join('\n');
  const scope = day + '/us-east-1/s3/aws4_request';
  const hmac = (secret, value) => crypto.createHmac('sha256', secret).update(value).digest();
  const signing = hmac(
    hmac(hmac(hmac('AWS4' + config.secretKey, day), 'us-east-1'), 's3'),
    'aws4_request',
  );
  const signature = hmac(
    signing,
    ['AWS4-HMAC-SHA256', now, scope, digest(canonical)].join('\n'),
  ).toString('hex');
  const headers = {
    'x-amz-date': now,
    'x-amz-content-sha256': payloadHash,
    authorization: `AWS4-HMAC-SHA256 Credential=${config.accessKey}/${scope}, SignedHeaders=${signed}, Signature=${signature}`,
  };
  if (contentType) headers['content-type'] = contentType;
  const response = await fetch(origin.origin + pathname + (query ? '?' + query : ''), {
    method,
    headers,
    body: ['GET', 'HEAD'].includes(method) ? undefined : body,
    redirect: 'manual',
    signal: AbortSignal.timeout(10000),
  });
  const permitted = ['GET', 'HEAD'].includes(method)
    ? [200, 404]
    : method === 'PUT'
      ? [200, 204]
      : [200, 204, 404];
  if (!permitted.includes(response.status)) {
    await response.body?.cancel();
    fail('object_storage_unavailable');
  }
  if (!['GET', 'HEAD'].includes(method)) await response.body?.cancel();
  return response;
}
async function objectBytes(response) {
  if (response.status === 404) return null;
  const length = Number(response.headers.get('content-length'));
  if (!Number.isSafeInteger(length) || length < 0 || length > MAX_OBJECT) fail('object_limit');
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > MAX_OBJECT) fail('object_limit');
    chunks.push(chunk);
  }
  if (size !== length) fail('object_integrity_mismatch');
  return Buffer.concat(chunks);
}
module.exports = {
  BackupFailure,
  fail,
  digest,
  fields,
  identifier,
  quote,
  fs,
  path,
  crypto,
  readline,
  nativeFs,
  REPOSITORY,
  AI_TABLES,
  MAX_LINE,
  MAX_ARCHIVE,
  MAX_ROWS,
  MAX_OBJECT,
  privateDirectory,
  privateFile,
  readPrivateJson,
  readKey,
  forceDirectory,
  createPrivate,
  keygen,
  connection,
  tableNames,
  migrationFiles,
  expectedBusinessTables,
  descriptors,
  encodeRow,
  decodeRow,
  encryptedWriter,
  decrypt,
  objectRequest,
  objectBytes,
  storageConfig,
};
