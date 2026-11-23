/**
 * 工作流恢复前的只读一致性探针，支持合同审查、文书生成和案件分析。
 * 探针使用与执行器相同的非阻塞命名锁，在锁内读取快照、待写入及操作结果，重算摘要并核对依赖链。
 * 这里不创建执行租约、不修补记录、不调用模型、不继续图；探测行为本身不能触发新的付费执行。
 * 返回 SAFE 仅说明已检查的缓存与输入兼容，恢复时仍由 Java 重新检查权限、任务状态及费用确认。
 */
import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { Pool, PoolConnection, RowDataPacket } from 'mysql2/promise';
import { workflowDatabaseUrl } from '../../config/settings';
import { workflowPool } from '../../infrastructure/persistence/workflow-pool';
import { workflowThreadId } from '../../infrastructure/persistence/mysql-checkpointer';
import {
  ContractInput,
  ContractEvidence,
  contractIdentity,
  contractOperationHash,
  validateContractInput,
  validateContractEvidence,
  validateContractExtraction,
  validateContractAnalysis,
} from '../contract-review/contract-review.graph';
import {
  GenerationInput,
  generationIdentity,
  validateGenerationInput,
  validateGeneratedDraft,
} from '../document-generation/document-generation.graph';
import {
  CaseAnalysisInput,
  analysisIdentity,
  analysisOperationHash,
  validateAnalysisInput,
  validateExtracted,
  validateAnalysisAssessment,
} from '../case-analysis/case-analysis.graph';
import { deliveryId } from '../../infrastructure/messaging/workflow-delivery';

/**
 * 只读探针的检查结论，不能直接作为重新付费执行的授权。
 * SAFE：已检查记录与输入兼容，可能尚无记录或仍有待执行节点，不保证后续完全不调用模型。
 * BUSY：执行器正持有同一线程锁，不能读取为稳定恢复状态。
 * UNCERTAIN：存在 STARTED 或其他不可确认完成的操作，禁止自动重发。
 * FAILED：存在明确失败操作；INPUT_CHANGED：身份、摘要、依赖或快照与当前输入不一致。
 * UNAVAILABLE：连接、超时、类型、大小、解码或其他检查条件无法满足，不能当作 SAFE。
 */
export type WorkflowRecoveryStatus =
  'SAFE' | 'BUSY' | 'UNCERTAIN' | 'FAILED' | 'INPUT_CHANGED' | 'UNAVAILABLE';
export interface WorkflowRecoveryResult {
  status: WorkflowRecoveryStatus;
}
/**
 * Java 为一次恢复资格检查提供的固定输入，结构绑定明确任务类型与协议版本。
 * 剔除 apiKey 后仍能重算全部内容指纹；凭据、加载指令和未知字段会被拒绝。
 * 合同工作流若使用知识依据，Java 还须提供当前重新授权的 verifiedEvidence，证明缓存依据仍可使用。
 * 本请求只用于探测兼容性，不是执行指令，也不携带人工重新付费授权。
 */
export interface WorkflowRecoveryRequest {
  /** 明确选择待检查图类型，不根据输入形状猜测。 */
  type: 'CONTRACT_REVIEW' | 'DOCUMENT_GENERATE' | 'CASE_ANALYSIS';
  /** 须与 input.taskId 一致的 Java 业务任务编号。 */
  taskId: string;
  /** 仅接受当前可验证恢复协议 2，拒绝旧协议记录按新规则静默重放。 */
  executionProtocol: 2;
  /** 按任务类型提供固定内容，类型层剔除 apiKey，运行时还会递归拒绝敏感字段。 */
  input:
    | Omit<ContractInput, 'apiKey'>
    | Omit<GenerationInput, 'apiKey'>
    | Omit<CaseAnalysisInput, 'apiKey'>;
  /** 仅合同检查可提供，Java 当前授权的依据原文，用于验证旧检索缓存的活性。 */
  verifiedEvidence?: ContractEvidence[];
}
interface OperationRow extends RowDataPacket {
  operation_key: string;
  input_hash: string;
  status: string;
}
interface ResultRow extends RowDataPacket {
  operation_key: string;
  result_type: string;
  result_blob: Buffer;
  result_bytes: number;
}
// 分别约束请求/快照、单项操作结果和整次探针累计读取量。
const MAX_BODY = 8 * 1024 * 1024;
const MAX_RESULT = 2 * 1024 * 1024;
const MAX_READ = 24 * 1024 * 1024;
const forbiddenFieldNames = new Set([
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
  '__proto__',
  'prototype',
  'constructor',
]);
const isRecord = (value: unknown): value is Record<string, any> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
const isBoundedText = (value: unknown, max: number): value is string =>
  typeof value === 'string' && !!value.trim() && value.length <= max;

/**
 * 在输入和已解码存储结果中递归拒绝敏感/危险字段，同时控制遍历成本。
 * 预算在整棵树共享：最多遍历 50 万节点、深度 80；不是为每个分支重新计数。
 * 除已知凭据字段外，还拒绝原型相关字段及 lc=1 的 LangChain 加载标记，探针只处理普通 JSON 数据。
 * 字段名检查不等于任意秘密识别，调用方不能把 API Key 改名塞进普通字符串来依赖此方法自动发现。
 */
function rejectCredentials(value: unknown, depth = 0, budget = { nodes: 0 }): void {
  if (++budget.nodes > 500000 || depth > 80) {
    throw new Error('恢复输入层级无效');
  }
  if (!value || typeof value !== 'object') {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenFieldNames.has(key.toLowerCase())) {
      throw new Error('恢复输入不得包含凭据');
    }
    // 存储值只能按普通数据解析，不能被当作 LangChain 构造器或加载指令执行。
    if (key === 'lc' && child === 1) {
      throw new Error('恢复存储类型无效');
    }
    rejectCredentials(child, depth + 1, budget);
  }
}

/**
 * 在获取 SQL 连接前完成恢复请求的结构、身份和当前协议边界校验。
 * 先拒绝未知顶层字段及凭据，限制整体 UTF-8 字节，再核验任务 ID、attempt/stateVersion 和 HTTPS 模型身份。
 * 按任务类型使用各自字段白名单及业务输入校验器，防止一种图的输入误用于另一种图；不要求也不接收模型 Key。
 * 这里不检查模型域名部署白名单，因为探针不访问模型；真正恢复时仍由模型工厂与 Java 授权检查完成。
 *
 * @param value 控制器收到的未知 JSON，不应在校验前转成可信业务类型。
 * @returns 与任务类型对应的 WorkflowRecoveryRequest，内容仍只用于只读检查。
 * @throws 非法结构、未知字段、标识越界、超限或不兼容版本时抛错，不进行数据库访问。
 */
export function validateWorkflowRecoveryRequest(value: unknown): WorkflowRecoveryRequest {
  if (
    !isRecord(value) ||
    Object.keys(value).some(
      (k) => !['type', 'taskId', 'executionProtocol', 'input', 'verifiedEvidence'].includes(k),
    ) ||
    !['CONTRACT_REVIEW', 'DOCUMENT_GENERATE', 'CASE_ANALYSIS'].includes(value.type) ||
    !deliveryId(value.taskId) ||
    value.executionProtocol !== 2 ||
    !isRecord(value.input)
  ) {
    throw new Error('恢复检查参数无效');
  }
  rejectCredentials(value);
  if (Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_BODY) {
    throw new Error('恢复输入超过上限');
  }
  const input = value.input;
  if (
    input.taskId !== value.taskId ||
    !deliveryId(input.tenantId) ||
    !Number.isSafeInteger(input.attempt) ||
    input.attempt < 0 ||
    input.attempt > 2147483647 ||
    !Number.isSafeInteger(input.stateVersion) ||
    input.stateVersion < 1 ||
    input.stateVersion > 2147483647
  ) {
    throw new Error('恢复任务身份无效');
  }
  for (const [name, max] of [
    ['provider', 80],
    ['baseUrl', 500],
    ['model', 120],
  ] as const) {
    if (!isBoundedText(input[name], max) || /[\u0000-\u001f\u007f]/.test(input[name])) {
      throw new Error('恢复模型身份无效');
    }
  }
  const url = new URL(input.baseUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
    throw new Error('恢复模型身份无效');
  }
  const allowed =
    value.type === 'CONTRACT_REVIEW'
      ? [
          'tenantId',
          'taskId',
          'fileVersionId',
          'sha256',
          'mimeType',
          'attempt',
          'stateVersion',
          'stance',
          'focus',
          'knowledgeBaseId',
          'decisionId',
          'round',
          'instructions',
          'supplements',
          'previousReport',
          'provider',
          'baseUrl',
          'model',
        ]
      : value.type === 'DOCUMENT_GENERATE'
        ? [
            'tenantId',
            'taskId',
            'caseId',
            'documentId',
            'sourceVersionId',
            'attempt',
            'stateVersion',
            'title',
            'baseContent',
            'instructions',
            'caseContext',
            'modelRevision',
            'provider',
            'baseUrl',
            'model',
          ]
        : [
            'tenantId',
            'taskId',
            'caseId',
            'attempt',
            'stateVersion',
            'background',
            'requirements',
            'sources',
            'configurationRevision',
            'decisionId',
            'provider',
            'baseUrl',
            'model',
          ];
  if (Object.keys(input).some((k) => !allowed.includes(k))) {
    throw new Error('恢复输入字段无效');
  }
  if (value.type === 'CONTRACT_REVIEW') {
    validateContractInput(input as ContractInput);
    if (
      !deliveryId(input.fileVersionId) ||
      (input.knowledgeBaseId !== null && !deliveryId(input.knowledgeBaseId)) ||
      (input.supplements ?? []).some(
        (s: any) => !deliveryId(s.parseTaskId) || !deliveryId(s.fileVersionId),
      )
    ) {
      throw new Error('恢复来源身份无效');
    }
    if (!isBoundedText(input.mimeType, 255)) {
      throw new Error('恢复源文件格式无效');
    }
    if (value.verifiedEvidence !== undefined) {
      validateContractEvidence(value.verifiedEvidence);
      if (value.verifiedEvidence.some((e: ContractEvidence) => !deliveryId(e.id))) {
        throw new Error('恢复依据身份无效');
      }
    }
  } else if (value.type === 'DOCUMENT_GENERATE') {
    if (value.verifiedEvidence !== undefined) {
      throw new Error('恢复输入字段无效');
    }
    validateGenerationInput(input as GenerationInput);
    rejectCredentials(JSON.parse(input.caseContext));
    if ([input.caseId, input.documentId, input.sourceVersionId].some((v) => !deliveryId(v))) {
      throw new Error('恢复来源身份无效');
    }
  } else {
    if (value.verifiedEvidence !== undefined) {
      throw new Error('恢复输入字段无效');
    }
    validateAnalysisInput(input as CaseAnalysisInput);
    if (
      !deliveryId(input.caseId) ||
      input.sources.some((s: any) =>
        [s.parseTaskId, s.fileVersionId, s.fileId].some((v) => !deliveryId(v)),
      )
    ) {
      throw new Error('恢复来源身份无效');
    }
  }
  workflowThreadId(input.tenantId, input.taskId);
  return value as WorkflowRecoveryRequest;
}

class InputChanged extends Error {}
/**
 * 用深度严格相等核对缓存、规范化结果和快照，不允许仅“字段看起来接近”。
 * 例如校验器会重建引用偏移、裁剪空白或筛掉额外字段；若缓存不是当前规范化形式，应按输入/状态不兼容拒绝。
 * 差异统一使用 InputChanged 标记，让上层返回 INPUT_CHANGED，而不向调用者暴露原文或对象差异。
 */
function assertEqual(actual: unknown, expected: unknown): void {
  if (!isDeepStrictEqual(actual, expected)) {
    throw new InputChanged();
  }
}
/**
 * 只解码探针明确支持的 json 类型，并限制单条存储值的实际字节数。
 * 使用 fatal UTF-8 解码拒绝损坏编码，再按普通 JSON.parse 解析并递归检查敏感/危险字段。
 * 不调用框架通用 serde，也不执行 LangChain 对象加载，因此不能通过存储值构造运行时模型或外部能力。
 *
 * @param type 数据库保存的序列化类型标识，当前仅接受 json。
 * @param blob 非空 Uint8Array/Buffer，不能超过该记录类别上限。
 * @param max 单项允许字节数，与整次读取预算分别约束。
 * @returns 待任务专用校验器进一步核对的普通 JSON 值。
 */
function decodeStoredJson(type: unknown, blob: unknown, max: number): any {
  if (
    type !== 'json' ||
    !(blob instanceof Uint8Array) ||
    !blob.byteLength ||
    blob.byteLength > max
  ) {
    throw new Error('存储数据不可验证');
  }
  const value = JSON.parse(new TextDecoder('utf8', { fatal: true }).decode(blob));
  rejectCredentials(value);
  return value;
}
const contractKey =
  /^(extract-clauses-v1|retrieve-knowledge-v1|analyze-risk-v1|deep-review-v1)(?:-round-([1-9]|10))?$/;

/**
 * 在执行器同名锁内只读核对一次工作流能否按当前输入继续。
 * 1. 请求先校验，再在共享总时限内取连接并尝试 GET_LOCK(...,0)，拿不到锁返回 BUSY，不等待执行器。
 * 2. 核对操作键及轮次，先阻止不确定/失败操作；成功日志再按当前输入重算摘要并检查依赖。
 * 3. 读取当前轮次最新 v4 快照，合并允许的 pending writes，再与规范化操作结果及最终报告交叉比较。
 * 4. 释放命名锁和连接；解锁/释放异常也不能返回 SAFE，失效连接尽力销毁，不放回池继续使用。
 * 不写 workflow_thread 租约、不创建 STARTED、不修改快照、不执行图和模型，避免一次探针本身改变恢复资格或产生费用。
 *
 * @param pool 独立 AI 工作流库连接池，仅需要获取连接能力。
 * @param raw Java 当前固定输入，内部会再验证一次；非法请求在进入数据库检查前抛错。
 * @returns 只有检查完整结束且连接正常收尾才可能返回 SAFE；业务授权和费用决定仍由 Java 负责。
 */
export async function inspectWorkflowRecovery(
  pool: Pick<Pool, 'getConnection'>,
  raw: WorkflowRecoveryRequest,
): Promise<WorkflowRecoveryResult> {
  const request = validateWorkflowRecoveryRequest(raw);
  const input = request.input;
  const tenant = input.tenantId;
  const thread = workflowThreadId(tenant, request.taskId);
  const lock =
    'lf_graph_' + createHash('sha256').update(`${tenant}:${thread}`).digest('hex').slice(0, 48);
  let connection: PoolConnection | undefined;
  let locked = false;
  let usable = true;
  let status: WorkflowRecoveryStatus = 'UNAVAILABLE';
  // 连接获取和每次查询共享总时限；超时取得的连接也必须释放。
  const deadline = Date.now() + 4500;
  const remaining = () => {
    const n = deadline - Date.now();
    if (n <= 0) {
      throw new Error('恢复检查超时');
    }
    return n;
  };
  try {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let expired = false;
    try {
      connection = await Promise.race([
        pool.getConnection().then((c) => {
          if (expired) {
            c.release();
            throw new Error('恢复检查超时');
          }
          return c;
        }),
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(() => {
            expired = true;
            reject(new Error('恢复检查超时'));
          }, remaining());
        }),
      ]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
    const query = async <T extends RowDataPacket>(sql: string, values: string[]) => {
      const [rows] = await connection!.execute<T[]>({ sql, timeout: remaining() }, values);
      return rows;
    };
    const acquired = await query<RowDataPacket>('SELECT GET_LOCK(?,0) AS acquired', [lock]);
    if (String(acquired[0]?.acquired) === '0') {
      status = 'BUSY';
    } else if (String(acquired[0]?.acquired) === '1') {
      locked = true;
      const bound =
        request.type === 'CONTRACT_REVIEW' ? 34 : request.type === 'DOCUMENT_GENERATE' ? 1 : 3;
      const rows = await query<OperationRow>(
        `SELECT operation_key,input_hash,status FROM workflow_operation
        WHERE tenant_id=? AND thread_id=? ORDER BY operation_key LIMIT ${bound + 1}`,
        [tenant, thread],
      );
      if (rows.length > bound) {
        throw new Error('日志超过上限');
      }
      const round = request.type === 'CONTRACT_REVIEW' ? ((input as ContractInput).round ?? 0) : 0;
      const keys = new Map<string, OperationRow>();
      const current: OperationRow[] = [];
      for (const row of rows) {
        let rowRound = 0;
        if (request.type === 'CONTRACT_REVIEW') {
          const m = contractKey.exec(row.operation_key);
          if (!m) {
            throw new InputChanged();
          }
          rowRound = Number(m[2] ?? 0);
          if (rowRound > round || (rowRound > 0 && m[1] === 'extract-clauses-v1')) {
            throw new InputChanged();
          }
        } else if (
          !(request.type === 'DOCUMENT_GENERATE'
            ? row.operation_key === 'document-draft-v1'
            : ['case-facts-v1', 'case-disputes-v1', 'case-high-risk-v1'].includes(
                row.operation_key,
              ))
        ) {
          throw new InputChanged();
        }
        if (keys.has(row.operation_key) || !/^[a-f0-9]{64}$/.test(row.input_hash)) {
          throw new InputChanged();
        }
        keys.set(row.operation_key, row);
        if (rowRound === round) {
          current.push(row);
        }
      }
      // 不确定或失败日志先阻止恢复，再逐步核对输入摘要、依赖结果和报告一致性。
      if (rows.some((row) => row.status !== 'SUCCEEDED' && row.status !== 'FAILED')) {
        status = 'UNCERTAIN';
      } else if (rows.some((row) => row.status === 'FAILED')) {
        status = 'FAILED';
      } else {
        const namespace = round ? `contract-round-${round}|` : '';
        const namespaces = await query<RowDataPacket>(
          `SELECT DISTINCT checkpoint_ns FROM workflow_checkpoint
          WHERE tenant_id=? AND thread_id=? LIMIT 12`,
          [tenant, thread],
        );
        for (const row of namespaces) {
          if (request.type === 'CONTRACT_REVIEW') {
            if (
              row.checkpoint_ns !== '' &&
              !/^contract-round-([1-9]|10)\|$/.test(row.checkpoint_ns)
            ) {
              throw new InputChanged();
            }
            if (
              row.checkpoint_ns &&
              Number(/^contract-round-([1-9]|10)\|$/.exec(row.checkpoint_ns)![1]) > round
            ) {
              throw new InputChanged();
            }
          } else if (row.checkpoint_ns !== '') {
            throw new InputChanged();
          }
        }
        let bytes = 0;
        const snapshots = await query<RowDataPacket>(
          `SELECT checkpoint_id,checkpoint_type,metadata_type,
          OCTET_LENGTH(checkpoint_blob) AS checkpoint_bytes,OCTET_LENGTH(metadata_blob) AS metadata_bytes,
          CASE WHEN OCTET_LENGTH(checkpoint_blob)<=8388608 THEN checkpoint_blob END AS checkpoint_blob,
          CASE WHEN OCTET_LENGTH(metadata_blob)<=60000 THEN metadata_blob END AS metadata_blob
          FROM workflow_checkpoint WHERE tenant_id=? AND thread_id=? AND checkpoint_ns=? ORDER BY checkpoint_id DESC LIMIT 1`,
          [tenant, thread, namespace],
        );
        const identity =
          request.type === 'CONTRACT_REVIEW'
            ? contractIdentity(input as ContractInput)
            : request.type === 'DOCUMENT_GENERATE'
              ? generationIdentity(input as GenerationInput)
              : analysisIdentity(input as CaseAnalysisInput);
        // 先读取最新主快照，再合并 pending writes，最后与操作日志逐项交叉核对。
        const state: Record<string, any> = {};
        if (snapshots.length) {
          const row = snapshots[0];
          bytes += Number(row.checkpoint_bytes) + Number(row.metadata_bytes);
          const snapshot = decodeStoredJson(row.checkpoint_type, row.checkpoint_blob, MAX_BODY);
          const metadata = decodeStoredJson(row.metadata_type, row.metadata_blob, 60000);
          if (
            !isRecord(snapshot) ||
            snapshot.v !== 4 ||
            !isRecord(snapshot.channel_values) ||
            !isRecord(metadata)
          ) {
            throw new Error('快照不可验证');
          }
          Object.assign(state, snapshot.channel_values);
          const storedHash = state.inputHash ?? metadata.lexflowInputHash;
          if (typeof storedHash !== 'string') {
            throw new Error('快照摘要缺失');
          }
          if (
            storedHash !== identity ||
            (metadata.lexflowInputHash !== undefined && metadata.lexflowInputHash !== identity)
          ) {
            throw new InputChanged();
          }
          const writes = await query<RowDataPacket>(
            `SELECT channel_name,value_type,OCTET_LENGTH(value_blob) AS value_bytes,
            CASE WHEN OCTET_LENGTH(value_blob)<=8388608 THEN value_blob END AS value_blob
            FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=? AND checkpoint_ns=? AND checkpoint_id=?
            ORDER BY task_id,write_idx LIMIT 201`,
            [tenant, thread, namespace, row.checkpoint_id],
          );
          if (writes.length > 200) {
            throw new Error('待写入快照超过上限');
          }
          const channels = new Set([
            'inputHash',
            'text',
            'contractType',
            'clauses',
            'evidence',
            'analysis',
            'report',
            'decisionId',
            'draft',
            'extracted',
            'assessment',
          ]);
          // 合并已保存但尚未进入主快照的节点写入，检查恢复实际会使用的完整状态。
          const pending = new Map<string, unknown>();
          for (const write of writes) {
            bytes += Number(write.value_bytes);
            if (bytes > MAX_READ) {
              throw new Error('快照总字节超过上限');
            }
            if (!channels.has(write.channel_name)) {
              continue;
            }
            const decoded = decodeStoredJson(write.value_type, write.value_blob, MAX_BODY);
            if (pending.has(write.channel_name)) {
              assertEqual(pending.get(write.channel_name), decoded);
            }
            pending.set(write.channel_name, decoded);
          }
          for (const [channel, value] of pending) {
            state[channel] = value;
          }
          if (state.inputHash !== undefined && state.inputHash !== identity) {
            throw new InputChanged();
          }
        } else if ('decisionId' in input && input.decisionId) {
          throw new Error('已决定的快照缺失');
        }
        if (
          state.decisionId !== undefined &&
          state.decisionId !== input['decisionId' as keyof typeof input]
        ) {
          throw new InputChanged();
        }
        if ('decisionId' in input && input.decisionId && !state.report) {
          throw new Error('已决定的报告快照缺失');
        }
        const results = new Map<string, any>();
        for (const operation of current) {
          const result = await query<ResultRow>(
            `SELECT operation_key,result_type,OCTET_LENGTH(result_blob) AS result_bytes,
            CASE WHEN OCTET_LENGTH(result_blob)<=2097152 THEN result_blob END AS result_blob
            FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND operation_key=?`,
            [tenant, thread, operation.operation_key],
          );
          if (result.length !== 1) {
            throw new Error('操作结果缺失');
          }
          bytes += Number(result[0].result_bytes);
          if (bytes > MAX_READ) {
            throw new Error('结果总字节超过上限');
          }
          results.set(
            operation.operation_key,
            decodeStoredJson(result[0].result_type, result[0].result_blob, MAX_RESULT),
          );
        }
        const verify = (key: string, digest: string) => {
          const op = keys.get(key);
          if (op && op.input_hash !== digest) {
            throw new InputChanged();
          }
        };
        if (request.type === 'DOCUMENT_GENERATE') {
          verify('document-draft-v1', identity);
          if (results.has('document-draft-v1')) {
            const draft = results.get('document-draft-v1');
            assertEqual(draft, validateGeneratedDraft(draft));
            if (state.draft !== undefined) {
              assertEqual(state.draft, draft);
            }
          } else if (state.draft !== undefined) {
            throw new InputChanged();
          }
        } else if (request.type === 'CONTRACT_REVIEW') {
          inspectContract(
            input as ContractInput,
            request.verifiedEvidence,
            state,
            keys,
            results,
            verify,
            identity,
            round,
          );
        } else {
          inspectAnalysis(input as CaseAnalysisInput, state, keys, results, verify, identity);
        }
        // SAFE 只表示当前缓存与输入可复用，调用方还须重新检查 Java 的实时任务状态。
        status = 'SAFE';
      }
    }
  } catch (error) {
    status = error instanceof InputChanged ? 'INPUT_CHANGED' : 'UNAVAILABLE';
    usable = false;
  } finally {
    if (connection) {
      if (locked) {
        try {
          const [rows] = await connection.execute<RowDataPacket[]>(
            { sql: 'SELECT RELEASE_LOCK(?) AS released', timeout: 500 },
            [lock],
          );
          if (String(rows[0]?.released) !== '1') {
            status = 'UNAVAILABLE';
            usable = false;
          }
        } catch {
          status = 'UNAVAILABLE';
          usable = false;
        }
      }
      if (!usable) {
        try {
          connection.destroy();
        } catch {
          /* 当前连接已不可复用，销毁失败也不能返回 SAFE。 */
        }
      }
      try {
        connection.release();
      } catch {
        status = 'UNAVAILABLE';
        try {
          connection.destroy();
        } catch {
          /* 释放失败后尽力销毁，防止失效连接回到池中。 */
        }
      }
    }
  }
  return { status };
}

/**
 * 按合同图相同的依赖顺序重建可恢复状态，禁止无依据的跨步骤或跨轮次缓存复用。
 * 首轮从操作结果恢复条款，补材轮次用上一轮原文重验条款；检索问题也按相同规则重算后核对摘要。
 * 缓存知识依据必须出现在 Java 重新授权的 verified 列表中，编号和原文都要匹配。
 * 风险分析依赖条款与依据；深度复核只允许由 HIGH 风险/冲突触发，并按图中的规则保留初始高风险和合并缺口。
 * 最后检查状态里的 analysis/report 是否与这些已验证依赖完全一致，不修补缺失或冲突的数据。
 *
 * @param input 当前固定合同轮次输入。
 * @param verified Java 本次已重新授权的知识原文，不能只凭旧快照认定仍有权限。
 * @param state 最新快照合并待写入后的有效通道值。
 * @param results 当前轮次已完成操作的普通 JSON 结果。
 * @param verify 核对操作键与重算 inputHash 的闭包；不执行任何操作。
 * @throws 不兼容差异以 InputChanged 标记，缺失依赖/未证明依据活性等则使探针不可用。
 */
function inspectContract(
  input: ContractInput,
  verified: ContractEvidence[] | undefined,
  state: Record<string, any>,
  keys: Map<string, OperationRow>,
  results: Map<string, any>,
  verify: (key: string, hash: string) => void,
  identity: string,
  round: number,
): void {
  const key = (step: string) => (round ? `${step}-round-${round}` : step);
  const original = round ? input.previousReport!.sourceText : state.text;
  if (
    state.text !== undefined &&
    (!isBoundedText(state.text, 40000) || (round && state.text !== original))
  ) {
    throw new InputChanged();
  }
  let extracted: ReturnType<typeof validateContractExtraction> | undefined;
  if (round) {
    extracted = validateContractExtraction(input.previousReport!, original);
  } else if (results.has(key('extract-clauses-v1'))) {
    if (!isBoundedText(original, 40000)) {
      throw new Error('原文快照缺失');
    }
    verify(key('extract-clauses-v1'), contractOperationHash(identity, original));
    extracted = results.get(key('extract-clauses-v1'));
    assertEqual(extracted, validateContractExtraction(extracted, original));
  } else if (state.clauses !== undefined || state.contractType !== undefined) {
    throw new InputChanged();
  }
  if (extracted) {
    if (state.clauses !== undefined) {
      assertEqual(state.clauses, extracted.clauses);
    }
    if (state.contractType !== undefined) {
      assertEqual(state.contractType, extracted.contractType);
    }
  }
  let evidence: ContractEvidence[] | undefined = input.knowledgeBaseId === null ? [] : undefined;
  if (results.has(key('retrieve-knowledge-v1'))) {
    if (input.knowledgeBaseId === null) {
      throw new InputChanged();
    }
    if (!extracted) {
      throw new Error('检索依赖快照缺失');
    }
    const query = (
      input.focus.trim() ||
      `${extracted.contractType} ${extracted.clauses.map((c) => c.title).join(' ')}`
    ).slice(0, 500);
    verify(key('retrieve-knowledge-v1'), contractOperationHash(identity, query));
    evidence = results.get(key('retrieve-knowledge-v1'));
    validateContractEvidence(evidence!);
    if (
      evidence!.length &&
      (!verified ||
        evidence!.some((e) => !verified.some((v) => v.id === e.id && v.text === e.text)))
    ) {
      throw new Error('缓存依据活性未证明');
    }
  }
  if (state.evidence !== undefined) {
    if (!evidence) {
      throw new InputChanged();
    }
    assertEqual(state.evidence, evidence);
  }
  let initial: any;
  let final: any;
  if (results.has(key('analyze-risk-v1'))) {
    if (!extracted || !evidence) {
      throw new Error('风险分析依赖缺失');
    }
    verify(key('analyze-risk-v1'), contractOperationHash(identity, [extracted.clauses, evidence]));
    initial = results.get(key('analyze-risk-v1'));
    assertEqual(initial, validateContractAnalysis(initial, extracted.clauses, evidence));
    final = initial;
  }
  if (results.has(key('deep-review-v1'))) {
    if (!initial || !extracted || !evidence) {
      throw new Error('二次审查依赖缺失');
    }
    if (!initial.risks.some((r: any) => r.level === 'HIGH') && !initial.basisConflicts?.length) {
      throw new InputChanged();
    }
    verify(key('deep-review-v1'), contractOperationHash(identity, initial));
    const revised = results.get(key('deep-review-v1'));
    assertEqual(revised, validateContractAnalysis(revised, extracted.clauses, evidence));
    final = structuredClone(revised);
    for (const risk of initial.risks.filter((r: any) => r.level === 'HIGH')) {
      const next = final.risks.find((r: any) => r.clauseId === risk.clauseId);
      if (!next) {
        throw new InputChanged();
      }
      next.level = 'HIGH';
    }
    const conflicts = [...initial.basisConflicts];
    for (const c of final.basisConflicts) {
      if (!conflicts.some((old) => JSON.stringify(old) === JSON.stringify(c))) {
        conflicts.push(c);
      }
    }
    if (conflicts.length > 10) {
      throw new InputChanged();
    }
    final = {
      ...final,
      basisConflicts: conflicts,
      insufficientEvidence: final.insufficientEvidence || initial.insufficientEvidence,
      missingMaterials: [
        ...new Set([...initial.missingMaterials, ...final.missingMaterials]),
      ].slice(0, 10),
    };
  }
  if (state.analysis !== undefined) {
    if (!final) {
      throw new InputChanged();
    }
    assertEqual(state.analysis, final);
  }
  if (state.report !== undefined) {
    if (!final || !extracted || !evidence) {
      throw new Error('报告依赖缺失');
    }
    assertEqual(state.report, {
      sourceSha256: input.sha256,
      sourceText: original,
      contractType: extracted.contractType,
      clauses: extracted.clauses,
      risks: final.risks,
      missingMaterials: final.missingMaterials,
      evidence,
      basisConflicts: final.basisConflicts ?? [],
      needsHuman:
        final.insufficientEvidence ||
        !evidence.length ||
        !!final.missingMaterials.length ||
        !!final.basisConflicts?.length ||
        final.risks.some((r: any) => r.level === 'HIGH'),
    });
  }
}

/**
 * 沿案件图依赖链检查事实提取、争议分析、高风险复核和最终报告的缓存一致性。
 * 事实引用重新绑定当前固定材料，争议只能引用这些有效编号；每个步骤摘要使用与执行器相同的依赖值。
 * 高风险复核须确有初始 HIGH 争议，并保留原高风险、合并缺材料和依据不足，不能只验证最后一份模型输出。
 * state 中出现某项结果时，其依赖操作结果必须存在且严格相等，探针不会重新请求模型补齐依赖。
 *
 * @param input 当前案件材料、背景、要求和配置修订，不含模型 Key。
 * @param state 最新快照与待写入合并后的通道状态。
 * @param results 已完成步骤的解码结果，校验器会再次核验引用与字段。
 * @param identity 当前固定内容身份，用于逐步重算操作摘要。
 * @throws 状态不匹配或依赖缺失时终止，外层转换为 INPUT_CHANGED 或 UNAVAILABLE。
 */
function inspectAnalysis(
  input: CaseAnalysisInput,
  state: Record<string, any>,
  keys: Map<string, OperationRow>,
  results: Map<string, any>,
  verify: (key: string, hash: string) => void,
  identity: string,
): void {
  let extracted: any;
  let initial: any;
  let final: any;
  if (results.has('case-facts-v1')) {
    verify('case-facts-v1', analysisOperationHash(identity, input.sources));
    extracted = results.get('case-facts-v1');
    assertEqual(extracted, validateExtracted(extracted, input.sources));
  }
  if (state.extracted !== undefined) {
    if (!extracted) {
      throw new InputChanged();
    }
    assertEqual(state.extracted, extracted);
  }
  if (results.has('case-disputes-v1')) {
    if (!extracted) {
      throw new Error('争议分析依赖缺失');
    }
    verify('case-disputes-v1', analysisOperationHash(identity, extracted));
    initial = results.get('case-disputes-v1');
    assertEqual(initial, validateAnalysisAssessment(initial, extracted.citations));
    final = initial;
  }
  if (results.has('case-high-risk-v1')) {
    if (!initial || !extracted) {
      throw new Error('高风险分析依赖缺失');
    }
    if (!initial.issues.some((i: any) => i.level === 'HIGH')) {
      throw new InputChanged();
    }
    verify('case-high-risk-v1', analysisOperationHash(identity, initial));
    const revised = results.get('case-high-risk-v1');
    assertEqual(revised, validateAnalysisAssessment(revised, extracted.citations));
    final = structuredClone(revised);
    for (const high of initial.issues.filter((i: any) => i.level === 'HIGH')) {
      const matching = final.issues.find((i: any) => i.title === high.title);
      if (!matching) {
        throw new InputChanged();
      }
      matching.level = 'HIGH';
    }
    final = {
      ...final,
      missingMaterials: [
        ...new Set([...initial.missingMaterials, ...final.missingMaterials]),
      ].slice(0, 20),
      insufficientEvidence: final.insufficientEvidence || initial.insufficientEvidence,
    };
  }
  if (state.assessment !== undefined) {
    if (!final) {
      throw new InputChanged();
    }
    assertEqual(state.assessment, final);
  }
  if (state.report !== undefined) {
    if (!extracted || !final) {
      throw new Error('报告依赖缺失');
    }
    assertEqual(state.report, {
      ...extracted,
      issues: final.issues,
      missingMaterials: final.missingMaterials,
      recommendationBoundary: final.recommendationBoundary,
      insufficientEvidence: final.insufficientEvidence,
      needsHuman:
        !extracted.facts.length ||
        !!extracted.pendingVerification.length ||
        !!final.missingMaterials.length ||
        final.insufficientEvidence ||
        final.issues.some((i: any) => i.level === 'HIGH'),
    });
  }
}

/** 把连接、超时或损坏数据统一映射为 UNAVAILABLE，调用方不能据此重放付费操作。 */
@Injectable()
/**
 * 将只读恢复探针封装为可注入的应用服务，按需创建独立 AI 数据库连接池。
 * 连接配置、数据库访问或输入检查出现异常时返回 UNAVAILABLE，调用方不能据此忽略检查直接恢复。
 * 服务不保存探针结果、不调模型，也不以“数据库没有记录”自动决定发起新任务；业务决定留在 Java。
 */
export class WorkflowRecoveryEligibilityService implements OnModuleDestroy {
  private pool?: Pool;
  async onModuleDestroy(): Promise<void> {
    await this.pool?.end();
  }
  /**
   * 调用只读探针并把无法完成检查的异常收敛为 UNAVAILABLE。
   * 控制器通常已验证请求，探针仍会重复校验以便安全接受其他内部调用者。
   *
   * @param input 与当前 Java 任务版本绑定的恢复检查输入。
   * @returns 固定状态对象，不返回材料、向量、模型正文或数据库内部异常。
   */
  async check(input: WorkflowRecoveryRequest): Promise<WorkflowRecoveryResult> {
    try {
      this.pool ??= workflowPool(workflowDatabaseUrl());
      return await inspectWorkflowRecovery(this.pool, input);
    } catch {
      return { status: 'UNAVAILABLE' };
    }
  }
}
