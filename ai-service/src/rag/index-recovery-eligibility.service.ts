import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Pool, RowDataPacket } from 'mysql2/promise';
import { workflowThreadId } from '../graphs/persistence/mysql-checkpointer';
import { workflowPool } from '../graphs/persistence/workflow-pool';
import { KnowledgeIndexInput } from '../services/task-callback.service';
import { knowledgeDecimalId, knowledgeScope } from './knowledge-scope';
import { knowledgeIndexIdentity } from './knowledge-index-workflow.service';

export type IndexRecoveryStatus =
  'SAFE' | 'BUSY' | 'UNCERTAIN' | 'FAILED' | 'INPUT_CHANGED' | 'UNAVAILABLE';
export interface IndexRecoveryRequest {
  taskId: string;
  input: Omit<KnowledgeIndexInput, 'apiKey'>;
}
export interface IndexRecoveryResult {
  status: IndexRecoveryStatus;
}
interface OperationRow extends RowDataPacket {
  operation_key: string;
  input_hash: string;
  status: string;
}
const MAX_CHUNKS = 10000;
const BATCH_SIZE = 50;

/** 检查参数须与索引的固定输入一致，并遵守相同上限；此处不接收运行时密钥。 */
export function validateIndexRecoveryRequest(value: unknown): IndexRecoveryRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('恢复检查参数无效');
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
  )
    throw new Error('恢复检查参数无效');
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
  )
    throw new Error('恢复检查参数无效');
  for (const [text, max] of [
    [input.provider, 80],
    [input.baseUrl, 500],
    [input.model, 120],
  ] as const)
    if (
      typeof text !== 'string' ||
      !text.trim() ||
      text.length > max ||
      /[\u0000-\u001f\u007f]/.test(text)
    )
      throw new Error('恢复检查参数无效');
  const url = new URL(input.baseUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash)
    throw new Error('恢复检查参数无效');
  if (!Array.isArray(input.chunks) || !input.chunks.length || input.chunks.length > MAX_CHUNKS)
    throw new Error('恢复检查参数无效');
  const ids = new Set<string>();
  for (const chunk of input.chunks) {
    if (!chunk || typeof chunk !== 'object' || Array.isArray(chunk))
      throw new Error('恢复检查参数无效');
    knowledgeDecimalId(chunk.id);
    if (
      ids.has(chunk.id) ||
      typeof chunk.text !== 'string' ||
      !chunk.text.trim() ||
      chunk.text.length > 1000
    )
      throw new Error('恢复检查参数无效');
    ids.add(chunk.id);
  }
  return request;
}

/** 只读检查操作日志；SAFE 后调用方仍需复查 Java 实时状态，不在检查中重发模型请求。 */
export async function inspectIndexRecovery(
  pool: Pick<Pool, 'getConnection'>,
  request: IndexRecoveryRequest,
): Promise<IndexRecoveryResult> {
  const { taskId, input } = validateIndexRecoveryRequest(request);
  const thread = workflowThreadId(input.tenantId, taskId);
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
    if (String(locks[0]?.acquired) === '0') status = 'BUSY';
    else if (String(locks[0]?.acquired) === '1') {
      locked = true;
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
      status = 'SAFE';
      for (const row of rows) {
        const match = /^index-embedding-v1-(0|[1-9][0-9]*)$/.exec(row.operation_key);
        const batch = match ? Number(match[1]) : -1;
        const texts =
          Number.isSafeInteger(batch) &&
          batch >= 0 &&
          batch < Math.ceil(input.chunks.length / BATCH_SIZE)
            ? input.chunks
                .slice(batch * BATCH_SIZE, (batch + 1) * BATCH_SIZE)
                .map((chunk) => chunk.text)
            : null;
        const hash = texts
          ? createHash('sha256')
              .update(JSON.stringify([identity, texts]))
              .digest('hex')
          : null;
        if (hash !== row.input_hash) {
          status = 'INPUT_CHANGED';
          break;
        }
        if (row.status !== 'SUCCEEDED' && row.status !== 'FAILED') status = 'UNCERTAIN';
        else if (row.status === 'FAILED' && status !== 'UNCERTAIN') status = 'FAILED';
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
          /* own failed connection */
        }
      }
      try {
        connection.release();
      } catch {
        status = 'UNAVAILABLE';
        try {
          connection.destroy();
        } catch {
          /* own failed connection */
        }
      }
    }
  }
  return { status };
}

@Injectable()
export class IndexRecoveryEligibilityService implements OnModuleDestroy {
  private pool?: Pool;
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async check(input: IndexRecoveryRequest): Promise<IndexRecoveryResult> {
    try {
      this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
      return await inspectIndexRecovery(this.pool, input);
    } catch {
      return { status: 'UNAVAILABLE' };
    }
  }
}
