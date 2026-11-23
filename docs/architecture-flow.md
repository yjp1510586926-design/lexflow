# LexFlow 项目架构与核心链路图

核对日期：2026-10-29。依据当前工作区源码和部署配置整理；描述代码中已经接入的链路，不代表当前机器已运行这些服务或外部模型已通过质量验证。

阅读顺序：先看运行总图和代码分层，再按业务查看异步任务、文件与索引、资料问答、文书导出，最后看异常恢复。

可直接查看或放大：[总览 PNG](../output/architecture-2026-10-29/01-overview.png)、[总览 SVG](../output/architecture-2026-10-29/01-overview.svg)、[代码分层](../output/architecture-2026-10-29/02-code-layers.svg)、[异步任务](../output/architecture-2026-10-29/03-async-tasks.svg)、[文件与索引](../output/architecture-2026-10-29/04-files-and-index.svg)、[RAG 问答](../output/architecture-2026-10-29/05-rag-question.svg)、[文书导出](../output/architecture-2026-10-29/06-document-export.svg)、[恢复与审计](../output/architecture-2026-10-29/07-recovery-and-audit.svg)。

## 1. 整体运行架构

```mermaid
flowchart TB
    USER["用户 / 浏览器"] --> WEB["React + TypeScript<br/>案件、文件、文书、知识库、AI、任务"]
    WEB -->|"同源 /api 请求"| ENTRY["访问入口<br/>开发：Vite :5173<br/>容器：Nginx 默认 :8088"]
    ENTRY -->|"转发 /api"| JAVA["Java 主应用 :8080<br/>api-core + service + framework<br/>鉴权、业务、调度、导出消费者"]

    JAVA -->|"业务读写"| BIZDB[("MySQL 业务库<br/>权限、案件、文书正文与版本<br/>切片、任务、Outbox、审计")]
    JAVA -->|"会话 / 登录限流"| REDIS[("Redis")]
    JAVA -->|"私有文件读写"| MINIO[("MinIO<br/>上传原件、导出文件")]
    JAVA -->|"Outbox 发布"| MQ["RabbitMQ<br/>lexflow.tasks + 工作队列"]
    MQ -->|"document.export"| JAVA
    MQ -->|"五类 AI 任务"| AI["NestJS AI 服务 :3001<br/>内部 HTTP + MQ 消费者<br/>解析、索引、LangGraph"]
    JAVA -->|"内部 HTTP：检索、问答等"| AI
    AI -->|"内部 HTTP：授权取材、认领、结果回调"| JAVA

    AI -->|"检查点 / 执行锁 / 外部调用记录"| AIDB[("MySQL 工作流库 lexflow_ai")]
    AI -->|"向量写入 / 检索 / 回收"| VECTOR[("Milvus · 按需启用")]
    AI -->|"HTTPS"| MODEL["外部模型供应商<br/>LLM / Embedding / 可选 Rerank"]
    VECTOR -.->|"基础设施依赖"| ETCD["etcd"]
    VECTOR -.->|"向量对象存储"| MINIO
```

- `api-core`、`service`、`framework` 打包在同一个 Java 应用中。`ai-service` 是另一个独立进程。
- MVP Compose 的两个 MySQL 逻辑库部署在同一个 MySQL 实例，使用不同账号。Java 持有业务数据；AI 通过 Java 内部接口访问授权材料，仅直连自己的工作流库、Milvus 和模型服务。
- 浏览器统一通过 Java API；Nginx 拦截 `/internal/`。开发入口使用 Vite 代理，容器入口使用 Nginx。
- 实线表示应用调用或数据访问；虚线表示 Milvus 的基础设施依赖。Milvus 和 etcd 属于可选 `vector` profile。

证据：[根 Maven 配置](../pom.xml)、[前端代理](../web/vite.config.ts)、[Nginx 配置](../deploy/nginx.conf)、[MVP Compose](../docker-compose.mvp.yml)、[AI 启动入口](../ai-service/src/main.ts)。

## 2. 代码分层与业务职责

```mermaid
flowchart TB
    subgraph FRONT["web · 浏览器"]
        PAGE["页面 / 组件<br/>工作台、案件、成员权限、文书、任务、知识库、AI、设置"]
        CONTEXT["AuthProvider / ToastProvider<br/>React 本地状态切页"]
        CLIENT["api/*.ts + client.ts<br/>Cookie 会话 / 写请求 CSRF"]
        PAGE --> CLIENT
        CONTEXT --> CLIENT
    end

    subgraph JVM["同一个 Java 进程"]
        subgraph API["api-core"]
            FILTER["core/filter + SecurityConfig<br/>请求追踪、会话有效性、CSRF、请求审计"]
            CTRL["api / api/internal<br/>HTTP 参数与响应"]
            BIZ["biz<br/>复杂用例编排"]
            CORE["core/client、mq、storage、audit<br/>AI HTTP、Outbox、消费者、后台维护"]
            FILTER --> CTRL
            CTRL --> BIZ
        end
        subgraph SERVICE["service"]
            SVC["业务 Service<br/>身份组织、案件证据、文件、文书版本<br/>知识库、AI 应用、任务、审计"]
            DAO["dao + dao/model<br/>MyBatis Mapper / JDBC Repository / 实体"]
            ADAPTER["file / ai / permission / audit<br/>私有存储、凭据加密、授权策略、审计辅助"]
            SVC --> DAO
            SVC --> ADAPTER
        end
        COMMON["framework<br/>统一响应、分页、异常、TraceContext、工具"]
        CTRL -->|"简单用例"| SVC
        BIZ --> SVC
        BIZ -->|"部分检索编排"| DAO
        CORE --> SVC
        API -.->|"复用通用类型"| COMMON
        SERVICE -.->|"编译依赖"| COMMON
    end
    CLIENT --> FILTER
    DAO --> DB[("MySQL 业务库")]
```

编译依赖为 `api-core → service → framework`。Biz 用于复杂接口编排，普通 CRUD 可以直接由 Controller 调用 Service。服务层落实租户隔离、案件权限、事务和版本校验；前端控件显隐不代替后端授权。

前端当前通过 React 状态切换页面。登录身份保存在服务端 Redis Session；启动读取 `/api/v1/auth/me`，写请求附带 CSRF token。请求追踪与审计贯穿接口路径。

证据：[Java 分层约定](java-code-structure.md)、[前端入口](../web/src/App.tsx)、[API 客户端](../web/src/api/client.ts)、[安全配置](../api-core/src/main/java/com/lexflow/website/core/config/SecurityConfig.java)、[案件接口](../api-core/src/main/java/com/lexflow/website/api/CaseController.java)。

## 3. 异步任务通用链路

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant W as React 页面
    participant J as Java API / Service
    participant D as MySQL 业务库
    participant O as Java OutboxPublisher
    participant Q as RabbitMQ
    participant A as NestJS 消费者
    participant C as MySQL 工作流库

    U->>W: 提交解析 / 索引 / 审查 / 分析 / 起草
    W->>J: POST 业务接口，携会话与 CSRF
    J->>J: 授权、版本校验、固定输入
    J->>D: 同事务保存任务、业务快照、Outbox、业务审计
    J-->>W: 返回 taskId
    O->>D: 读取 PENDING Outbox
    O->>Q: 按 routing key 发布
    Q-->>O: Publisher confirm 且未退回
    O->>D: 标记 Outbox SENT
    Q->>A: 投递任务
    A->>J: 内部 HTTP 获取上下文、认领和固定输入
    J->>D: 核验任务轮次、状态版本、当前授权
    J-->>A: 当前有效输入 / 授权文件或原文
    opt 索引或持久化工作流
        A->>C: 获取执行锁，读取进度和已完成操作
    end
    A->>A: 解析 / 索引 / 模型工作流
    opt 索引或持久化工作流
        A->>C: 保存操作结果与工作流进度
    end
    A->>J: 内部 HTTP 提交结果
    J->>J: 再授权、来源校验、attempt / stateVersion 幂等校验
    J->>D: 保存业务结果与任务状态
    J-->>A: 结果已接收
    A->>Q: ACK 消息
    W->>J: 查询任务或业务结果
    J-->>W: 进行中 / 成功 / 失败 / 待人工处理
```

当前实际消费分工：

| 队列 | 消费方 | 结果落点 |
|---|---|---|
| `lexflow.q.document.parse` | NestJS | Java 保存解析正文和页/段信息 |
| `lexflow.q.knowledge.ingest` | NestJS | Milvus 向量；Java 接收索引收据并发布有效索引 |
| `lexflow.q.contract.review` | NestJS | Java 保存合同报告或待人工状态 |
| `lexflow.q.case.analysis` | NestJS | Java 保存案件分析或待人工状态 |
| `lexflow.q.document.generate` | NestJS | Java 保存独立待复核文书版本 |
| `lexflow.q.document.export` | Java | 私有 MinIO TXT 文件和导出任务结果 |

AI 结果当前通过内部 HTTP 回调 Java。配置中存在的 `lexflow.results` 结果拓扑没有承担当前主要回传链路。

Outbox 保证至少一次投递，消费者必须处理重复消息。合同审查、文书生成和导出等结果面板按需轮询；任务列表通过加载、手动刷新和操作后查询更新。问答等待完整 HTTP 响应，当前没有 SSE / WebSocket 推送。

证据：[任务服务](../service/src/main/java/com/lexflow/service/TaskService.java)、[Outbox 发布器](../api-core/src/main/java/com/lexflow/website/core/mq/OutboxPublisher.java)、[RabbitMQ 配置](../api-core/src/main/java/com/lexflow/website/core/config/RabbitMqConfig.java)、[AI 回调服务](../ai-service/src/infrastructure/java/task-callback.service.ts)、[AI 消费启动](../ai-service/src/main.ts)。

### AI 工作流内部步骤与人工环节

| 用例 | 当前执行步骤 | 人工处理 |
|---|---|---|
| 合同审查 | `parseDocument → extractClauses → retrieveKnowledge → analyzeRisk → [deepReview] → generateReport` | 高风险、依据冲突、缺资料或依据不足等触发 `humanReview` 中断；Java 保存决定后重新投递恢复 |
| 案件分析 | `extractMaterialFacts → analyzeDisputes → [reviewHighRisk] → buildAnalysisReport` | 存在高风险、待核实事实或依据不足等触发 `humanReview` 中断 |
| 文书生成 | `generateDraft → validateDraft` | Java 收到结果后保存 `AI_DRAFT`；用户另行复核，通过后才切换当前版本 |
| 知识索引 | 固定切片 → 分批 Embedding → 持久化结果 → 登记向量位置 → Milvus upsert → 索引收据 | 使用工作流存储的锁和操作日志，本身没有构建 LangGraph StateGraph |

合同的 `deepReview` 由高风险或依据冲突触发；案件的 `reviewHighRisk` 由高风险触发。方括号表示条件分支。合同与案件工作流可在无需人工时直接结束。文书生成图本身没有人工中断节点，生成任务成功也不等于文书已复核或已发布。

证据：[合同图](../ai-service/src/workflows/contract-review/contract-review.graph.ts)、[案件分析图](../ai-service/src/workflows/case-analysis/case-analysis.graph.ts)、[文书生成图](../ai-service/src/workflows/document-generation/document-generation.graph.ts)、[文书版本复核](../service/src/main/java/com/lexflow/service/DocumentService.java)。

## 4. 文件上传、解析与知识入库

```mermaid
flowchart LR
    subgraph FILESTAGE["文件上传与解析"]
        direction TB
        UP["用户上传案件文件 / 律所知识文件"] --> J["Java：授权、文件检查、摘要与版本登记"]
        J --> OBJ[("MinIO 私有原件")]
        J --> META[("MySQL 文件元数据 / 固定版本")]
        META -->|"用户提交解析"| PT["Java：解析任务 + Outbox"]
        PT --> PQ["RabbitMQ document.parse"]
        PQ --> PARSE["NestJS 解析消费者"]
        PARSE --> FETCH["经 Java 内部接口<br/>授权下载固定版本并验证摘要"]
        OBJ --> FETCH
        FETCH --> TEXT["TXT / DOCX / 文本层 PDF<br/>提取正文与页 / 段定位"]
        TEXT -->|"HTTP 回调 Java"| PARSED[("MySQL 解析正文 / 页段 / 来源版本")]
    end
    subgraph INDEXSTAGE["知识切片准备与索引发布"]
        direction TB
        PREPARE["用户加入知识库<br/>Java prepare 按正文与页段切片"] --> CHUNK[("MySQL PREPARED 知识文档 / 切片")]
        CHUNK -->|"用户发起知识索引"| KT["Java：固定知识范围、切片和配置版本<br/>索引任务 + Outbox"]
        KT --> KQ["RabbitMQ knowledge.ingest"]
        KQ --> INDEX["NestJS 索引工作流<br/>通过 Java 获取授权输入"]
        INDEX --> EMB["Embedding 服务<br/>每批最多 50 个切片"]
        EMB --> LOG[("lexflow_ai<br/>先持久化已完成的外部调用结果")]
        LOG --> REG["经 Java 登记精确向量位置"]
        REG --> V[("Milvus<br/>向量 + 来源标识；不存原文")]
        V --> RECEIPT["索引收据 HTTP 回调 Java"]
        RECEIPT --> ACTIVE[("MySQL 发布有效索引<br/>记录索引版本与向量制品")]
    end
    FILESTAGE -->|"使用已解析的固定来源"| INDEXSTAGE
```

上传、解析、知识切片准备和索引发布是不同阶段。解析回调保存正文与页段，加入知识库时由 Java 准备切片；索引消费者使用已准备的切片。普通文档解析不调用模型，当前没有自动 OCR。案件知识和律所公共知识使用各自的授权范围。

Milvus Collection 按模型身份与维度隔离。检索还需限定租户、知识库、案件或公共范围、有效索引版本及授权文档。原文始终由 Java 按当前权限提供。

证据：[文件前端请求](../web/src/api/files.ts)、[解析消费者](../ai-service/src/documents/document-parse.consumer.ts)、[解析器](../ai-service/src/documents/parsers/document.parser.ts)、[知识切片准备](../service/src/main/java/com/lexflow/service/KnowledgeService.java)、[索引工作流](../ai-service/src/knowledge/knowledge-index-workflow.service.ts)、[索引器](../ai-service/src/knowledge/knowledge-indexer.ts)、[Milvus 存储](../ai-service/src/knowledge/milvus-knowledge.store.ts)。

## 5. 资料检索与 RAG 问答

```mermaid
sequenceDiagram
    autonumber
    participant W as React 资料问答
    participant J as Java 会话与检索编排
    participant D as MySQL 业务库
    participant A as NestJS 内部 HTTP
    participant V as Milvus
    participant M as 模型供应商

    W->>J: 提交问题 + requestId
    J->>D: 校验私有会话、租户、知识范围、当前权限和有效索引
    J->>A: knowledge/search，携授权检索范围
    A->>M: Query Embedding
    A->>V: 按授权范围与版本过滤检索
    V-->>A: 切片 ID / 文档 ID / 分数
    A-->>J: 候选命中标识
    J->>D: 校验来源并读取授权原文
    opt 配置了排序模型
        J->>A: knowledge/rerank，携候选原文
        A->>M: Rerank
        A-->>J: 合法排序结果
        J->>D: 重新检查来源权限
    end
    alt 有可用授权原文
        J->>A: ai/answer，携问题和 evidence
        A->>M: LLM JSON 回答
        M-->>A: 回答、依据充分性和引用编号
        A-->>J: 校验后的模型结果
    else 没有可用原文
        J->>J: 首轮记为依据不足，跳过回答模型
    end
    opt 首轮无原文或明确依据不足
        J->>J: 在同一授权库扩大召回一次
        J->>A: 再次检索，沿用授权与可选排序流程
        A-->>J: 扩大后的候选标识
        J->>D: 补全当前有效授权原文
        alt 获得不同的有效原文
            J->>A: ai/answer，携合并筛选后的 evidence
            A->>M: LLM JSON 回答
            M-->>A: 回答及引用
            A-->>J: 校验后的模型结果
        else 仍为空或有效原文未变化
            J->>J: 保留依据不足，不重复调用回答模型
        end
    end
    J->>D: 再授权、核验引用、保存会话和答案
    J-->>W: 完整答案 + 固定来源引用 / 依据不足
```

整个问答编排在 Java 的 `AiConversationBiz` 和 `KnowledgeSearchBiz`；AI 服务负责检索、可选排序和回答的执行。有效资料不足时最多扩大一次，回答模型最多调用两次；异常或引用无效不会自动重发付费调用。用户点击引用下载时仍通过 Java 再授权。

证据：[会话编排](../api-core/src/main/java/com/lexflow/website/biz/AiConversationBiz.java)、[检索编排](../api-core/src/main/java/com/lexflow/website/biz/KnowledgeSearchBiz.java)、[检索接口](../ai-service/src/knowledge/knowledge-search.controller.ts)、[问答接口](../ai-service/src/ai/legal-question.controller.ts)、[问答页面](../web/src/pages/Cases/CaseQuestionPanel.tsx)。

## 6. 文书异步导出

```mermaid
sequenceDiagram
    autonumber
    participant W as React 文书 / 任务页
    participant J as Java 导出业务
    participant D as MySQL 业务库
    participant Q as RabbitMQ
    participant C as Java DocumentExportConsumer
    participant S as MinIO 私有对象

    W->>J: 提交导出，指定已保存文书版本
    J->>J: 校验当前权限、文书版本及 AI 草稿复核状态
    J->>D: 同事务固定源版本，保存导出任务与 Outbox
    J-->>W: taskId
    J->>Q: OutboxPublisher 发布 document.export
    Q->>C: 投递导出任务
    C->>J: 认领任务并复查授权与执行版本
    J->>D: 读取固定 document_version.content_text
    D-->>J: 已保存的版本正文
    J->>J: 生成 TXT
    J->>S: 写入私有导出对象
    J->>D: 登记摘要与结果，发布成功状态
    C->>Q: ACK
    W->>J: 轮询导出状态
    J-->>W: 文件已准备好
    W->>J: 用户主动点击下载
    J->>J: 重新检查当前权限
    J->>S: 读取对象并校验摘要
    J->>J: 读取后再次授权
    J-->>W: 文件字节
```

导出由同一个 Java 应用内的消费者执行。未复核的 AI 草稿不能导出；任务固定提交时的已保存版本，之后编辑不改变该任务结果。成功只表示文件已生成。取消会撤销结果发布资格，迟到对象进入清理登记。

证据：[导出编排](../api-core/src/main/java/com/lexflow/website/biz/DocumentExportBiz.java)、[Java 导出消费者](../api-core/src/main/java/com/lexflow/website/core/mq/DocumentExportConsumer.java)、[导出业务服务](../service/src/main/java/com/lexflow/service/DocumentExportService.java)、[导出源正文查询](../service/src/main/java/com/lexflow/dao/DocumentExportRepository.java)、[导出面板](../web/src/pages/Cases/DocumentExportPanel.tsx)。

## 7. 异常恢复、清理与审计

```mermaid
flowchart TB
    MSG["AI 任务消息"] --> RUN["NestJS AI 消费执行"]
    RUN -->|"可重试的瞬时基础设施故障"| RETRY["延迟重试<br/>10 秒 / 60 秒 / 300 秒"]
    RETRY --> MSG
    RUN -->|"永久故障 / 次数耗尽 / 结果不确定"| DLQ["死信队列 DLQ"]
    DLQ --> COLLECT["Java TaskDeadLetterCollector<br/>登记投递账本与待人工处理状态"]
    COLLECT --> DECIDE["用户查看原因，决定继续处理或取消"]
    DECIDE -->|"申请继续处理"| CHECK["Java 校验恢复条件<br/>必要时询问 AI 工作流恢复资格"]
    CHECK -->|"允许恢复"| NEW["新执行轮次 / 新 Outbox"]
    NEW --> MSG

    OP["模型外部调用"] --> LOG[("AI workflow_operation<br/>STARTED / SUCCEEDED / FAILED")]
    LOG -->|"已成功"| REUSE["复用保存结果"]
    LOG -->|"遗留 STARTED"| UNKNOWN["结果不确定<br/>停止自动重复付费调用"]

    DELETE["文件删除 / 过期导出 / 无效向量"] --> LEDGER[("MySQL 清理与回收账本")]
    LEDGER --> FILEWORK["Java FileCleanupWorker"]
    FILEWORK --> STORAGE[("MinIO 对象清理")]
    LEDGER --> VWORK["Java 向量回收 Worker<br/>内部 HTTP → NestJS"]
    VWORK --> VSTORE[("Milvus 精确来源版本删除")]

    AUDIT["请求审计"] --> DB[("MySQL 审计记录")]
    AUDIT -->|"数据库写入失败"| BUFFER["本地持久审计缓冲"]
    BUFFER --> REPLAY["Java AuditReplayWorker<br/>校验后幂等重放"]
    REPLAY --> DB
```

“待人工复核报告”和“外部调用结果不确定”可能都使用 `WAITING_HUMAN`，但含义及可执行操作不同。结果不确定时不能通过确认报告绕过检查；继续处理也不承诺会重新调用模型。

上图的死信收集与待人工处理路径针对五类 AI 任务。文书导出使用 Java 的租约检查和延迟 Outbox 重投，常见存储故障或租约过期最多延迟重试三次，耗尽后进入 `FAILED`。导出消费者未能处理的异常会进入独立导出 DLQ，由租约恢复或排队任务人工重投兜底，不由上述 AI 死信收集器统一处理。

AI 工作流库保存线程锁、检查点、检查点写入及独立外部调用操作记录。检查点用于恢复进度，操作日志用于复用已完成结果；这两个机制共同约束重复执行。内部 HTTP 校验服务凭据，外部模型调用限制为白名单 HTTPS 域名，SDK 不自动重试。

仓库另有 `ops/backup` 离线加密备份恢复工具，覆盖业务库、AI 工作流库及被引用对象，并支持独立审计缓冲备份。数据库恢复时将向量索引失效，后续需主动重建；该工具不应画成常驻在线服务，也不表示完整备份恢复验收已经完成。

证据：[投递与重试](../ai-service/src/infrastructure/messaging/task-delivery.service.ts)、[工作流存储](../ai-service/src/infrastructure/persistence/mysql-checkpointer.ts)、[工作流表](../ai-service/sql/001_workflow_checkpoints.sql)、[死信收集](../api-core/src/main/java/com/lexflow/website/core/mq/TaskDeadLetterCollector.java)、[请求审计写入](../service/src/main/java/com/lexflow/service/audit/RequestAuditWriter.java)、[备份工具](../ops/backup/lexflow-backup.cjs)。
