/**
 * CASE 表示单案件知识；TENANT 表示同一律所的公共知识。
 * 公共仍然受 tenantId 隔离，不能理解为所有租户都能检索。
 */
export type KnowledgeScopeKind = 'CASE' | 'TENANT';

/** Java 传入的知识范围协议；兼容旧案件调用，但公共范围必须显式声明。 */
export interface KnowledgeScopeInput {
  /** 只有旧 CASE 调用可省略范围；TENANT 公共知识必须显式声明。 */
  scope?: KnowledgeScopeKind;
  /** CASE 必须是正数十进制 ID；TENANT 必须明确为 null，空字符串不能代替。 */
  caseId: string | null;
}

/** 校验后的判别联合，使 CASE 与 TENANT 的 caseId 约束在类型中对应起来。 */
export type KnowledgeScope = { scope: 'CASE'; caseId: string } | { scope: 'TENANT'; caseId: null };

/**
 * 将外部标识限制为 Java 正 Long 能表示的十进制字符串，避免浮点数精度损失。
 * 不接受 0、负数、前导零、数字类型或超过 9223372036854775807 的值。
 * 返回的字符串可用于后续结构化过滤条件；调用方不应先 Number(value) 再传入。
 *
 * @param value Java 内部接口提供的租户、知识库、文档或切片等 ID。
 * @returns 通过验证的原字符串；任何不合法形式都会抛错。
 */
export function knowledgeDecimalId(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value) ||
    BigInt(value) > 9223372036854775807n
  ) {
    throw new Error('知识范围标识无效');
  }
  return value;
}

/**
 * 规范化知识范围，并阻止缺少案件 ID 的请求意外扩大到律所公共资料。
 * scope 缺省只兼容旧 CASE 请求，仍要求合法 caseId；TENANT 则必须明确带 caseId=null。
 * Milvus 用的内部保留值“0”不属于 API 的公共范围声明，不能从外部直接混用。
 *
 * @param scope Java 明确授权的范围类型，允许旧 CASE 调用省略。
 * @param caseId 案件 ID 或公共范围的 null。
 * @returns 规范化的 CASE/TENANT 对象；范围和案件标识矛盾时抛错。
 */
export function knowledgeScope(scope: unknown, caseId: unknown): KnowledgeScope {
  if (scope === 'TENANT') {
    if (caseId !== null) {
      throw new Error('公共知识范围必须明确提供空案件标识');
    }
    return { scope: 'TENANT', caseId: null };
  }
  if (scope !== undefined && scope !== 'CASE') {
    throw new Error('知识范围类型无效');
  }
  return { scope: 'CASE', caseId: knowledgeDecimalId(caseId) };
}

/**
 * 把已校验的 API 范围映射到既有 Milvus v1 字段表示。
 * CASE 保留真实案件 ID；明确的 TENANT 映射为 case_id="0"，同时仍必须过滤 tenant_id。
 * 映射不会扩大权限，也不会更改原输入对象；无效范围会由 knowledgeScope 抛错。
 */
export function milvusKnowledgeCaseId(input: KnowledgeScopeInput): string {
  const value = knowledgeScope(input.scope, input.caseId);
  return value.scope === 'TENANT' ? '0' : value.caseId;
}
