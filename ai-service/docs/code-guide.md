# AI 服务代码学习导读

这个目录虽然位于 Java 项目中，实际运行的是独立的 NestJS + TypeScript 服务。Java Core 拥有业务数据和授权决定，AI 服务负责文件解析、模型推理、工作流执行和向量存储访问。阅读代码时，可以始终围绕一个问题：**这一步拿到什么输入，做完什么检查，才允许进入下一步？**

## 建议阅读顺序

| 顺序 | 文件                                                                                                                                                                                     | 阅读重点                                                             |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| 1    | [main.ts](../src/main.ts)、[app.module.ts](../src/app.module.ts)                                                                                                                         | HTTP 与 RabbitMQ 如何在同一进程启动，controller 与 provider 如何装配 |
| 2    | [document-parse.consumer.ts](../src/documents/document-parse.consumer.ts)                                                                                                                | 消息校验、获取任务、下载文件、解析、回调和 ACK 的先后顺序            |
| 3    | [document.parser.ts](../src/documents/parsers/document.parser.ts)、[source-segments.ts](../src/documents/parsers/source-segments.ts)                                                     | 不同文件格式如何归一成原文，以及页码、段号和字符偏移如何关联         |
| 4    | [knowledge-indexer.ts](../src/knowledge/knowledge-indexer.ts)、[milvus-knowledge.store.ts](../src/knowledge/milvus-knowledge.store.ts)                                                   | 原文切片如何变成向量，模型身份和授权范围如何限制检索                 |
| 5    | [legal-question.controller.ts](../src/ai/legal-question.controller.ts)、[legal-question.ts](../src/ai/legal-question.ts)                                                                 | 内部鉴权、模型构造、提示词、JSON 输出和引用校验                      |
| 6    | [case-analysis-workflow.service.ts](../src/workflows/case-analysis/case-analysis-workflow.service.ts)、[case-analysis.graph.ts](../src/workflows/case-analysis/case-analysis.graph.ts)   | 任务认领、状态通道、图节点、条件分支及人工复核                       |
| 7    | [contract-review.graph.ts](../src/workflows/contract-review/contract-review.graph.ts)、[document-generation.graph.ts](../src/workflows/document-generation/document-generation.graph.ts) | 比较不同工作流如何复用相同的执行与持久化机制                         |
| 8    | [mysql-checkpointer.ts](../src/infrastructure/persistence/mysql-checkpointer.ts)、[task-delivery.service.ts](../src/infrastructure/messaging/task-delivery.service.ts)                   | 检查点、执行锁、外部操作日志与消息重试各自解决什么问题               |

## 项目结构

根模块按功能导入 NestJS 子模块。每个子模块登记自己的 HTTP 控制器、队列消费者和执行服务。共用的 Java 客户端和消息恢复服务由 `TaskMessagingModule` 导出。这样的装配方式与 [NestJS 功能模块](https://docs.nestjs.com/modules#feature-modules) 一致。

```text
ai-service/
├── src/
│   ├── main.ts                    # 启动 HTTP 和 RabbitMQ 消费者
│   ├── app.module.ts              # 装配四个业务模块与健康接口
│   ├── config/                    # 部署参数和 HTTP 请求体上限
│   ├── ai/                        # 资料问答、连接测试
│   │   └── models/                # LLM、Embedding、Rerank 供应商适配器
│   ├── documents/                 # 文件解析消费者与 TXT、DOCX、PDF 解析器
│   ├── knowledge/                 # 知识索引、检索、重排、向量回收
│   ├── workflows/
│   │   ├── case-analysis/         # 案件分析
│   │   ├── contract-review/       # 合同审查
│   │   ├── document-generation/   # 文书生成
│   │   └── recovery/              # 只读恢复资格检查
│   ├── infrastructure/
│   │   ├── java/                  # 调用 Java 内部接口
│   │   ├── messaging/             # 队列、消息确认、延时重试和死信
│   │   ├── persistence/           # MySQL 连接、检查点、执行锁和操作日志
│   │   └── errors/                # 执行层和投递层共用的错误类型
│   └── health/                    # 进程健康接口
└── sql/                           # 独立 AI 数据库的初始化 SQL
```

工作流目录内的三个文件对应 [LangGraph 的状态、节点和边](https://docs.langchain.com/oss/javascript/langgraph/thinking-in-langgraph)。以案件分析为例：

| 文件                                                                                                  | 职责                                                                                           |
| ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| [case-analysis.state.ts](../src/workflows/case-analysis/case-analysis.state.ts)                       | 定义输入、报告和需要跨节点保存的状态通道。                                                     |
| [case-analysis.nodes.ts](../src/workflows/case-analysis/case-analysis.nodes.ts)                       | 提取事实、校验原文引用、分析争议、高风险复核和生成报告。节点从当前状态读取数据，返回局部更新。 |
| [case-analysis.graph.ts](../src/workflows/case-analysis/case-analysis.graph.ts)                       | 用边和条件分支连接节点，并处理编译、线程锁、检查点和恢复。                                     |
| [case-analysis-workflow.service.ts](../src/workflows/case-analysis/case-analysis-workflow.service.ts) | 向 Java 认领任务，构造模型、持久化和授权检查所需的运行时依赖。                                 |

[settings.ts](../src/config/settings.ts) 集中定义环境变量与开发默认值。本机通过 `.env.ai.local` 启动，部署由容器注入配置。租户的模型凭据由 Java 在执行时提供。

NestJS 用 `@Controller` 声明 HTTP 或消息入口，用 `@Injectable` 声明可注入的服务。TypeScript 的 `interface` 描述编译期数据结构，运行时仍需校验 HTTP、消息和模型响应。

## 沿着三条实际链路阅读

### 文件解析：先理解最短的异步任务链路

`DocumentParseConsumer.handle` 接收消息后，先向 Java 获取当前任务上下文。它比较消息与上下文中的租户、轮次，并检查上下文中的任务类型及状态；不匹配时直接确认旧投递，避免覆盖新结果。匹配后下载已授权文件，调用 `parseDocument`，再由 `sourceSegments` 计算来源区间。Java 接受结果后才 ACK；回调或基础设施故障交给投递恢复层处理。

解析器不调用模型。`sourceSha256` 绑定文件字节；`text` 保留提取原文；`segments` 使用 UTF-16 的左闭右开区间 `[startOffset, endOffset)`。例如 emoji 在 JavaScript 字符串和 Java String 中都可能占两个 UTF-16 单元，因此这里不能随意换成按 Unicode 字符数量计算的偏移。

### 知识索引与问答：理解“检索到”与“允许引用”的区别

索引从 `KnowledgeIndexConsumer` 进入 `KnowledgeIndexWorkflowService`。Java 分配固定索引版本和切片编号；服务每批处理 50 段，先把 Embedding 结果写入操作日志，再登记精确向量位置并写入 Milvus。Java 接受成功回调后才发布新索引，向量写入成功本身不等于业务发布成功。

检索由 `KnowledgeSearchController` 接收 Java 提供的授权范围，`MilvusKnowledgeStore.search` 按租户、案件或律所范围、知识库、授权文档列表及索引版本召回切片标识，再由 Java 核对文件版本和切片权限。`KnowledgeRerankController` 只对 Java 再次授权的候选原文排序。Java 负责串联检索、可选排序和问答，并在返回结果前复核来源。

`answerQuestion` 只用本次 `evidence` 作为事实依据。没有原文时直接拒答。有原文时，先挑选问题、历史问题和原文构造提示词，调用模型，最后由 `parseQuestionAnswer` 检查回答结构，以及正文 `[编号]`、`citationIds` 和授权来源之间的一致性。JSON 语法正确不能证明引用正确；这里核对的是来源编号，案件分析和合同图还会进一步核对引用原文。

### LangGraph：从案件分析理解有状态执行

`CaseAnalysisWorkflowService.run` 向 Java 认领固定输入并通知消费者更新回调版本，然后创建模型与租户检查点存储器。`case-analysis.state.ts` 用 `Annotation` 定义状态通道。`case-analysis.nodes.ts` 实现节点。`runCaseAnalysis` 用 `addNode` 注册步骤，再用 `addEdge` 和 `addConditionalEdges` 决定下一步。

先读 `extractMaterialFacts → analyzeDisputes`，随后按风险分支：普通路径直接进入 `buildAnalysisReport`，高风险路径先进入 `reviewHighRisk`，再生成报告。报告需要人工复核时进入 `humanReview`。人工节点通过 `interrupt` 暂停，Java 保存的复核决定在恢复时通过 `Command` 传回。模型 API Key 留在执行闭包中，图状态保存可恢复的数据。`assertAuthorized` 在执行期间重新向 Java 检查权限和固定输入，不能把最初一次授权当成整个长任务的永久授权。

## 四组容易混淆的概念

| 概念                        | 在本项目中的作用                                                                                 |
| --------------------------- | ------------------------------------------------------------------------------------------------ |
| `attempt` 与 `stateVersion` | 前者识别业务处理轮次，后者用于回调时核对当前状态版本；认领任务后版本可能变化                     |
| 检查点与操作日志            | 检查点记录图执行进度；`runOnce` 操作日志单独记录模型等外部动作，成功结果可在检查点尚未写入时复用 |
| 执行锁与租约 token          | 数据库锁约束同一线程的并发执行；token 帮助拒绝失去锁的旧执行者继续写入                           |
| 消息重试与模型重发          | 消息重新投递可以复用已保存结果；遗留 `STARTED` 只能说明结果不确定，不能证明供应商没有执行        |

`runOnce` 先保存 `STARTED`，然后发起外部动作；成功后保存 `SUCCEEDED`。已成功结果可以复用，明确失败不自动重试；若进程在请求发出后崩溃，遗留 `STARTED` 会被视为不确定。它不能保证远程模型恰好执行一次。

恢复资格检查位于 [workflow-recovery-eligibility.service.ts](../src/workflows/recovery/workflow-recovery-eligibility.service.ts) 和 [index-recovery-eligibility.service.ts](../src/knowledge/index-recovery-eligibility.service.ts)。这些探针只读取已有操作状态并判断是否适合恢复，不重新发起模型请求，也不代替 Java 的业务授权。

## 构建与格式检查

在 `ai-service` 目录执行：

```sh
npm run build
```

格式使用仓库根目录已有的 Prettier 规则。安装根目录开发依赖后，可以在仓库根目录只检查本服务：

```sh
./node_modules/.bin/prettier --check "ai-service/**/*.{ts,js,cjs,json,md}"
```

完整服务运行需要 Node.js 24 及对应基础设施配置。`setup:workflow` 用于在配置的独立 AI 数据库中创建工作流表。详见 [README](../README.md)。
