import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Pool, RowDataPacket } from 'mysql2/promise';
import { KNOWLEDGE_EMBEDDING_BATCH_SIZE } from '../ai/models/embedding-model';
import { workflowDatabaseUrl } from '../config/settings';
import type { KnowledgeIndexInput } from '../infrastructure/java/task-callback.service';
import { workflowThreadId } from '../infrastructure/persistence/mysql-checkpointer';
import { workflowPool } from '../infrastructure/persistence/workflow-pool';
import { knowledgeEmbeddingBatchHash, knowledgeIndexIdentity } from './knowledge-index-identity';
import { knowledgeDecimalId, knowledgeScope } from './knowledge-scope';

/**
 * 恢复探针的固定状态，不携带材料、向量、Key 或供应商错误。
 * SAFE：已读到的操作与本次输入匹配且没有失败/不确定记录，也可能还没有任何操作。
 * BUSY：同任务执行锁正被占用；UNCERTAIN：存在 STARTED 等不能确认结果的状态。
 * FAILED：存在明确失败批次；INPUT_CHANGED：批次键或输入摘要与当前输入不符。
 * UNAVAILABLE：数据库、锁释放或其他检查无法可靠完成，不能据此授权重放模型请求。
 * 即使 SAFE，Java 仍须检查当前任务状态、权限、版本和费用确认。
 */
export type IndexRecoveryStatus =
  'SAFE' | 'BUSY' | 'UNCERTAIN' | 'FAILED' | 'INPUT_CHANGED' | 'UNAVAILABLE';
/** Java 为人工恢复前的只读检查提供任务及固定输入；协议明确去除 apiKey。 */
export interface IndexRecoveryRequest {
  /** 与真正执行使用同一任务 ID，因此落到同一个 MySQL 命名锁。 */
  taskId: string;
  /** 与执行时一致的来源、切片顺序和配置修订，用于重算批次摘要。 */
  input: Omit<KnowledgeIndexInput, 'apiKey'>;
}
export interface IndexRecoveryResult {
  status: IndexRecoveryStatus;
}
/** 探针只读取操作身份、摘要和状态，不读取模型向量结果。 */
interface OperationRow extends RowDataPacket {
  operation_key: string;
  input_hash: string;
  status: string;
}
const MAX_CHUNKS = 10000;

/**
 * 在获取数据库连接前验证恢复请求，避免非法输入参与锁名称或摘要计算。
 * 检查 Java Long 标识、范围、Int32 索引版本、配置修订、HTTPS 基础地址和完整切片列表。
 * 限制最多 10000 段、每段 1000 个 UTF-16 码元且 ID 唯一，批次边界与正常执行保持一致。
 *
 * input 顶层出现已知凭据字段会拒绝；这里不构造客户端、不访问地址，也不做模型域名探测。
 * 域名白名单和实时业务授权属于真正执行入口，合法 URL 不能被理解为模型已获授权。
 *
 * @param value 控制器收到的未知 JSON 值，不能只凭 TypeScript 断言相信其结构。
 * @returns 经校验的原请求对象，不重排切片、不修改文本；非法参数直接抛错。
 */
export function validateIndexRecoveryRequest(value: unknown): IndexRecoveryRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('恢复检查参数无效');
  }
  const request = value as IndexRecoveryRequest;
  knowledgeDecimalId(request.taskId);
  const input = request.input;
  if (
    !input ||
    typeof input !== 'object' ||
    Array.isArray(input) ||
    Object.keys(input).some((key) =>
      ['apikey', 'api_key', 'encryptedkey', 'encrypted_key', 'authorization', 'password'].includes(
        key.toLowerCase(),
      ),
    )
  ) {
    throw new Error('恢复检查参数无效');
  }
  [input.tenantId, input.knowledgeBaseId, input.documentId, input.fileVersionId].forEach(
    knowledgeDecimalId,
  );
  knowledgeScope(input.scope, input.caseId);
  if (
    !Number.isSafeInteger(input.indexVersion) ||
    input.indexVersion < 1 ||
    input.indexVersion > 2147483647 ||
    typeof input.configurationRevision !== 'string' ||
    !input.configurationRevision.trim() ||
    input.configurationRevision.length > 80 ||
    /[\u0000-\u001f\u007f]/.test(input.configurationRevision)
  ) {
    throw new Error('恢复检查参数无效');
  }
  for (const [text, max] of [
    [input.provider, 80],
    [input.baseUrl, 500],
    [input.model, 120],
  ] as const) {
    if (
      typeof text !== 'string' ||
      !text.trim() ||
      text.length > max ||
      /[\u0000-\u001f\u007f]/.test(text)
    ) {
      throw new Error('恢复检查参数无效');
    }
  }
  const url = new URL(input.baseUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
    throw new Error('恢复检查参数无效');
  }
  if (!Array.isArray(input.chunks) || !input.chunks.length || input.chunks.length > MAX_CHUNKS) {
    throw new Error('恢复检查参数无效');
  }
  const ids = new Set<string>();
  for (const chunk of input.chunks) {
    if (!chunk || typeof chunk !== 'object' || Array.isArray(chunk)) {
      throw new Error('恢复检查参数无效');
    }
    knowledgeDecimalId(chunk.id);
    if (
      ids.has(chunk.id) ||
      typeof chunk.text !== 'string' ||
      !chunk.text.trim() ||
      chunk.text.length > 1000
    ) {
      throw new Error('恢复检查参数无效');
    }
    ids.add(chunk.id);
  }
  return request;
}

/**
 * 对已持久化的 Embedding 批次做只读恢复资格检查，绝不在探针中调用模型或写入 Milvus。
 * 先用与执行器相同的线程身份抢占非阻塞命名锁；抢不到返回 BUSY，避免并发执行时读到
 * 尚在变化的日志。取得锁后只读已有操作键、摘要与状态，不初始化任务或修改操作记录。
 *
 * 每个批次键都必须能映射到本次固定输入的合法批次，再以整份输入身份和本批原文重算摘要。
 * 输入不符优先返回 INPUT_CHANGED；其余按 UNCERTAIN 高于 FAILED 高于 SAFE 汇总。
 * SAFE 只描述已检查日志，不保证向量仍存在、Java 仍授权或本次恢复必然成功。
 *
 * @param pool 独立 AI 工作流库连接池，只需借出一个连接，不访问 Java 业务表。
 * @param request Java 重新提供的无 Key 固定输入，内部再次校验以保护非 HTTP 调用方。
 * @returns 单一状态；数据库/锁/连接清理失败保守返回 UNAVAILABLE，输入校验错误直接抛出。
 */
export async function inspectIndexRecovery(
  pool: Pick<Pool, 'getConnection'>,
  request: IndexRecoveryRequest,
): Promise<IndexRecoveryResult> {
  const { taskId, input } = validateIndexRecoveryRequest(request);
  const thread = workflowThreadId(input.tenantId, taskId);
  // 与真正执行共用命名锁，等待时间为 0：忙碌时立即返回 BUSY。
  const lockName =
    'lf_graph_' +
    createHash('sha256').update(`${input.tenantId}:${thread}`).digest('hex').slice(0, 48);
  let connection: Awaited<ReturnType<Pool['getConnection']>> | undefined;
  let locked = false;
  let usable = true;
  let status: IndexRecoveryStatus = 'UNAVAILABLE';
  try {
    connection = await pool.getConnection();
    const [locks] = await connection.execute<RowDataPacket[]>(
      { sql: 'SELECT GET_LOCK(?,0) AS acquired', timeout: 5000 },
      [lockName],
    );
    if (String(locks[0]?.acquired) === '0') {
      status = 'BUSY';
    } else if (String(locks[0]?.acquired) === '1') {
      locked = true;
      // 正常 10000 段最多 200 批，这里有界读取至 201 条，不加载任何向量 blob。
      const [rows] = await connection.execute<OperationRow[]>(
        {
          sql: `SELECT operation_key,input_hash,status
        FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND operation_key LIKE 'index-embedding-v1-%'
        LIMIT 201`,
          timeout: 5000,
        },
        [input.tenantId, thread],
      );
      const identity = knowledgeIndexIdentity(input);
      // 只读取操作日志，不创建线程、不修改状态，也不向模型或 Milvus 发请求。
      status = 'SAFE';
      for (const row of rows) {
        // 批次编号从 0 开始；未知键、超出当前切片范围的批次都不能沿用旧结果。
        const match = /^index-embedding-v1-(0|[1-9][0-9]*)$/.exec(row.operation_key);
        const batch = match ? Number(match[1]) : -1;
        const texts =
          Number.isSafeInteger(batch) &&
          batch >= 0 &&
          batch < Math.ceil(input.chunks.length / KNOWLEDGE_EMBEDDING_BATCH_SIZE)
            ? input.chunks
                .slice(
                  batch * KNOWLEDGE_EMBEDDING_BATCH_SIZE,
                  (batch + 1) * KNOWLEDGE_EMBEDDING_BATCH_SIZE,
                )
                .map((chunk) => chunk.text)
            : null;
        // 算法与 runPersistedKnowledgeIndex 一致；配置修订或任一切片变化也会改变 identity。
        const hash = texts ? knowledgeEmbeddingBatchHash(identity, texts) : null;
        if (hash !== row.input_hash) {
          status = 'INPUT_CHANGED';
          break;
        }
        // 优先级：输入变化 > 不确定 > 明确失败 > 可安全继续。
        if (row.status !== 'SUCCEEDED' && row.status !== 'FAILED') {
          status = 'UNCERTAIN';
        } else if (row.status === 'FAILED' && status !== 'UNCERTAIN') {
          status = 'FAILED';
        }
      }
    }
  } catch {
    status = 'UNAVAILABLE';
    usable = false;
  } finally {
    if (connection) {
      if (locked) {
        try {
          const [released] = await connection.execute<RowDataPacket[]>(
            { sql: 'SELECT RELEASE_LOCK(?) AS released', timeout: 5000 },
            [lockName],
          );
          if (String(released[0]?.released) !== '1') {
            status = 'UNAVAILABLE';
            usable = false;
          }
        } catch {
          status = 'UNAVAILABLE';
          usable = false;
        }
      }
      // 命名锁释放结果不确定时销毁连接，避免带锁连接回到共享池。
      if (!usable) {
        try {
          connection.destroy();
        } catch {
          /* 连接已失效，销毁失败不改变恢复检查的不可用结果。 */
        }
      }
      try {
        connection.release();
      } catch {
        status = 'UNAVAILABLE';
        try {
          connection.destroy();
        } catch {
          /* 连接已失效，销毁失败不改变恢复检查的不可用结果。 */
        }
      }
    }
  }
  return { status };
}

/**
 * NestJS 的恢复检查适配层，延迟创建独立 AI 库连接池，并在模块关闭时释放。
 * 对外只暴露固定状态；配置缺失或探针无法完成时不抛出数据库细节，也不尝试代替用户恢复。
 */
@Injectable()
export class IndexRecoveryEligibilityService implements OnModuleDestroy {
  private pool?: Pool;

  /** 释放已创建的连接池，不因为关闭尚未用过的模块而建立连接。 */
  async onModuleDestroy() {
    await this.pool?.end();
  }

  /**
   * 执行一次只读探针，将连接配置、参数或查询等异常统一转换为 UNAVAILABLE。
   * HTTP 控制器已先单独验证参数，因此从 HTTP 调用时无效输入通常在进入本方法前返回 400。
   */
  async check(input: IndexRecoveryRequest): Promise<IndexRecoveryResult> {
    try {
      this.pool ??= workflowPool(workflowDatabaseUrl());
      return await inspectIndexRecovery(this.pool, input);
    } catch {
      return { status: 'UNAVAILABLE' };
    }
  }
}
