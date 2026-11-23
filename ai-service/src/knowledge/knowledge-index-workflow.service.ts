import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'mysql2/promise';
import { MilvusClient } from '@zilliz/milvus2-sdk-node';
import { configuredEmbeddings, EmbeddingModel } from '../ai/models/embedding-model';
import { allowedModelHosts, milvusAddress, workflowDatabaseUrl } from '../config/settings';
import { IndexStoreUnavailableError } from '../infrastructure/errors/index-store-unavailable.error';
import {
  TaskCallbackService,
  TaskContext,
  KnowledgeIndexInput,
} from '../infrastructure/java/task-callback.service';
import {
  TenantMySqlCheckpointer,
  workflowThreadId,
} from '../infrastructure/persistence/mysql-checkpointer';
import { workflowPool } from '../infrastructure/persistence/workflow-pool';
import { knowledgeEmbeddingBatchHash, knowledgeIndexIdentity } from './knowledge-index-identity';
import { indexKnowledgeDocument } from './knowledge-indexer';
import { MilvusKnowledgeStore } from './milvus-knowledge.store';
import { knowledgeScope } from './knowledge-scope';

/**
 * 在租户任务执行锁内给普通索引流程增加授权检查与逐批模型结果持久化。
 * 先核对任务与来源范围，再为每批分配稳定操作键；runOnce 在远程请求前保存 STARTED，
 * 成功结果落库后才继续写向量，覆盖“模型已返回但图/消息流程尚未完成”的中断窗口。
 *
 * 再次执行按固定顺序复用 SUCCEEDED 批次；FAILED 保持失败，遗留 STARTED 视为不确定，
 * 都不会因为消息重投而自动付费重发。向量写入失败可重走存储步骤，但不绕过模型日志。
 *
 * @param task 消息对应的租户、任务和知识范围，线程身份不随投递 attempt 改变。
 * @param input Java 授权的不可变来源，必须含模型配置 revision。
 * @param saver 绑定当前租户的独立 AI 数据库检查点存储器。
 * @param embeddings 真正发起远程调用的模型客户端，由持久化包装器控制何时调用。
 * @param store 底层向量存储，包装后在建集合及 upsert 前重新授权。
 * @param authorized 向 Java 重新读取输入并检查权限/配置是否仍有效，失败立即终止。
 * @param register 在真实 upsert 前登记精确向量位置，便于 Java 回收取消或过期写入。
 * @returns 写入收据；向量存储错误转为固定异常，模型不确定状态继续由 saver 表达。
 */
export async function runPersistedKnowledgeIndex(
  task: TaskContext,
  input: KnowledgeIndexInput,
  saver: TenantMySqlCheckpointer,
  embeddings: EmbeddingModel,
  store: MilvusKnowledgeStore,
  authorized: () => Promise<void>,
  register?: (collection: string, dimension: number, model: string) => Promise<void>,
) {
  if (!input.configurationRevision?.trim() || input.configurationRevision.length > 80) {
    throw new Error('索引缺少模型配置版本，请先升级 Java 内部接口');
  }
  const sourceScope = knowledgeScope(input.scope, input.caseId);
  const taskScope = knowledgeScope(task.scope, task.caseId);
  if (
    input.tenantId !== task.tenantId ||
    sourceScope.scope !== taskScope.scope ||
    sourceScope.caseId !== taskScope.caseId ||
    (task.knowledgeBaseId != null && input.knowledgeBaseId !== task.knowledgeBaseId)
  ) {
    throw new Error('索引执行材料不属于当前任务');
  }
  const identity = knowledgeIndexIdentity(input);
  // config 只携带存储定位；整份 input 中有 apiKey，不能作为检查点状态传入。
  const config = {
    configurable: {
      tenant_id: task.tenantId,
      thread_id: workflowThreadId(task.tenantId, task.taskId),
    },
  };
  return saver.withThreadLock(config, async () => {
    await authorized();
    let batchIndex = 0;
    // 仅替换模型的最小接口，普通索引算法无需知道日志表或锁的实现细节。
    const persistedEmbeddings: EmbeddingModel = {
      embedDocuments: async (texts) => {
        await authorized();
        // 批次键为 index-embedding-v1-0、-1……；摘要同时绑定整份输入和本批文本。
        // 相同键对应不同摘要会拒绝复用，避免修改切片后错绑旧向量。
        const operationKey = `index-embedding-v1-${batchIndex++}`;
        const inputHash = knowledgeEmbeddingBatchHash(identity, texts);
        const vectors = await saver.runOnce(config, operationKey, inputHash, () =>
          embeddings.embedDocuments(texts),
        );
        await authorized();
        return vectors;
      },
    };
    // 用包装对象覆盖存储入口，底层连接仍由 store 持有，避免修改共享实例。
    // 只有底层存储调用异常被包装；授权或位置登记失败保留原有语义，不伪装为可重试故障。
    const authorizedStore = Object.create(store) as MilvusKnowledgeStore;
    authorizedStore.ensureCollection = async (model, dimension) => {
      await authorized();
      try {
        return await store.ensureCollection(model, dimension);
      } catch {
        throw new IndexStoreUnavailableError();
      }
    };
    authorizedStore.upsert = async (collection, records) => {
      await authorized();
      // 必须先登记再写入：若写入成功但回调丢失，Java 仍知道到哪个集合和版本精确清理。
      // 同一来源/索引版本内的所有批次共享此位置，不需要保存每段正文来执行回收。
      if (register) {
        await register(
          collection,
          records[0].embedding.length,
          JSON.stringify([input.provider.trim(), input.baseUrl.trim(), input.model.trim()]),
        );
      }
      try {
        await store.upsert(collection, records);
      } catch {
        throw new IndexStoreUnavailableError();
      }
    };
    const receipt = await indexKnowledgeDocument(
      input,
      input,
      persistedEmbeddings,
      authorizedStore,
    );
    await authorized();
    return receipt;
  });
}

/**
 * NestJS 索引执行适配层：装配模型、独立 AI 数据库和 Milvus，并注入 Java 实时授权能力。
 * MySQL 连接池按需创建并在模块销毁时关闭；Milvus 连接为每次 run 创建，结束后单独释放。
 * 持久化层只保存 AI 执行资料，不直接读取 Java 的业务表或决定知识发布状态。
 */
@Injectable()
export class KnowledgeIndexWorkflowService implements OnModuleDestroy {
  private pool?: Pool;
  constructor(private readonly callback: TaskCallbackService) {}

  /** NestJS 关闭模块时释放已创建的工作流连接池，未执行过任务时无需建连接。 */
  async onModuleDestroy() {
    await this.pool?.end();
  }

  /**
   * 执行消费者已领取的固定输入，并在每个模型批次前后和存储动作前向 Java 复查。
   * Java 返回的来源指纹或 Key 有变化时终止，避免撤权、配置替换后继续发布旧结果。
   * 这里只返回收据，最终成功回调由消费者处理；失败也会进入 finally 尝试关闭 Milvus 连接。
   */
  async run(task: TaskContext, input: KnowledgeIndexInput) {
    const embeddings = configuredEmbeddings(input, allowedModelHosts());
    this.pool ??= workflowPool(workflowDatabaseUrl());
    const identity = knowledgeIndexIdentity(input);
    const client = new MilvusClient({ address: milvusAddress() });
    try {
      return await runPersistedKnowledgeIndex(
        task,
        input,
        new TenantMySqlCheckpointer(this.pool, task.tenantId),
        embeddings,
        new MilvusKnowledgeStore(client),
        async () => {
          // Key 不参与持久化摘要，但执行期间替换 Key 也会使本次授权失效。
          const live = await this.callback.getIndexInput(task);
          if (knowledgeIndexIdentity(live) !== identity || live.apiKey !== input.apiKey) {
            throw new Error('索引材料、权限或模型配置已变化');
          }
        },
        (collection, dimension, model) =>
          this.callback.registerIndexVectors(task, collection, dimension, model),
      );
    } finally {
      await client.closeConnection().catch(() => undefined);
    }
  }
}
