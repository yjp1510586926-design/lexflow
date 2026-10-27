import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Pool } from 'mysql2/promise';
import { MilvusClient } from '@zilliz/milvus2-sdk-node';
import {
  TaskCallbackService,
  TaskContext,
  KnowledgeIndexInput,
} from '../services/task-callback.service';
import {
  TenantMySqlCheckpointer,
  workflowThreadId,
} from '../graphs/persistence/mysql-checkpointer';
import { workflowPool } from '../graphs/persistence/workflow-pool';
import { configuredEmbeddings, EmbeddingModel, indexKnowledgeDocument } from './knowledge-indexer';
import { MilvusKnowledgeStore } from './milvus-knowledge.store';
import { knowledgeScope } from './knowledge-scope';

export class IndexStoreUnavailableError extends Error {
  constructor() {
    super('向量存储暂时不可用，已保存的模型结果可供恢复');
  }
}

/** 固定来源、切片和配置版本组成索引指纹；凭据不进入操作日志。 */
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

/** 向量写入前先持久化各批模型结果，投递重试可复用已成功的付费批次。 */
export async function runPersistedKnowledgeIndex(
  task: TaskContext,
  input: KnowledgeIndexInput,
  saver: TenantMySqlCheckpointer,
  embeddings: EmbeddingModel,
  store: MilvusKnowledgeStore,
  authorized: () => Promise<void>,
  register?: (collection: string, dimension: number, model: string) => Promise<void>,
) {
  if (!input.configurationRevision?.trim() || input.configurationRevision.length > 80)
    throw new Error('索引缺少模型配置版本，请先升级 Java 内部接口');
  const sourceScope = knowledgeScope(input.scope, input.caseId);
  const taskScope = knowledgeScope(task.scope, task.caseId);
  if (
    input.tenantId !== task.tenantId ||
    sourceScope.scope !== taskScope.scope ||
    sourceScope.caseId !== taskScope.caseId ||
    (task.knowledgeBaseId != null && input.knowledgeBaseId !== task.knowledgeBaseId)
  )
    throw new Error('索引执行材料不属于当前任务');
  const identity = knowledgeIndexIdentity(input);
  const config = {
    configurable: {
      tenant_id: task.tenantId,
      thread_id: workflowThreadId(task.tenantId, task.taskId),
    },
  };
  return saver.withThreadLock(config, async () => {
    await authorized();
    let batch = 0;
    const durable: EmbeddingModel = {
      embedDocuments: async (texts) => {
        await authorized();
        // 批次顺序必须固定；相同键匹配不同文本摘要时会拒绝复用，避免错绑向量。
        const key = `index-embedding-v1-${batch++}`;
        const hash = createHash('sha256')
          .update(JSON.stringify([identity, texts]))
          .digest('hex');
        const vectors = await saver.runOnce(config, key, hash, () =>
          embeddings.embedDocuments(texts),
        );
        await authorized();
        return vectors;
      },
    };
    // 仅向量存储故障可按瞬时故障重试，模型结果仍由操作日志控制是否可以复用。
    const guarded = Object.create(store) as MilvusKnowledgeStore;
    guarded.ensureCollection = async (model, dimension) => {
      await authorized();
      try {
        return await store.ensureCollection(model, dimension);
      } catch {
        throw new IndexStoreUnavailableError();
      }
    };
    guarded.upsert = async (collection, records) => {
      await authorized();
      // 先登记精确向量位置，写入中断后也能由 Java 清理遗留数据。
      if (register)
        await register(
          collection,
          records[0].embedding.length,
          JSON.stringify([input.provider.trim(), input.baseUrl.trim(), input.model.trim()]),
        );
      try {
        await store.upsert(collection, records);
      } catch {
        throw new IndexStoreUnavailableError();
      }
    };
    const receipt = await indexKnowledgeDocument(input, input, durable, guarded);
    await authorized();
    return receipt;
  });
}

@Injectable()
export class KnowledgeIndexWorkflowService implements OnModuleDestroy {
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}
  async onModuleDestroy() {
    await this.pool?.end();
  }
  async run(task: TaskContext, input: KnowledgeIndexInput) {
    const embeddings = configuredEmbeddings(input, process.env.LEXFLOW_AI_ALLOWED_HOSTS ?? '');
    this.pool ??= workflowPool(process.env.AI_WORKFLOW_DATABASE_URL);
    const identity = knowledgeIndexIdentity(input);
    const client = new MilvusClient({ address: process.env.MILVUS_ADDRESS ?? '127.0.0.1:19530' });
    try {
      return await runPersistedKnowledgeIndex(
        task,
        input,
        new TenantMySqlCheckpointer(this.pool, task.tenantId),
        embeddings,
        new MilvusKnowledgeStore(client),
        async () => {
          const live = await this.callback.getIndexInput(task);
          if (knowledgeIndexIdentity(live) !== identity || live.apiKey !== input.apiKey)
            throw new Error('索引材料、权限或模型配置已变化');
        },
        (collection, dimension, model) =>
          this.callback.registerIndexVectors(task, collection, dimension, model),
      );
    } finally {
      await client.closeConnection().catch(() => undefined);
    }
  }
}
