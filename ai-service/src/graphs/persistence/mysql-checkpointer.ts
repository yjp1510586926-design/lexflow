import {
  BaseCheckpointSaver,
  Checkpoint,
  CheckpointMetadata,
  CheckpointTuple,
  CheckpointListOptions,
  ChannelVersions,
  PendingWrite,
  CheckpointPendingWrite,
  WRITES_IDX_MAP,
  copyCheckpoint,
} from '@langchain/langgraph-checkpoint';
import { RunnableConfig } from '@langchain/core/runnables';
import { Pool, PoolConnection, RowDataPacket } from 'mysql2/promise';
import { createHash, randomUUID } from 'node:crypto';
import { AsyncLocalStorage } from 'node:async_hooks';
import { isDeepStrictEqual } from 'node:util';

interface Scope {
  thread?: string;
  namespace?: string;
  checkpoint?: string;
}
interface Invocation {
  tenant: string;
  thread: string;
  token: string;
  active: boolean;
  assertHeld: () => Promise<void>;
}
const invocation = new AsyncLocalStorage<Invocation>();
interface SavedRow extends RowDataPacket {
  thread_id: string;
  checkpoint_ns: string;
  checkpoint_id: string;
  parent_checkpoint_id: string | null;
  checkpoint_type: string;
  checkpoint_blob: Buffer;
  metadata_type: string;
  metadata_blob: Buffer;
}
interface WriteRow extends RowDataPacket {
  task_id: string;
  channel_name: string;
  value_type: string;
  value_blob: Buffer;
}
interface OperationRow extends RowDataPacket {
  input_hash: string;
  status: string;
  result_type: string | null;
  result_blob: Buffer | null;
}

function decimalId(value: string): string {
  if (
    typeof value !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error('工作流身份标识无效');
  return value;
}

/** 线程身份由已认证租户和 Java 已落库任务组成，不能由外部请求任意指定。 */
export function workflowThreadId(tenantId: string, taskId: string): string {
  return `lf:${decimalId(tenantId)}:${decimalId(taskId)}`;
}

/** 连接测试使用独立线程前缀，数值 ID 与任务相同也不会共用操作日志。 */
export function configurationTestThreadId(tenantId: string, testId: string): string {
  return `lft:${decimalId(tenantId)}:${decimalId(testId)}`;
}

const secretKeys = new Set([
  'apikey',
  'api_key',
  'encryptedkey',
  'encrypted_key',
  'password',
  'authorization',
  'cookie',
  'internal_secret',
  'internalsecret',
  'databaseurl',
  'database_url',
]);

/** 运行时凭据只留在模型闭包中；序列化前递归拒绝状态与元数据中的敏感字段。 */
function rejectCredentials(value: unknown, seen = new WeakSet<object>(), depth = 0): void {
  if (!value || typeof value !== 'object') return;
  if (depth > 100) throw new Error('工作流状态层级过深');
  if (seen.has(value)) return;
  seen.add(value);
  if (value instanceof Uint8Array) return;
  for (const [key, child] of Object.entries(value)) {
    if (secretKeys.has(key.toLowerCase())) throw new Error('工作流状态不得保存凭据');
    rejectCredentials(child, seen, depth + 1);
  }
  if (value instanceof Map)
    for (const [key, child] of value) {
      if (typeof key === 'string' && secretKeys.has(key.toLowerCase()))
        throw new Error('工作流状态不得保存凭据');
      rejectCredentials(key, seen, depth + 1);
      rejectCredentials(child, seen, depth + 1);
    }
  if (value instanceof Set) for (const child of value) rejectCredentials(child, seen, depth + 1);
}

function identifier(value: unknown, max: number, empty = false): string {
  if (
    typeof value !== 'string' ||
    value.length > max ||
    (!empty && !value.length) ||
    /[\u0000-\u001f\u007f]/.test(value) ||
    ['__proto__', 'constructor', 'prototype'].includes(value)
  )
    throw new Error('工作流存储标识无效');
  return value;
}

export class WorkflowBusyError extends Error {
  constructor() {
    super('该工作流正在执行，请稍后刷新');
  }
}

export class WorkflowOperationUncertainError extends Error {
  constructor() {
    super('上次操作的结果不确定，需要人工确认后再决定是否重新发起');
  }
}
export class WorkflowOperationFailedError extends Error {
  constructor() {
    super('该操作已失败，不会自动重复调用');
  }
}
export class WorkflowOperationConflictError extends Error {
  constructor() {
    super('同一工作流操作不能更换输入');
  }
}

/** 所有读写绑定当前租户和线程；仅支持新的 v4 快照格式。 */
export class TenantMySqlCheckpointer extends BaseCheckpointSaver {
  readonly tenantId: string;
  constructor(
    private readonly pool: Pool,
    tenantId: string,
    private readonly namespacePrefix = '',
  ) {
    super();
    this.tenantId = decimalId(tenantId);
    if (namespacePrefix && !/^[a-z][a-z0-9-]{0,79}$/.test(namespacePrefix))
      throw new Error('工作流存储分区无效');
  }
  /** LangGraph 会重置顶层命名空间，故在 SQL 存储层增加前缀来隔离各轮快照。 */
  forNamespace(namespace: string): TenantMySqlCheckpointer {
    if (!namespace) throw new Error('工作流存储分区无效');
    return new TenantMySqlCheckpointer(this.pool, this.tenantId, namespace);
  }

  private scope(config: RunnableConfig, requireThread = true): Scope {
    const c = config?.configurable;
    if (c?.tenant_id !== this.tenantId) throw new Error('工作流租户不匹配');
    const thread = c.thread_id;
    if (requireThread || thread !== undefined) this.thread(thread);
    const namespace = c.checkpoint_ns;
    if (namespace !== undefined) identifier(namespace, 255, true);
    const checkpoint = c.checkpoint_id;
    if (checkpoint !== undefined) identifier(checkpoint, 64);
    const storedNamespace = this.namespacePrefix
      ? `${this.namespacePrefix}|${namespace ?? ''}`
      : namespace;
    if (storedNamespace !== undefined) identifier(storedNamespace, 255, true);
    return { thread, namespace: storedNamespace, checkpoint };
  }

  private thread(threadId: unknown): asserts threadId is string {
    identifier(threadId, 80);
    const match = /^lft?:([1-9][0-9]{0,18}):([1-9][0-9]{0,18})$/.exec(threadId as string);
    if (!match || match[1] !== this.tenantId) throw new Error('工作流线程不属于当前租户');
    decimalId(match[2]);
  }

  private config(thread: string, namespace: string, checkpoint: string): RunnableConfig {
    if (this.namespacePrefix) {
      const prefix = this.namespacePrefix + '|';
      if (!namespace.startsWith(prefix)) throw new Error('工作流快照分区不匹配');
      namespace = namespace.slice(prefix.length);
    }
    return {
      configurable: {
        tenant_id: this.tenantId,
        thread_id: thread,
        checkpoint_ns: namespace,
        checkpoint_id: checkpoint,
      },
    };
  }

  private async serialize(value: unknown, maxBytes: number): Promise<[string, Buffer]> {
    rejectCredentials(value);
    const [type, bytes] = await this.serde.dumpsTyped(value);
    identifier(type, 32);
    if (bytes.byteLength > maxBytes) throw new Error('工作流状态超过存储上限');
    return [type, Buffer.from(bytes)];
  }

  /** 写事务同时检查执行锁和租约令牌，阻止锁连接失效后的旧执行者继续提交。 */
  private async writeTransaction<T>(
    scope: Scope,
    operation: (connection: PoolConnection) => Promise<T>,
  ): Promise<T> {
    const owner = invocation.getStore();
    if (owner && (!owner.active || owner.tenant !== this.tenantId || owner.thread !== scope.thread))
      throw new Error('工作流写入执行身份已失效');
    if (owner) await owner.assertHeld();
    const connection = await this.pool.getConnection();
    try {
      await connection.beginTransaction();
      if (owner) {
        const [rows] = await connection.execute<RowDataPacket[]>(
          `SELECT lease_token FROM workflow_thread
          WHERE tenant_id=? AND thread_id=? FOR SHARE`,
          [this.tenantId, scope.thread!],
        );
        if (rows[0]?.lease_token !== owner.token || !owner.active)
          throw new Error('工作流写入已被新的执行者替代');
      }
      const result = await operation(connection);
      if (owner) await owner.assertHeld();
      await connection.commit();
      return result;
    } catch (error) {
      await connection.rollback().catch(() => undefined);
      throw error;
    } finally {
      connection.release();
    }
  }

  private async tuple(row: SavedRow): Promise<CheckpointTuple> {
    const [writes] = await this.pool.execute<WriteRow[]>(
      `SELECT task_id,channel_name,value_type,value_blob
      FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=? AND checkpoint_ns=? AND checkpoint_id=?
      ORDER BY task_id,write_idx`,
      [this.tenantId, row.thread_id, row.checkpoint_ns, row.checkpoint_id],
    );
    const checkpoint = (await this.serde.loadsTyped(
      row.checkpoint_type,
      row.checkpoint_blob,
    )) as Checkpoint;
    if (checkpoint.v !== 4) throw new Error('工作流快照版本不受支持');
    const pendingWrites: CheckpointPendingWrite[] = [];
    for (const write of writes)
      pendingWrites.push([
        write.task_id,
        write.channel_name,
        await this.serde.loadsTyped(write.value_type, write.value_blob),
      ]);
    return {
      config: this.config(row.thread_id, row.checkpoint_ns, row.checkpoint_id),
      checkpoint,
      metadata: await this.serde.loadsTyped(row.metadata_type, row.metadata_blob),
      pendingWrites,
      ...(row.parent_checkpoint_id
        ? { parentConfig: this.config(row.thread_id, row.checkpoint_ns, row.parent_checkpoint_id) }
        : {}),
    };
  }

  async getTuple(config: RunnableConfig): Promise<CheckpointTuple | undefined> {
    const scope = this.scope(config);
    const values: string[] = [this.tenantId, scope.thread!, scope.namespace ?? ''];
    let sql =
      'SELECT * FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=? AND checkpoint_ns=?';
    if (scope.checkpoint) {
      sql += ' AND checkpoint_id=?';
      values.push(scope.checkpoint);
    }
    sql += ' ORDER BY checkpoint_id DESC LIMIT 1';
    const [rows] = await this.pool.execute<SavedRow[]>(sql, values);
    return rows.length ? this.tuple(rows[0]) : undefined;
  }

  async *list(
    config: RunnableConfig,
    options: CheckpointListOptions = {},
  ): AsyncGenerator<CheckpointTuple> {
    const scope = this.scope(config, false);
    const limit = options.limit ?? 50;
    if (!Number.isInteger(limit) || limit < 0 || limit > 1000)
      throw new Error('工作流历史数量无效');
    if (limit === 0) return;
    const before = options.before ? this.scope(options.before, false).checkpoint : undefined;
    if (
      options.filter !== undefined &&
      (!options.filter || typeof options.filter !== 'object' || Array.isArray(options.filter))
    )
      throw new Error('工作流历史筛选无效');
    let cursor: SavedRow | undefined;
    let returned = 0;
    while (returned < limit) {
      const values: string[] = [this.tenantId];
      let sql = 'SELECT * FROM workflow_checkpoint WHERE tenant_id=?';
      for (const [column, value] of [
        ['thread_id', scope.thread],
        ['checkpoint_ns', scope.namespace],
        ['checkpoint_id', scope.checkpoint],
      ] as const) {
        if (value !== undefined) {
          sql += ` AND ${column}=?`;
          values.push(value);
        }
      }
      if (before) {
        sql += ' AND checkpoint_id<?';
        values.push(before);
      }
      if (cursor) {
        sql += ' AND (checkpoint_id,thread_id,checkpoint_ns)<(?,?,?)';
        values.push(cursor.checkpoint_id, cursor.thread_id, cursor.checkpoint_ns);
      }
      sql += ' ORDER BY checkpoint_id DESC,thread_id DESC,checkpoint_ns DESC LIMIT 50';
      const [rows] = await this.pool.execute<SavedRow[]>(sql, values);
      if (!rows.length) return;
      for (const row of rows) {
        cursor = row;
        const metadata = await this.serde.loadsTyped(row.metadata_type, row.metadata_blob);
        if (
          options.filter &&
          !Object.entries(options.filter).every(
            ([key, value]) =>
              Object.hasOwn(metadata, key) && isDeepStrictEqual(metadata[key], value),
          )
        )
          continue;
        yield await this.tuple(row);
        if (++returned >= limit) return;
      }
      if (rows.length < 50) return;
    }
  }

  async put(
    config: RunnableConfig,
    checkpoint: Checkpoint,
    metadata: CheckpointMetadata,
    _newVersions: ChannelVersions,
  ): Promise<RunnableConfig> {
    const scope = this.scope(config);
    identifier(checkpoint.id, 64);
    if (checkpoint.v !== 4) throw new Error('仅支持新的 v4 工作流快照');
    // 仅序列化显式状态与元数据，RunnableConfig 可能带有运行时凭据，不能整包落库。
    const [saved, meta] = await Promise.all([
      this.serialize(copyCheckpoint(checkpoint), 8 * 1024 * 1024),
      this.serialize(metadata, 60000),
    ]);
    await this.writeTransaction(scope, (connection) =>
      connection.execute(
        `INSERT INTO workflow_checkpoint(tenant_id,thread_id,checkpoint_ns,checkpoint_id,
      parent_checkpoint_id,checkpoint_type,checkpoint_blob,metadata_type,metadata_blob) VALUES(?,?,?,?,?,?,?,?,?)
      ON DUPLICATE KEY UPDATE parent_checkpoint_id=VALUES(parent_checkpoint_id),checkpoint_type=VALUES(checkpoint_type),
        checkpoint_blob=VALUES(checkpoint_blob),metadata_type=VALUES(metadata_type),metadata_blob=VALUES(metadata_blob)`,
        [
          this.tenantId,
          scope.thread!,
          scope.namespace ?? '',
          checkpoint.id,
          scope.checkpoint ?? null,
          saved[0],
          saved[1],
          meta[0],
          meta[1],
        ],
      ),
    );
    return this.config(scope.thread!, scope.namespace ?? '', checkpoint.id);
  }

  async putWrites(config: RunnableConfig, writes: PendingWrite[], taskId: string): Promise<void> {
    const scope = this.scope(config);
    if (!scope.checkpoint) throw new Error('待提交写入缺少快照编号');
    identifier(taskId, 128);
    if (!Array.isArray(writes) || writes.length > 200) throw new Error('工作流写入数量无效');
    // 先完整校验并序列化，再开启写事务，任一值无效都不会留下部分记录。
    const prepared = await Promise.all(
      writes.map(async ([channel, value], index) => {
        identifier(channel, 255);
        const [type, bytes] = await this.serialize(value, 8 * 1024 * 1024);
        return { channel, index: WRITES_IDX_MAP[channel] ?? index, type, bytes };
      }),
    );
    if (!prepared.length) return;
    await this.writeTransaction(scope, async (connection) => {
      for (const write of prepared)
        await connection.execute(
          `INSERT INTO workflow_checkpoint_write(
        tenant_id,thread_id,checkpoint_ns,checkpoint_id,task_id,write_idx,channel_name,value_type,value_blob)
        VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE ` +
            (write.index < 0
              ? 'channel_name=VALUES(channel_name),value_type=VALUES(value_type),value_blob=VALUES(value_blob)'
              : 'write_idx=write_idx'),
          [
            this.tenantId,
            scope.thread!,
            scope.namespace ?? '',
            scope.checkpoint!,
            taskId,
            write.index,
            write.channel,
            write.type,
            write.bytes,
          ],
        );
    });
  }

  /**
   * 操作预留和结果独立于图快照保存，填补外部调用成功、快照尚未提交的中断窗口。
   * STARTED 先提交再调用外部服务；遗留 STARTED 无法证明远端是否完成，不自动再次调用。
   * 操作键带步骤版本，摘要包含固定输入和模型身份；凭据不进入摘要或保存结果。
   * 同一输入的成功结果可复用，失败结果保持失败；重新发起须由人工明确决定。
   */
  async runOnce<T>(
    config: RunnableConfig,
    key: string,
    inputHash: string,
    operation: () => Promise<T>,
  ): Promise<T> {
    const scope = this.scope(config);
    if (
      typeof key !== 'string' ||
      !/^[a-z][a-z0-9._-]{0,63}$/.test(key) ||
      typeof inputHash !== 'string' ||
      !/^[a-f0-9]{64}$/.test(inputHash)
    )
      throw new Error('工作流操作标识或输入摘要无效');
    const owner = invocation.getStore();
    if (!owner?.active || owner.tenant !== this.tenantId || owner.thread !== scope.thread)
      throw new Error('外部操作必须在当前工作流执行锁内调用');
    const identity = [this.tenantId, scope.thread!, key];
    const cached = await this.writeTransaction(scope, async (connection) => {
      const [rows] = await connection.execute<OperationRow[]>(
        `SELECT input_hash,status,result_type,result_blob
        FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND operation_key=? FOR UPDATE`,
        identity,
      );
      if (rows.length) {
        const row = rows[0];
        if (row.input_hash !== inputHash) throw new WorkflowOperationConflictError();
        if (row.status === 'SUCCEEDED' && row.result_type && row.result_blob) return row;
        if (row.status === 'FAILED') throw new WorkflowOperationFailedError();
        throw new WorkflowOperationUncertainError();
      }
      await connection.execute(
        `INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token)
        VALUES(?,?,?,?,'STARTED',?)`,
        [...identity, inputHash, owner.token],
      );
      return undefined;
    });
    if (cached) return (await this.serde.loadsTyped(cached.result_type!, cached.result_blob!)) as T;
    let serialized: [string, Buffer];
    let value: T;
    try {
      await owner.assertHeld();
      value = await operation();
      serialized = await this.serialize(value, 2 * 1024 * 1024);
    } catch {
      // 仅保存固定失败码，服务商响应、异常消息与凭据均不进入操作日志。
      try {
        await this.writeTransaction(scope, (connection) =>
          connection.execute(
            `UPDATE workflow_operation
          SET status='FAILED',error_code='CALL_FAILED',finished_at=CURRENT_TIMESTAMP(6)
          WHERE tenant_id=? AND thread_id=? AND operation_key=? AND input_hash=? AND status='STARTED' AND owner_token=?`,
            [...identity, inputHash, owner.token],
          ),
        );
      } catch {
        throw new WorkflowOperationUncertainError();
      }
      throw new WorkflowOperationFailedError();
    }
    try {
      await this.writeTransaction(scope, async (connection) => {
        const [result] = await connection.execute<import('mysql2').ResultSetHeader>(
          `UPDATE workflow_operation
          SET status='SUCCEEDED',result_type=?,result_blob=?,finished_at=CURRENT_TIMESTAMP(6)
          WHERE tenant_id=? AND thread_id=? AND operation_key=? AND input_hash=? AND status='STARTED' AND owner_token=?`,
          [...serialized, ...identity, inputHash, owner.token],
        );
        if (result.affectedRows !== 1) throw new WorkflowOperationUncertainError();
      });
    } catch {
      throw new WorkflowOperationUncertainError();
    }
    return value;
  }

  /** 整个调用期间持有 MySQL 命名锁，使独立 Node 进程也不能同时执行同一线程。 */
  async withThreadLock<T>(
    config: RunnableConfig,
    operation: (assertHeld: () => Promise<void>) => Promise<T>,
  ): Promise<T> {
    const scope = this.scope(config);
    const name =
      'lf_graph_' +
      createHash('sha256').update(`${this.tenantId}:${scope.thread}`).digest('hex').slice(0, 48);
    const connection = await this.pool.getConnection();
    let acquired = false;
    let context: Invocation | undefined;
    try {
      const [rows] = await connection.execute<RowDataPacket[]>(
        'SELECT GET_LOCK(?,0) AS acquired, CONNECTION_ID() AS owner',
        [name],
      );
      if (String(rows[0].acquired) !== '1') throw new WorkflowBusyError();
      acquired = true;
      const owner = String(rows[0].owner);
      // 命名锁随连接失效而释放；独立令牌用于阻止旧执行者借其他健康连接继续写入。
      const token = randomUUID();
      await connection.execute(
        `INSERT INTO workflow_thread(tenant_id,thread_id,lease_token) VALUES(?,?,?)
        ON DUPLICATE KEY UPDATE lease_token=VALUES(lease_token)`,
        [this.tenantId, scope.thread!, token],
      );
      const assertHeld = async () => {
        if (context && !context.active) throw new Error('工作流执行身份已结束');
        const [state] = await connection.execute<RowDataPacket[]>(
          `SELECT IS_USED_LOCK(?) AS owner,
          (SELECT lease_token FROM workflow_thread WHERE tenant_id=? AND thread_id=?) AS token`,
          [name, this.tenantId, scope.thread!],
        );
        if (String(state[0].owner) !== owner || state[0].token !== token)
          throw new Error('工作流执行锁已失效');
      };
      context = { tenant: this.tenantId, thread: scope.thread!, token, active: true, assertHeld };
      await assertHeld();
      const result = await invocation.run(context, () => operation(assertHeld));
      await assertHeld();
      return result;
    } finally {
      if (context) {
        context.active = false;
        await connection
          .execute(
            `UPDATE workflow_thread SET lease_token=NULL WHERE tenant_id=? AND thread_id=? AND lease_token=?`,
            [this.tenantId, scope.thread!, context.token],
          )
          .catch(() => undefined);
      }
      if (acquired)
        await connection.execute('SELECT RELEASE_LOCK(?)', [name]).catch(() => undefined);
      connection.release();
    }
  }

  async deleteThread(threadId: string): Promise<void> {
    this.thread(threadId);
    await this.withThreadLock(
      { configurable: { tenant_id: this.tenantId, thread_id: threadId } },
      async () => {
        await this.writeTransaction({ thread: threadId }, async (connection) => {
          await connection.execute(
            'DELETE FROM workflow_operation WHERE tenant_id=? AND thread_id=?',
            [this.tenantId, threadId],
          );
          await connection.execute(
            'DELETE FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=?',
            [this.tenantId, threadId],
          );
          await connection.execute(
            'DELETE FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=?',
            [this.tenantId, threadId],
          );
        });
      },
    );
    await this.pool.execute(
      'DELETE FROM workflow_thread WHERE tenant_id=? AND thread_id=? AND lease_token IS NULL',
      [this.tenantId, threadId],
    );
  }
}
