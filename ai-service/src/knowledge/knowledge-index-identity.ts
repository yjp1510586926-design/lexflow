import { createHash } from 'node:crypto';
import type { KnowledgeIndexInput } from '../infrastructure/java/task-callback.service';

/**
 * 为整个索引任务生成固定输入摘要，执行和恢复资格检查都使用同一算法。
 * 摘要按固定字段顺序绑定租户与范围、文件与索引版本、模型配置修订及完整切片列表。
 * 切片内容或顺序发生变化都会改变摘要，避免将旧向量匹配到新材料。
 * API Key 排除在持久化身份之外。工作流运行期间单独核对当前 Key 是否已替换。
 *
 * @param input Java 固定的索引输入或去除 API Key 的恢复检查输入。
 * @returns 供工作流比较的 SHA-256 十六进制摘要。实时授权仍由 Java 核对。
 */
export function knowledgeIndexIdentity(input: Omit<KnowledgeIndexInput, 'apiKey'>): string {
  return createHash('sha256')
    .update(
      JSON.stringify([
        input.tenantId,
        input.scope ?? 'CASE',
        input.caseId,
        input.knowledgeBaseId,
        input.documentId,
        input.fileVersionId,
        input.indexVersion,
        input.provider,
        input.baseUrl,
        input.model,
        input.configurationRevision ?? null,
        input.chunks,
      ]),
    )
    .digest('hex');
}

/**
 * 为固定顺序的一批切片计算操作日志摘要。执行时传入实际送给模型的文本，
 * 恢复检查则按相同批次边界切出文本再重算。复用已有日志依赖序列化顺序一致。
 */
export function knowledgeEmbeddingBatchHash(identity: string, texts: readonly string[]): string {
  return createHash('sha256')
    .update(JSON.stringify([identity, texts]))
    .digest('hex');
}
