export type KnowledgeScopeKind = 'CASE' | 'TENANT';

export interface KnowledgeScopeInput {
  /** 只有旧 CASE 调用可省略范围；TENANT 公共知识必须显式声明。 */
  scope?: KnowledgeScopeKind;
  caseId: string | null;
}

export type KnowledgeScope = { scope: 'CASE'; caseId: string } | { scope: 'TENANT'; caseId: null };

export function knowledgeDecimalId(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  )
    throw new Error('知识范围标识无效');
  return value;
}

/** 空案件 ID 或 Milvus 保留值不能隐式扩大到公共知识范围，必须明确传入 TENANT。 */
export function knowledgeScope(scope: unknown, caseId: unknown): KnowledgeScope {
  if (scope === 'TENANT') {
    if (caseId !== null) throw new Error('公共知识范围必须明确提供空案件标识');
    return { scope: 'TENANT', caseId: null };
  }
  if (scope !== undefined && scope !== 'CASE') throw new Error('知识范围类型无效');
  return { scope: 'CASE', caseId: knowledgeDecimalId(caseId) };
}

/** 既有 v1 向量结构保留 case_id="0"，仅用于明确声明的租户公共知识。 */
export function milvusKnowledgeCaseId(input: KnowledgeScopeInput): string {
  const value = knowledgeScope(input.scope, input.caseId);
  return value.scope === 'TENANT' ? '0' : value.caseId;
}
