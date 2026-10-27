import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { Pool, PoolConnection, RowDataPacket } from 'mysql2/promise';
import { workflowPool } from './persistence/workflow-pool';
import { workflowThreadId } from './persistence/mysql-checkpointer';
import {
  ContractInput,
  ContractEvidence,
  contractIdentity,
  contractOperationHash,
  validateContractInput,
  validateContractEvidence,
  validateContractExtraction,
  validateContractAnalysis,
} from './contract-review.graph';
import {
  GenerationInput,
  generationIdentity,
  validateGenerationInput,
  validateGeneratedDraft,
} from './document-generation.graph';
import {
  CaseAnalysisInput,
  analysisIdentity,
  analysisOperationHash,
  validateAnalysisInput,
  validateExtracted,
  validateAnalysisAssessment,
} from './case-analysis.graph';
import { deliveryId } from '../transport/workflow-delivery';

export type WorkflowRecoveryStatus =
  'SAFE' | 'BUSY' | 'UNCERTAIN' | 'FAILED' | 'INPUT_CHANGED' | 'UNAVAILABLE';
export interface WorkflowRecoveryResult {
  status: WorkflowRecoveryStatus;
}
export interface WorkflowRecoveryRequest {
  type: 'CONTRACT_REVIEW' | 'DOCUMENT_GENERATE' | 'CASE_ANALYSIS';
  taskId: string;
  executionProtocol: 2;
  input:
    | Omit<ContractInput, 'apiKey'>
    | Omit<GenerationInput, 'apiKey'>
    | Omit<CaseAnalysisInput, 'apiKey'>;
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
const MAX_BODY = 8 * 1024 * 1024,
  MAX_RESULT = 2 * 1024 * 1024,
  MAX_READ = 24 * 1024 * 1024;
const forbidden = new Set([
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
const object = (v: unknown): v is Record<string, any> =>
  !!v && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown, max: number): v is string =>
  typeof v === 'string' && !!v.trim() && v.length <= max;

function rejectCredentials(value: unknown, depth = 0, budget = { nodes: 0 }): void {
  if (++budget.nodes > 500000 || depth > 80) throw new Error('恢复输入层级无效');
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (forbidden.has(key.toLowerCase())) throw new Error('恢复输入不得包含凭据');
    // Stored values are data, never LangChain constructor/load instructions.
    if (key === 'lc' && child === 1) throw new Error('恢复存储类型无效');
    rejectCredentials(child, depth + 1, budget);
  }
}

/** Validate before obtaining any SQL connection. No credentials are needed to recompute identities. */
export function validateWorkflowRecoveryRequest(value: unknown): WorkflowRecoveryRequest {
  if (
    !object(value) ||
    Object.keys(value).some(
      (k) => !['type', 'taskId', 'executionProtocol', 'input', 'verifiedEvidence'].includes(k),
    ) ||
    !['CONTRACT_REVIEW', 'DOCUMENT_GENERATE', 'CASE_ANALYSIS'].includes(value.type) ||
    !deliveryId(value.taskId) ||
    value.executionProtocol !== 2 ||
    !object(value.input)
  )
    throw new Error('恢复检查参数无效');
  rejectCredentials(value);
  if (Buffer.byteLength(JSON.stringify(value), 'utf8') > MAX_BODY)
    throw new Error('恢复输入超过上限');
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
  )
    throw new Error('恢复任务身份无效');
  for (const [name, max] of [
    ['provider', 80],
    ['baseUrl', 500],
    ['model', 120],
  ] as const)
    if (!text(input[name], max) || /[\u0000-\u001f\u007f]/.test(input[name]))
      throw new Error('恢复模型身份无效');
  const url = new URL(input.baseUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash)
    throw new Error('恢复模型身份无效');
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
  if (Object.keys(input).some((k) => !allowed.includes(k))) throw new Error('恢复输入字段无效');
  if (value.type === 'CONTRACT_REVIEW') {
    validateContractInput(input as ContractInput);
    if (
      !deliveryId(input.fileVersionId) ||
      (input.knowledgeBaseId !== null && !deliveryId(input.knowledgeBaseId)) ||
      (input.supplements ?? []).some(
        (s: any) => !deliveryId(s.parseTaskId) || !deliveryId(s.fileVersionId),
      )
    )
      throw new Error('恢复来源身份无效');
    if (!text(input.mimeType, 255)) throw new Error('恢复源文件格式无效');
    if (value.verifiedEvidence !== undefined) {
      validateContractEvidence(value.verifiedEvidence);
      if (value.verifiedEvidence.some((e: ContractEvidence) => !deliveryId(e.id)))
        throw new Error('恢复依据身份无效');
    }
  } else if (value.type === 'DOCUMENT_GENERATE') {
    if (value.verifiedEvidence !== undefined) throw new Error('恢复输入字段无效');
    validateGenerationInput(input as GenerationInput);
    rejectCredentials(JSON.parse(input.caseContext));
    if ([input.caseId, input.documentId, input.sourceVersionId].some((v) => !deliveryId(v)))
      throw new Error('恢复来源身份无效');
  } else {
    if (value.verifiedEvidence !== undefined) throw new Error('恢复输入字段无效');
    validateAnalysisInput(input as CaseAnalysisInput);
    if (
      !deliveryId(input.caseId) ||
      input.sources.some((s: any) =>
        [s.parseTaskId, s.fileVersionId, s.fileId].some((v) => !deliveryId(v)),
      )
    )
      throw new Error('恢复来源身份无效');
  }
  workflowThreadId(input.tenantId, input.taskId);
  return value as WorkflowRecoveryRequest;
}

class InputChanged extends Error {}
function same(a: unknown, b: unknown): void {
  if (!isDeepStrictEqual(a, b)) throw new InputChanged();
}
function decode(type: unknown, blob: unknown, max: number): any {
  if (type !== 'json' || !(blob instanceof Uint8Array) || !blob.byteLength || blob.byteLength > max)
    throw new Error('存储数据不可验证');
  const value = JSON.parse(new TextDecoder('utf8', { fatal: true }).decode(blob));
  rejectCredentials(value);
  return value;
}
const contractKey =
  /^(extract-clauses-v1|retrieve-knowledge-v1|analyze-risk-v1|deep-review-v1)(?:-round-([1-9]|10))?$/;

/** 使用执行器同名非阻塞锁只读检查，避免并发快照变化；不更新租约、日志或快照。 */
export async function inspectWorkflowRecovery(
  pool: Pick<Pool, 'getConnection'>,
  raw: WorkflowRecoveryRequest,
): Promise<WorkflowRecoveryResult> {
  const request = validateWorkflowRecoveryRequest(raw),
    input = request.input;
  const tenant = input.tenantId,
    thread = workflowThreadId(tenant, request.taskId);
  const lock =
    'lf_graph_' + createHash('sha256').update(`${tenant}:${thread}`).digest('hex').slice(0, 48);
  let connection: PoolConnection | undefined,
    locked = false,
    usable = true,
    status: WorkflowRecoveryStatus = 'UNAVAILABLE';
  const deadline = Date.now() + 4500;
  const remaining = () => {
    const n = deadline - Date.now();
    if (n <= 0) throw new Error('恢复检查超时');
    return n;
  };
  try {
    let timer: ReturnType<typeof setTimeout> | undefined,
      expired = false;
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
      if (timer) clearTimeout(timer);
    }
    const query = async <T extends RowDataPacket>(sql: string, values: string[]) => {
      const [rows] = await connection!.execute<T[]>({ sql, timeout: remaining() }, values);
      return rows;
    };
    const acquired = await query<RowDataPacket>('SELECT GET_LOCK(?,0) AS acquired', [lock]);
    if (String(acquired[0]?.acquired) === '0') status = 'BUSY';
    else if (String(acquired[0]?.acquired) === '1') {
      locked = true;
      const bound =
        request.type === 'CONTRACT_REVIEW' ? 34 : request.type === 'DOCUMENT_GENERATE' ? 1 : 3;
      const rows = await query<OperationRow>(
        `SELECT operation_key,input_hash,status FROM workflow_operation
        WHERE tenant_id=? AND thread_id=? ORDER BY operation_key LIMIT ${bound + 1}`,
        [tenant, thread],
      );
      if (rows.length > bound) throw new Error('日志超过上限');
      const round = request.type === 'CONTRACT_REVIEW' ? ((input as ContractInput).round ?? 0) : 0;
      const keys = new Map<string, OperationRow>(),
        current: OperationRow[] = [];
      for (const row of rows) {
        let rowRound = 0;
        if (request.type === 'CONTRACT_REVIEW') {
          const m = contractKey.exec(row.operation_key);
          if (!m) throw new InputChanged();
          rowRound = Number(m[2] ?? 0);
          if (rowRound > round || (rowRound > 0 && m[1] === 'extract-clauses-v1'))
            throw new InputChanged();
        } else if (
          !(request.type === 'DOCUMENT_GENERATE'
            ? row.operation_key === 'document-draft-v1'
            : ['case-facts-v1', 'case-disputes-v1', 'case-high-risk-v1'].includes(
                row.operation_key,
              ))
        )
          throw new InputChanged();
        if (keys.has(row.operation_key) || !/^[a-f0-9]{64}$/.test(row.input_hash))
          throw new InputChanged();
        keys.set(row.operation_key, row);
        if (rowRound === round) current.push(row);
      }
      // 不确定或失败日志先阻止恢复，再逐步核对输入摘要、依赖结果和报告一致性。
      if (rows.some((row) => row.status !== 'SUCCEEDED' && row.status !== 'FAILED'))
        status = 'UNCERTAIN';
      else if (rows.some((row) => row.status === 'FAILED')) status = 'FAILED';
      else {
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
            )
              throw new InputChanged();
            if (
              row.checkpoint_ns &&
              Number(/^contract-round-([1-9]|10)\|$/.exec(row.checkpoint_ns)![1]) > round
            )
              throw new InputChanged();
          } else if (row.checkpoint_ns !== '') throw new InputChanged();
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
        const state: Record<string, any> = {};
        if (snapshots.length) {
          const row = snapshots[0];
          bytes += Number(row.checkpoint_bytes) + Number(row.metadata_bytes);
          const snapshot = decode(row.checkpoint_type, row.checkpoint_blob, MAX_BODY),
            metadata = decode(row.metadata_type, row.metadata_blob, 60000);
          if (
            !object(snapshot) ||
            snapshot.v !== 4 ||
            !object(snapshot.channel_values) ||
            !object(metadata)
          )
            throw new Error('快照不可验证');
          Object.assign(state, snapshot.channel_values);
          const storedHash = state.inputHash ?? metadata.lexflowInputHash;
          if (typeof storedHash !== 'string') throw new Error('快照摘要缺失');
          if (
            storedHash !== identity ||
            (metadata.lexflowInputHash !== undefined && metadata.lexflowInputHash !== identity)
          )
            throw new InputChanged();
          const writes = await query<RowDataPacket>(
            `SELECT channel_name,value_type,OCTET_LENGTH(value_blob) AS value_bytes,
            CASE WHEN OCTET_LENGTH(value_blob)<=8388608 THEN value_blob END AS value_blob
            FROM workflow_checkpoint_write WHERE tenant_id=? AND thread_id=? AND checkpoint_ns=? AND checkpoint_id=?
            ORDER BY task_id,write_idx LIMIT 201`,
            [tenant, thread, namespace, row.checkpoint_id],
          );
          if (writes.length > 200) throw new Error('待写入快照超过上限');
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
            if (bytes > MAX_READ) throw new Error('快照总字节超过上限');
            if (!channels.has(write.channel_name)) continue;
            const decoded = decode(write.value_type, write.value_blob, MAX_BODY);
            if (pending.has(write.channel_name)) same(pending.get(write.channel_name), decoded);
            pending.set(write.channel_name, decoded);
          }
          for (const [channel, value] of pending) state[channel] = value;
          if (state.inputHash !== undefined && state.inputHash !== identity)
            throw new InputChanged();
        } else if ('decisionId' in input && input.decisionId) throw new Error('已决定的快照缺失');
        if (
          state.decisionId !== undefined &&
          state.decisionId !== input['decisionId' as keyof typeof input]
        )
          throw new InputChanged();
        if ('decisionId' in input && input.decisionId && !state.report)
          throw new Error('已决定的报告快照缺失');
        const results = new Map<string, any>();
        for (const operation of current) {
          const result = await query<ResultRow>(
            `SELECT operation_key,result_type,OCTET_LENGTH(result_blob) AS result_bytes,
            CASE WHEN OCTET_LENGTH(result_blob)<=2097152 THEN result_blob END AS result_blob
            FROM workflow_operation WHERE tenant_id=? AND thread_id=? AND operation_key=?`,
            [tenant, thread, operation.operation_key],
          );
          if (result.length !== 1) throw new Error('操作结果缺失');
          bytes += Number(result[0].result_bytes);
          if (bytes > MAX_READ) throw new Error('结果总字节超过上限');
          results.set(
            operation.operation_key,
            decode(result[0].result_type, result[0].result_blob, MAX_RESULT),
          );
        }
        const verify = (key: string, digest: string) => {
          const op = keys.get(key);
          if (op && op.input_hash !== digest) throw new InputChanged();
        };
        if (request.type === 'DOCUMENT_GENERATE') {
          verify('document-draft-v1', identity);
          if (results.has('document-draft-v1')) {
            const draft = results.get('document-draft-v1');
            same(draft, validateGeneratedDraft(draft));
            if (state.draft !== undefined) same(state.draft, draft);
          } else if (state.draft !== undefined) throw new InputChanged();
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
        } else inspectAnalysis(input as CaseAnalysisInput, state, keys, results, verify, identity);
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
          /* failed own connection */
        }
      }
      try {
        connection.release();
      } catch {
        status = 'UNAVAILABLE';
        try {
          connection.destroy();
        } catch {
          /* own connection */
        }
      }
    }
  }
  return { status };
}

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
  if (state.text !== undefined && (!text(state.text, 40000) || (round && state.text !== original)))
    throw new InputChanged();
  let extracted: ReturnType<typeof validateContractExtraction> | undefined;
  if (round) extracted = validateContractExtraction(input.previousReport!, original);
  else if (results.has(key('extract-clauses-v1'))) {
    if (!text(original, 40000)) throw new Error('原文快照缺失');
    verify(key('extract-clauses-v1'), contractOperationHash(identity, original));
    extracted = results.get(key('extract-clauses-v1'));
    same(extracted, validateContractExtraction(extracted, original));
  } else if (state.clauses !== undefined || state.contractType !== undefined)
    throw new InputChanged();
  if (extracted) {
    if (state.clauses !== undefined) same(state.clauses, extracted.clauses);
    if (state.contractType !== undefined) same(state.contractType, extracted.contractType);
  }
  let evidence: ContractEvidence[] | undefined = input.knowledgeBaseId === null ? [] : undefined;
  if (results.has(key('retrieve-knowledge-v1'))) {
    if (input.knowledgeBaseId === null) throw new InputChanged();
    if (!extracted) throw new Error('检索依赖快照缺失');
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
    )
      throw new Error('缓存依据活性未证明');
  }
  if (state.evidence !== undefined) {
    if (!evidence) throw new InputChanged();
    same(state.evidence, evidence);
  }
  let initial: any, final: any;
  if (results.has(key('analyze-risk-v1'))) {
    if (!extracted || !evidence) throw new Error('风险分析依赖缺失');
    verify(key('analyze-risk-v1'), contractOperationHash(identity, [extracted.clauses, evidence]));
    initial = results.get(key('analyze-risk-v1'));
    same(initial, validateContractAnalysis(initial, extracted.clauses, evidence));
    final = initial;
  }
  if (results.has(key('deep-review-v1'))) {
    if (!initial || !extracted || !evidence) throw new Error('二次审查依赖缺失');
    if (!initial.risks.some((r: any) => r.level === 'HIGH') && !initial.basisConflicts?.length)
      throw new InputChanged();
    verify(key('deep-review-v1'), contractOperationHash(identity, initial));
    const revised = results.get(key('deep-review-v1'));
    same(revised, validateContractAnalysis(revised, extracted.clauses, evidence));
    final = structuredClone(revised);
    for (const risk of initial.risks.filter((r: any) => r.level === 'HIGH')) {
      const next = final.risks.find((r: any) => r.clauseId === risk.clauseId);
      if (!next) throw new InputChanged();
      next.level = 'HIGH';
    }
    const conflicts = [...initial.basisConflicts];
    for (const c of final.basisConflicts)
      if (!conflicts.some((old) => JSON.stringify(old) === JSON.stringify(c))) conflicts.push(c);
    if (conflicts.length > 10) throw new InputChanged();
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
    if (!final) throw new InputChanged();
    same(state.analysis, final);
  }
  if (state.report !== undefined) {
    if (!final || !extracted || !evidence) throw new Error('报告依赖缺失');
    same(state.report, {
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

function inspectAnalysis(
  input: CaseAnalysisInput,
  state: Record<string, any>,
  keys: Map<string, OperationRow>,
  results: Map<string, any>,
  verify: (key: string, hash: string) => void,
  identity: string,
): void {
  let extracted: any, initial: any, final: any;
  if (results.has('case-facts-v1')) {
    verify('case-facts-v1', analysisOperationHash(identity, input.sources));
    extracted = results.get('case-facts-v1');
    same(extracted, validateExtracted(extracted, input.sources));
  }
  if (state.extracted !== undefined) {
    if (!extracted) throw new InputChanged();
    same(state.extracted, extracted);
  }
  if (results.has('case-disputes-v1')) {
    if (!extracted) throw new Error('争议分析依赖缺失');
    verify('case-disputes-v1', analysisOperationHash(identity, extracted));
    initial = results.get('case-disputes-v1');
    same(initial, validateAnalysisAssessment(initial, extracted.citations));
    final = initial;
  }
  if (results.has('case-high-risk-v1')) {
    if (!initial || !extracted) throw new Error('高风险分析依赖缺失');
    if (!initial.issues.some((i: any) => i.level === 'HIGH')) throw new InputChanged();
    verify('case-high-risk-v1', analysisOperationHash(identity, initial));
    const revised = results.get('case-high-risk-v1');
    same(revised, validateAnalysisAssessment(revised, extracted.citations));
    final = structuredClone(revised);
    for (const high of initial.issues.filter((i: any) => i.level === 'HIGH')) {
      const matching = final.issues.find((i: any) => i.title === high.title);
      if (!matching) throw new InputChanged();
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
    if (!final) throw new InputChanged();
    same(state.assessment, final);
  }
  if (state.report !== undefined) {
    if (!extracted || !final) throw new Error('报告依赖缺失');
    same(state.report, {
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

@Injectable()
export class WorkflowRecoveryEligibilityService implements OnModuleDestroy {
  private pool?: Pool;
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async check(input: WorkflowRecoveryRequest): Promise<WorkflowRecoveryResult> {
    try {
      this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
      return await inspectWorkflowRecovery(this.pool, input);
    } catch {
      return { status: 'UNAVAILABLE' };
    }
  }
}
