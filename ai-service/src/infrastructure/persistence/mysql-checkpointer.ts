/**
 * LangGraph 的独立 AI MySQL 持久化适配器。
 * checkpoint 保存图的状态与进度；checkpoint_write 保存尚未合并的节点写入；operation 保存外部操作的意图和结果。
 * 执行器应以 withThreadLock 包住整个图调用；AsyncLocalStorage 将锁身份传给内部异步写入，租约令牌阻止旧执行者提交。
 * 数据库事务只能保护本地记录，不能与远程模型形成原子事务，因此 STARTED 遗留记录必须按结果不确定处理。
 */
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
import { classifyModelFailure, sanitizeModelFailureCode } from '../../ai/models/model-failure';

/** 从 RunnableConfig 提取的存储定位；tenant 始终使用当前存储器绑定值。 */
interface Scope {
  /** SQL 使用的完整线程名；历史枚举允许不指定，其他操作通常必须提供。 */
  thread?: string;
  /** SQL 存储命名空间，可能含合同轮次前缀，区别于框架配置中的原始值。 */
  namespace?: string;
  /** 具体快照编号；读取时省略通常表示选择最新快照。 */
  checkpoint?: string;
}
/** 当前异步执行链的锁身份；令牌可阻止旧执行者在失锁后借其他连接写入。 */
interface Invocation {
  /** 当前执行归属的固定租户，与存储器绑定租户比较。 */
  tenant: string;
  /** 整个异步调用链所属工作流线程，不能借同一上下文写其他线程。 */
  thread: string;
  /** 本次执行的随机令牌，写事务与 workflow_thread 比较；不是按时间自动续期的锁。 */
  token: string;
  /** 回调退出后置 false，防止遗留异步任务继续使用已结束的执行身份。 */
  active: boolean;
  /** 查询原命名锁持有者和当前租约令牌，连接失效或被替换时抛错。 */
  assertHeld: () => Promise<void>;
}
// AsyncLocalStorage 使图内部异步调用共享执行身份，无需把令牌写入图状态。
const executionContext = new AsyncLocalStorage<Invocation>();
interface SavedRow extends RowDataPacket {
  thread_id: string;
  checkpoint_ns: string;
  checkpoint_id: string;
  /** 上一检查点编号，恢复元组时重建 parentConfig。 */
  parent_checkpoint_id: string | null;
  /** 框架序列化类型，配合 checkpoint_blob 解码主快照。 */
  checkpoint_type: string;
  checkpoint_blob: Buffer;
  metadata_type: string;
  /** 框架元数据及显式输入指纹，不保存整个 RunnableConfig。 */
  metadata_blob: Buffer;
}
interface WriteRow extends RowDataPacket {
  /** LangGraph 内部节点任务编号，用于 pendingWrites，而非 Java 业务任务号。 */
  task_id: string;
  /** 本次节点返回值写入的状态通道名称。 */
  channel_name: string;
  /** 通道值的序列化类型标识，配合 value_blob 解码。 */
  value_type: string;
  value_blob: Buffer;
}
interface OperationRow extends RowDataPacket {
  /** 固定任务身份与本步输入组成的摘要，复用前必须完全相同。 */
  input_hash: string;
  /** STARTED/SUCCEEDED/FAILED 等本地状态；遗留 STARTED 不能自动当成未执行。 */
  status: string;
  /** SUCCEEDED 时保存的结果序列化类型，缺失结果不能视为可复用成功。 */
  result_type: string | null;
  result_blob: Buffer | null;
  /** FAILED 只保存本地白名单分类，不保存服务商原始异常文本。 */
  error_code: string | null;
}

/** 与 Java Long 的正数范围一致，避免 JavaScript number 造成大整数精度丢失。 */
function decimalId(value: string): string {
  if (
    typeof value !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  ) {
    throw new Error('工作流身份标识无效');
  }
  return value;
}

/**
 * 构造业务工作流线程名 lf:tenantId:taskId，供图执行、日志和只读恢复探针共同定位。
 * 参数必须来自已认证租户和 Java 已保存的任务；格式校验本身不等于业务授权。
 * 两段 ID 均保持十进制字符串并检查 Java Long 正数上限，避免大整数精度损失。
 *
 * @returns 同一租户任务稳定不变的线程名，不包含 attempt 或 stateVersion。
 */
export function workflowThreadId(tenantId: string, taskId: string): string {
  return `lf:${decimalId(tenantId)}:${decimalId(taskId)}`;
}

/**
 * 构造主动模型连接测试线程名 lft:tenantId:testId。
 * 前缀与业务任务的 lf 隔离，即使测试编号恰好等于任务编号，也不会共享操作日志或执行身份。
 *
 * @param tenantId 已认证租户的十进制正 Long 编号。
 * @param testId Java 生成并保存的连接测试编号。
 * @returns 供连接测试存储器使用的稳定线程名。
 */
export function configurationTestThreadId(tenantId: string, testId: string): string {
  return `lft:${decimalId(tenantId)}:${decimalId(testId)}`;
}

const credentialFieldNames = new Set([
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

/**
 * 递归拒绝对象、Map 和 Set 中已知的凭据字段名，字段匹配忽略大小写。
 * WeakSet 避免循环引用反复遍历，深度上限防止异常嵌套；Uint8Array 作为二进制值跳过内容检查。
 * 它检查的是字段约定，不是通用秘密识别器：普通字符串、二进制值或未知字段中的秘密仍可能漏过。
 * 因此模型 Key 必须留在闭包中，供应商原始异常也不能作为状态或操作结果传入。
 */
function rejectCredentials(value: unknown, seen = new WeakSet<object>(), depth = 0): void {
  if (!value || typeof value !== 'object') {
    return;
  }
  if (depth > 100) {
    throw new Error('工作流状态层级过深');
  }
  if (seen.has(value)) {
    return;
  }
  seen.add(value);
  if (value instanceof Uint8Array) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (credentialFieldNames.has(key.toLowerCase())) {
      throw new Error('工作流状态不得保存凭据');
    }
    rejectCredentials(child, seen, depth + 1);
  }
  if (value instanceof Map) {
    for (const [key, child] of value) {
      if (typeof key === 'string' && credentialFieldNames.has(key.toLowerCase())) {
        throw new Error('工作流状态不得保存凭据');
      }
      rejectCredentials(key, seen, depth + 1);
      rejectCredentials(child, seen, depth + 1);
    }
  }
  if (value instanceof Set) {
    for (const child of value) {
      rejectCredentials(child, seen, depth + 1);
    }
  }
}

/** 对命名空间、快照编号等内部字符串标识做长度和控制字符校验。 */
function identifier(value: unknown, max: number, empty = false): string {
  if (
    typeof value !== 'string' ||
    value.length > max ||
    (!empty && !value.length) ||
    /[\u0000-\u001f\u007f]/.test(value) ||
    ['__proto__', 'constructor', 'prototype'].includes(value)
  ) {
    throw new Error('工作流存储标识无效');
  }
  return value;
}

/** 同一租户任务已有执行者持有命名锁，调用方应稍后读取状态。 */
export class WorkflowBusyError extends Error {
  constructor() {
    super('该工作流正在执行，请稍后刷新');
  }
}

/** 远程调用可能已经完成，但本地没有可确认的结果；禁止自动重发。 */
export class WorkflowOperationUncertainError extends Error {
  constructor() {
    super('上次操作的结果不确定，需要人工确认后再决定是否重新发起');
  }
}
/** 已记录的失败只暴露本地白名单分类码，不保留服务商异常消息。 */
export class WorkflowOperationFailedError extends Error {
  readonly errorCode: string;
  constructor(errorCode: unknown = 'CALL_FAILED') {
    super('该操作已失败，不会自动重复调用');
    this.errorCode = sanitizeModelFailureCode(errorCode);
  }
}
/** 同一版本操作键对应不同输入摘要，旧结果不能复用。 */
export class WorkflowOperationConflictError extends Error {
  constructor() {
    super('同一工作流操作不能更换输入');
  }
}

/**
 * 将 LangGraph 的保存/恢复协议映射到独立 AI 数据库，实例固定绑定一个租户。
 * 所有定位都检查 tenant_id 和线程前缀，防止调用配置把当前实例切换到其他租户。
 * 普通快照读写实现 BaseCheckpointSaver 接口；runOnce 和 withThreadLock 是本项目补充的外部操作保护层。
 * 执行图时由调用方使用同步持久化，并以线程锁包住整次执行；runOnce 会强制检查自己处于该锁的异步上下文中。
 * 只接受 v4 主快照；不会保存整个 RunnableConfig，避免运行时模型配置随参数一起持久化。
 */
export class TenantMySqlCheckpointer extends BaseCheckpointSaver {
  readonly tenantId: string;
  constructor(
    private readonly pool: Pool,
    tenantId: string,
    private readonly namespacePrefix = '',
  ) {
    super();
    this.tenantId = decimalId(tenantId);
    if (namespacePrefix && !/^[a-z][a-z0-9-]{0,79}$/.test(namespacePrefix)) {
      throw new Error('工作流存储分区无效');
    }
  }
  /**
   * 为同一租户和连接池创建带业务分区前缀的存储器，合同补材轮次用它隔离快照。
   * LangGraph 可能重置顶层 checkpoint_ns，因此前缀在 SQL 定位中展开，而不是依赖图始终传回同一命名空间。
   * 分区只影响快照/待写入定位，不替代线程身份和执行锁；不同轮次的操作键仍须由调用方另行区分。
   *
   * @param namespace 非空、满足内部分区格式的名称，例如 contract-round-1。
   * @returns 新存储器实例，共享原连接池和租户，不创建数据库连接或表。
   */
  forNamespace(namespace: string): TenantMySqlCheckpointer {
    if (!namespace) {
      throw new Error('工作流存储分区无效');
    }
    return new TenantMySqlCheckpointer(this.pool, this.tenantId, namespace);
  }

  /**
   * 把 LangGraph 配置转换为经过校验的 SQL 定位，不接受配置切换当前租户。
   * 线程名须属于绑定租户，命名空间与快照编号受长度/字符约束；有业务前缀时合成为“前缀|框架命名空间”。
   * list 历史允许省略线程，其他操作通常要求线程明确，未提供的命名空间仍保留 undefined 供调用者决定筛选行为。
   *
   * @param config 框架调用传入的定位配置；这里只读取身份字段，不保存其余运行时参数。
   * @param requireThread 是否强制存在有效线程，历史枚举时为 false。
   * @returns 内部 Scope，namespace 已包含业务前缀，不能直接作为框架配置返回。
   */
  private scope(config: RunnableConfig, requireThread = true): Scope {
    const configurable = config?.configurable;
    if (configurable?.tenant_id !== this.tenantId) {
      throw new Error('工作流租户不匹配');
    }
    const thread = configurable.thread_id;
    if (requireThread || thread !== undefined) {
      this.thread(thread);
    }
    const namespace = configurable.checkpoint_ns;
    if (namespace !== undefined) {
      identifier(namespace, 255, true);
    }
    const checkpoint = configurable.checkpoint_id;
    if (checkpoint !== undefined) {
      identifier(checkpoint, 64);
    }
    const storedNamespace = this.namespacePrefix
      ? `${this.namespacePrefix}|${namespace ?? ''}`
      : namespace;
    if (storedNamespace !== undefined) {
      identifier(storedNamespace, 255, true);
    }
    return { thread, namespace: storedNamespace, checkpoint };
  }

  /**
   * 校验线程名格式、所属租户及任务/测试编号范围。
   * lf 对应业务任务，lft 对应主动配置测试；两种前缀均必须嵌入当前存储器租户编号。
   * 编号使用十进制字符串并按 Java Long 正数范围处理，避免转换为 number 丢失精度。
   */
  private thread(threadId: unknown): asserts threadId is string {
    identifier(threadId, 80);
    const match = /^lft?:([1-9][0-9]{0,18}):([1-9][0-9]{0,18})$/.exec(threadId as string);
    if (!match || match[1] !== this.tenantId) {
      throw new Error('工作流线程不属于当前租户');
    }
    decimalId(match[2]);
  }

  /**
   * 由数据库定位重建只含身份字段的 RunnableConfig。
   * 带业务分区的存储器先验证并剥离 SQL 前缀，再把框架自己的 checkpoint_ns 返回给 LangGraph。
   * 不尝试重建调用时的模型、回调函数或凭据，恢复执行需要由服务层重新提供运行时能力。
   */
  private config(thread: string, namespace: string, checkpoint: string): RunnableConfig {
    if (this.namespacePrefix) {
      const prefix = this.namespacePrefix + '|';
      if (!namespace.startsWith(prefix)) {
        throw new Error('工作流快照分区不匹配');
      }
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

  /**
   * 在任何写库之前检查已知敏感字段，再使用框架序列化器生成“类型 + 字节”二元组。
   * 限制的是序列化后的实际字节数，和业务输入的 UTF-16 字符长度限制不是同一概念。
   * 敏感字段检查不能识别藏在任意普通字符串或二进制数据中的 Key，调用方仍要确保状态和结果不含凭据。
   *
   * @param value 显式状态、元数据、通道值或外部操作结果；不能传整个运行配置。
   * @param maxBytes 当前记录类别允许的字节上限。
   * @returns 序列化类型名与独立 Buffer，供对应表的 type/blob 列存储。
   * @throws 敏感字段、过深结构、无效类型名或超出字节上限时拒绝保存。
   */
  private async serialize(value: unknown, maxBytes: number): Promise<[string, Buffer]> {
    rejectCredentials(value);
    const [type, bytes] = await this.serde.dumpsTyped(value);
    identifier(type, 32);
    if (bytes.byteLength > maxBytes) {
      throw new Error('工作流状态超过存储上限');
    }
    return [type, Buffer.from(bytes)];
  }

  /**
   * 为一批本地写入提供事务，并在存在执行上下文时验证锁身份和租约。
   * 1. 开始前检查上下文仍 active 且属于当前租户/线程，并检查原锁连接仍持锁。
   * 2. 写事务对 workflow_thread 行取共享锁，比较 lease_token，阻止新执行者替换后旧调用继续提交。
   * 3. operation 完成后再检查命名锁，成功才 commit；任一异常尽力 rollback，最后释放写连接。
   * 命名锁连接与写事务连接不同：前者覆盖整次图调用，后者只覆盖一次数据库变更，不跨远程模型请求持有事务。
   *
   * @param scope 已由 scope() 核验的目标线程定位。
   * @param operation 只使用传入连接执行本次原子数据库变更的函数。
   * @returns operation 的返回值；本方法不会把供应商请求与数据库组成分布式事务。
   */
  private async writeTransaction<T>(
    scope: Scope,
    operation: (connection: PoolConnection) => Promise<T>,
  ): Promise<T> {
    const owner = executionContext.getStore();
    if (
      owner &&
      (!owner.active || owner.tenant !== this.tenantId || owner.thread !== scope.thread)
    ) {
      throw new Error('工作流写入执行身份已失效');
    }
    if (owner) {
      await owner.assertHeld();
    }
    const connection = await this.pool.getConnection();
    try {
      await connection.beginTransaction();
      if (owner) {
        const [rows] = await connection.execute<RowDataPacket[]>(
          `SELECT lease_token FROM workflow_thread
          WHERE tenant_id=? AND thread_id=? FOR SHARE`,
          [this.tenantId, scope.thread!],
        );
        if (rows[0]?.lease_token !== owner.token || !owner.active) {
          throw new Error('工作流写入已被新的执行者替代');
        }
      }
      const result = await operation(connection);
      if (owner) {
        await owner.assertHeld();
      }
      await connection.commit();
      return result;
    } catch (error) {
      await connection.rollback().catch(() => undefined);
      throw error;
    } finally {
      connection.release();
    }
  }

  /**
   * 从一条主快照记录还原 LangGraph 所需的 CheckpointTuple。
   * 待写入数据按 task_id/write_idx 稳定读取并解码；主快照只接受 v4，元数据与父检查点定位也一并还原。
   * pendingWrites 不在此手动覆盖 channel_values，而是交给 LangGraph 按其恢复规则处理。
   *
   * @param row 已按当前租户和线程定位读取的主快照行。
   * @returns 当前配置、主快照、元数据、待写入，以及存在时的父配置。
   */
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
    if (checkpoint.v !== 4) {
      throw new Error('工作流快照版本不受支持');
    }
    const pendingWrites: CheckpointPendingWrite[] = [];
    for (const write of writes) {
      pendingWrites.push([
        write.task_id,
        write.channel_name,
        await this.serde.loadsTyped(write.value_type, write.value_blob),
      ]);
    }
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

  /**
   * 读取一个可恢复单元，供 LangGraph 或执行器判断是首次执行还是恢复执行。
   * 有 checkpoint_id 时读取该快照，否则按编号倒序选当前线程/命名空间中的最新一条。
   * 数据库读取本身不自动取得线程锁；图执行器应在 withThreadLock 中调用，避免读取后被并发执行改变。
   *
   * @param config 至少包含当前租户及合法线程，可选择命名空间和快照编号。
   * @returns 匹配的 CheckpointTuple，没有记录则返回 undefined，不创建初始快照。
   */
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

  /**
   * 按稳定游标枚举当前租户的历史快照，可进一步限制线程、命名空间和编号。
   * SQL 每批取 50 条，游标由 checkpoint_id/thread_id/checkpoint_ns 共同组成，避免 offset 分页的重复扫描。
   * metadata filter 在反序列化后按自有字段和深度相等判断，因此 limit 统计的是实际通过筛选的记录数。
   *
   * @param config 租户必须匹配；允许不指定线程以枚举该租户内的快照。
   * @param options before 限制更早的检查点，limit 为 0–1000，filter 为元数据筛选对象。
   * @returns 异步生成恢复单元；没有记录、到达 limit 或最后一批结束时停止。
   */
  async *list(
    config: RunnableConfig,
    options: CheckpointListOptions = {},
  ): AsyncGenerator<CheckpointTuple> {
    const scope = this.scope(config, false);
    const limit = options.limit ?? 50;
    if (!Number.isInteger(limit) || limit < 0 || limit > 1000) {
      throw new Error('工作流历史数量无效');
    }
    if (limit === 0) {
      return;
    }
    const before = options.before ? this.scope(options.before, false).checkpoint : undefined;
    if (
      options.filter !== undefined &&
      (!options.filter || typeof options.filter !== 'object' || Array.isArray(options.filter))
    ) {
      throw new Error('工作流历史筛选无效');
    }
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
      if (!rows.length) {
        return;
      }
      for (const row of rows) {
        cursor = row;
        const metadata = await this.serde.loadsTyped(row.metadata_type, row.metadata_blob);
        if (
          options.filter &&
          !Object.entries(options.filter).every(
            ([key, value]) =>
              Object.hasOwn(metadata, key) && isDeepStrictEqual(metadata[key], value),
          )
        ) {
          continue;
        }
        yield await this.tuple(row);
        if (++returned >= limit) {
          return;
        }
      }
      if (rows.length < 50) {
        return;
      }
    }
  }

  /**
   * 实现 LangGraph 主快照保存接口，节点进度与父快照关系存放在 workflow_checkpoint。
   * 先复制显式快照并与元数据并行序列化，再进入写事务；同一定位重复保存按主键更新。
   * 快照最多 8 MiB，元数据最多 60000 字节；RunnableConfig 不整体保存，运行时能力必须由下一次执行重新注入。
   *
   * @param config 目标线程定位；其中 checkpoint_id 作为本次快照的父编号。
   * @param checkpoint 框架生成的 v4 快照，含通道值及节点执行进度。
   * @param metadata 框架元数据与本项目输入指纹等显式信息。
   * @param _newVersions 框架接口参数；当前实现直接保存整个快照，不按通道版本差分存储。
   * @returns 只包含本次检查点身份的新配置，可用于后续待写入保存。
   */
  async put(
    config: RunnableConfig,
    checkpoint: Checkpoint,
    metadata: CheckpointMetadata,
    _newVersions: ChannelVersions,
  ): Promise<RunnableConfig> {
    const scope = this.scope(config);
    identifier(checkpoint.id, 64);
    if (checkpoint.v !== 4) {
      throw new Error('仅支持新的 v4 工作流快照');
    }
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

  /**
   * 保存节点已产出的通道值，使节点结果可以早于下一份完整快照单独持久化。
   * 必须指定目标检查点编号，对应主快照可以尚未落库。先完整校验并序列化所有值，再在一个事务中写入。
   * WRITES_IDX_MAP 为框架特殊通道指定负序号，这些记录可覆盖；普通非负序号在重复插入时保留首次写入。
   *
   * @param config 当前主快照定位，待写入记录归属此快照。
   * @param writes 最多 200 个 channel/value 对，每个值最多 8 MiB。
   * @param taskId LangGraph 内部节点任务标识，用于区分同一检查点的写入；不等同于 Java 业务 taskId。
   * @returns 全部写入完成后返回；空列表不启动写事务。
   */
  async putWrites(config: RunnableConfig, writes: PendingWrite[], taskId: string): Promise<void> {
    const scope = this.scope(config);
    if (!scope.checkpoint) {
      throw new Error('待提交写入缺少快照编号');
    }
    identifier(taskId, 128);
    if (!Array.isArray(writes) || writes.length > 200) {
      throw new Error('工作流写入数量无效');
    }
    // 先完整校验并序列化，再开启写事务，任一值无效都不会留下部分记录。
    const prepared = await Promise.all(
      writes.map(async ([channel, value], index) => {
        identifier(channel, 255);
        const [type, bytes] = await this.serialize(value, 8 * 1024 * 1024);
        return { channel, index: WRITES_IDX_MAP[channel] ?? index, type, bytes };
      }),
    );
    if (!prepared.length) {
      return;
    }
    await this.writeTransaction(scope, async (connection) => {
      for (const write of prepared) {
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
      }
    });
  }

  /**
   * 在当前线程锁内登记并执行一个外部操作，操作日志独立于图快照保存。
   * 状态处理如下：无记录时先提交 STARTED，再执行 operation；SUCCEEDED 且摘要相同则直接解码缓存；
   * FAILED 维持已失败状态；遗留 STARTED 或缺失成功结果按 UNCERTAIN 拒绝复用，不自动重新调用。
   * 同一 key 的输入摘要变化会抛 Conflict；因此 key 应包含步骤/协议版本，inputHash 应涵盖固定输入和当前依赖。
   * operation 返回后先序列化并把结果保存为 SUCCEEDED，节点随后才把返回值写进图状态，覆盖“模型成功、快照未提交”窗口。
   * 调用或结果序列化失败时仅登记本地分类码；登记失败或成功提交无法确认时返回不确定异常。
   * 本地记录无法证明远端是否恰好执行一次，超时、断线和进程崩溃都不能据此认定没有产生费用。
   *
   * @param config 当前租户、线程身份，必须位于 withThreadLock 的有效异步上下文。
   * @param key 同一任务内稳定的操作标识，例如 case-facts-v1，不应随消息投递次数改变。
   * @param inputHash 64 个字符的 SHA-256 十六进制输入摘要，不能包含 API Key。
   * @param operation 真正的外部动作；只有首次合法操作才执行，不在数据库事务中等待远端。
   * @returns 首次操作的已保存结果或同输入的成功缓存，结果本身也须不含凭据。
   * @throws Busy 以外的操作失败、输入冲突或结果不确定异常由消费者分别处理，不能统一视为可重试。
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
    ) {
      throw new Error('工作流操作标识或输入摘要无效');
    }
    const owner = executionContext.getStore();
    if (!owner?.active || owner.tenant !== this.tenantId || owner.thread !== scope.thread) {
      throw new Error('外部操作必须在当前工作流执行锁内调用');
    }
    const identity = [this.tenantId, scope.thread!, key];
    const cached = await this.writeTransaction(scope, async (connection) => {
      const [rows] = await connection.execute<OperationRow[]>(
        `SELECT input_hash,status,result_type,result_blob,error_code
        FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND operation_key=? FOR UPDATE`,
        identity,
      );
      if (rows.length) {
        const row = rows[0];
        if (row.input_hash !== inputHash) {
          throw new WorkflowOperationConflictError();
        }
        if (row.status === 'SUCCEEDED' && row.result_type && row.result_blob) {
          return row;
        }
        if (row.status === 'FAILED') {
          throw new WorkflowOperationFailedError(row.error_code);
        }
        throw new WorkflowOperationUncertainError();
      }
      await connection.execute(
        `INSERT INTO workflow_operation(tenant_id,thread_id,operation_key,input_hash,status,owner_token)
        VALUES(?,?,?,?,'STARTED',?)`,
        [...identity, inputHash, owner.token],
      );
      return undefined;
    });
    if (cached) {
      return (await this.serde.loadsTyped(cached.result_type!, cached.result_blob!)) as T;
    }
    let serialized: [string, Buffer];
    let value: T;
    try {
      await owner.assertHeld();
      value = await operation();
      serialized = await this.serialize(value, 2 * 1024 * 1024);
    } catch (error) {
      // 仅保存白名单分类码；服务商响应、异常消息与凭据均不进入操作日志。
      const errorCode = classifyModelFailure(error);
      try {
        await this.writeTransaction(scope, (connection) =>
          connection.execute(
            `UPDATE workflow_operation
          SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(6)
          WHERE tenant_id=? AND thread_id=? AND operation_key=? AND input_hash=? AND status='STARTED' AND owner_token=?`,
            [errorCode, ...identity, inputHash, owner.token],
          ),
        );
      } catch {
        throw new WorkflowOperationUncertainError();
      }
      throw new WorkflowOperationFailedError(errorCode);
    }
    try {
      await this.writeTransaction(scope, async (connection) => {
        const [result] = await connection.execute<import('mysql2').ResultSetHeader>(
          `UPDATE workflow_operation
          SET status='SUCCEEDED',result_type=?,result_blob=?,finished_at=CURRENT_TIMESTAMP(6)
          WHERE tenant_id=? AND thread_id=? AND operation_key=? AND input_hash=? AND status='STARTED' AND owner_token=?`,
          [...serialized, ...identity, inputHash, owner.token],
        );
        if (result.affectedRows !== 1) {
          throw new WorkflowOperationUncertainError();
        }
      });
    } catch {
      throw new WorkflowOperationUncertainError();
    }
    return value;
  }

  /**
   * 为整个图执行/恢复取得跨进程线程锁，并建立本次执行的独立租约身份。
   * GET_LOCK(...,0) 不等待：已有执行者时抛 WorkflowBusyError，调用方不应再启动第二个相同任务。
   * 锁连接一直保留到回调结束，租约令牌另存 workflow_thread；assertHeld 同时验证连接持锁者和当前令牌。
   * AsyncLocalStorage 只传播本次执行身份，writeTransaction 可在其他池连接上据此拒绝已失锁的旧调用。
   * finally 先使上下文失效，再尽力清除自己的令牌、释放命名锁及连接；它不能撤回已发出的远程模型请求。
   *
   * @param config 当前租户与合法线程，任务和主动配置测试使用不同前缀。
   * @param operation 整次受保护的图操作，收到的 assertHeld 可在关键外部动作前后主动复查。
   * @returns 回调结果；返回前还会检查锁是否仍有效，失效时不会按正常完成返回。
   */
  async withThreadLock<T>(
    config: RunnableConfig,
    operation: (assertHeld: () => Promise<void>) => Promise<T>,
  ): Promise<T> {
    const scope = this.scope(config);
    const lockName =
      'lf_graph_' +
      createHash('sha256').update(`${this.tenantId}:${scope.thread}`).digest('hex').slice(0, 48);
    const connection = await this.pool.getConnection();
    let acquired = false;
    let context: Invocation | undefined;
    try {
      const [rows] = await connection.execute<RowDataPacket[]>(
        'SELECT GET_LOCK(?,0) AS acquired, CONNECTION_ID() AS owner',
        [lockName],
      );
      if (String(rows[0].acquired) !== '1') {
        throw new WorkflowBusyError();
      }
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
        if (context && !context.active) {
          throw new Error('工作流执行身份已结束');
        }
        const [state] = await connection.execute<RowDataPacket[]>(
          `SELECT IS_USED_LOCK(?) AS owner,
          (SELECT lease_token FROM workflow_thread WHERE tenant_id=? AND thread_id=?) AS token`,
          [lockName, this.tenantId, scope.thread!],
        );
        if (String(state[0].owner) !== owner || state[0].token !== token) {
          throw new Error('工作流执行锁已失效');
        }
      };
      context = { tenant: this.tenantId, thread: scope.thread!, token, active: true, assertHeld };
      await assertHeld();
      const result = await executionContext.run(context, () => operation(assertHeld));
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
      if (acquired) {
        await connection.execute('SELECT RELEASE_LOCK(?)', [lockName]).catch(() => undefined);
      }
      connection.release();
    }
  }

  /**
   * 清理一个租户线程的本地工作流记录，删除过程与该线程执行互斥。
   * 锁内同一事务依次删除操作日志、待写入和主快照；锁退出并清除自身租约后，才清理无租约的线程行。
   * 删除的是 AI 工作流记录，不删除 Java 业务任务，也不能取消已发出的模型请求；调用方必须先决定业务侧是否允许清理。
   *
   * @param threadId 属于当前租户的 lf/lft 线程，无法借此删除其他租户记录。
   * @throws 线程非法、当前正被占用或数据库失败时抛错，不能假定清理已成功。
   */
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
