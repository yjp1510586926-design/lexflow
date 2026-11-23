# 合同智能审查与人工复核：全栈功能代码导读

核对日期：2026-10-27。下列行号对应本次工作区代码，后续改动可能使行号变化。

## 1. 把它作为一个完整功能

建议功能名称：**合同智能审查与人工复核工作流**。

功能从用户在案件文件中选定合同版本开始，覆盖提交审查、异步执行、条款抽取、风险报告、原文溯源、人工复核、补材续审和结果查询。React、Java、NestJS/LangGraph 是同一功能的不同实现层，可以合并成一项全栈职责。

本体是合同审查页面、专用接口、合同业务数据、AI 消费者和审查图。案件、登录权限、文件存储、任务中心、Outbox 和知识库提供共享能力；阅读时重点理解合同流程怎样接入它们。

## 2. 前端 URL 与实际点击路径

| 用途 | 浏览器 URL | 进入方式 |
| --- | --- | --- |
| 本机开发入口 | [http://127.0.0.1:5173/](http://127.0.0.1:5173/) | 登录后进入系统；本次已确认入口返回 LexFlow 页面 |
| 发起合同审查 | 同上，地址保持 `/` | 侧栏「案件」→ 选择案件 →「文件与证据管理」→ 合同文件行「审查合同」 |
| 查看历史审查 | 同上，地址保持 `/` | 侧栏「任务记录」→ 合同审查任务 →「查看审查」 |
| 管理模型配置 | 同上，地址保持 `/` | 侧栏「AI 配置」，由管理员保存本律所 LLM 配置 |
| 单机容器部署默认入口 | [http://127.0.0.1:8088/](http://127.0.0.1:8088/) | 部署配置中的 `WEB_PORT` 可覆盖端口；这是默认配置，本次没有确认该端口正在运行 |

**当前没有 `/cases/:id`、`/contract-reviews/:id` 等独立前端路由。** 应用用 React 状态 `currentTab` 和 `selectedCaseId` 切换页面，案件详情再用 `activeTab` 切换页签。刷新后不会根据地址自动恢复某个案件或审查弹窗。不要把后面的 `/api/v1/...` 当成浏览器页面地址。

导航依据：[App.tsx:20](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/App.tsx:20)、[CaseDetailPage.tsx:245](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/pages/Cases/CaseDetailPage.tsx:245)。开发请求经 [vite.config.ts:8](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/vite.config.ts:8) 将 `/api` 代理到 Java 默认端口 `8080`；浏览器通过 Java 接口提交任务，NestJS 经消息队列执行审查。

## 3. 按调用顺序读代码

### 3.1 React：从入口到报告交互

| 顺序 | 文件 | 重点位置与职责 |
| --- | --- | --- |
| 1 | [CaseFilesTab.tsx](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/pages/Cases/CaseFilesTab.tsx:558) | 558 行判断可审查文件；566 行点击「审查合同」；597 行将 `caseId/fileVersionId/fileName` 传给审查组件。要求案件未归档，文件为 AVAILABLE 的 TXT、DOCX 或 PDF |
| 2 | [ContractReviewPanel.tsx](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/pages/Cases/ContractReviewPanel.tsx:203) | 前端核心。203 行 `submit` 提交立场、关注点、参考库和幂等键；159 行 `refresh` 查询报告；191 行轮询；407 行报告展示；263 行查看引用；247 行读取补材候选；638 行补材续审；678 行人工复核。77 行处理失权和迟到响应 |
| 3 | [contracts.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/api/contracts.ts:70) | API 与类型定义。70 行提交、82 行查询、83 行复核、93 行同次继续、98 行补材候选、100 行续审、113 行引用详情、115 行下载引用原件 |
| 4 | [TasksPage.tsx](/Users/yangjinpeng/Documents/Projects/Java/lexflow/web/src/pages/Tasks/TasksPage.tsx:383) | 任务中心的「查看审查」入口；492 行用 `taskId` 复用同一个 `ContractReviewPanel`，重新读取已持久化的报告 |

先追一条请求：`ContractReviewPanel.submit → contracts.submitContract → POST /api/v1/contract-reviews`。先看提交字段，再看返回的 `taskId/status/stateVersion` 怎样驱动页面查询与按钮状态。

### 3.2 Java：业务规则、事务与报告发布

| 顺序 | 文件 | 重点位置与职责 |
| --- | --- | --- |
| 5 | [ContractReviewController.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/api/ContractReviewController.java:45) | 浏览器访问的 HTTP 入口，接收当前登录身份及请求参数，委托合同服务；提交返回 202 |
| 6 | [ContractReviewService.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/service/src/main/java/com/lexflow/service/ContractReviewService.java:161) | Java 业务核心。161 行 `request` 校验权限、输入和幂等，同事务创建任务、审查记录及 outbox；249 行 `view`；351 行 `supplement`；500 行 `approve`；601 行 `begin`；621 行 `input`；769 行 `apply` 接收结果；1079 行 `event` 构造投递事件 |
| 7 | [ContractReviewRepository.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/service/src/main/java/com/lexflow/dao/ContractReviewRepository.java:154) | 合同 DAO：报告、决定、补材轮次和材料快照的 SQL；任务状态按 attempt/stateVersion 条件更新 |
| 8 | [OutboxPublisher.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/core/mq/OutboxPublisher.java:48) | 共享发布器。将事务内保存的事件发送到 RabbitMQ，收到 confirm 且未被退回后标记 SENT；消息可能重复，业务必须幂等 |
| 9 | [InternalContractReviewController.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/api/internal/InternalContractReviewController.java:42) | NestJS 调用的 Java 内部接口：取输入、认领任务、获取授权依据、回传报告。使用服务间认证 |

队列绑定见 [RabbitMqConfig.java:255](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/core/config/RabbitMqConfig.java:255)：`lexflow.tasks` 交换机 → `contract.review.requested.v1` 路由键 → `lexflow.q.contract.review` 队列。

Java 保持业务状态与报告发布的最终决定权。NestJS 返回报告后，Java 仍会校验当前权限、源文件摘要、模型身份、引用、执行轮次和状态版本。取消后或旧轮次的迟到结果无法改写当前任务。

### 3.3 NestJS / LangGraph：执行审查与恢复

| 顺序 | 文件 | 重点位置与职责 |
| --- | --- | --- |
| 10 | [contract-review.consumer.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.consumer.ts:59) | 消费合同队列并核对消息与当前任务。116 行调用工作流，136 行向 Java 回传结果，随后 ACK。投递失败按类型处理 |
| 11 | [contract-workflow.service.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-workflow.service.ts:41) | `run` 从 Java 认领任务并取得固定输入，创建模型、checkpointer 和运行时依赖。68 行定义授权复查，78 行下载并解析合同，82 行获取知识依据 |
| 12 | [contract-review.graph.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.graph.ts:83) | 注册节点、组织条件边、编译图并恢复执行。状态和节点分别位于 [contract-review.state.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.state.ts:113) 与 [contract-review.nodes.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.nodes.ts:319) |
| 13 | [task-callback.service.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/infrastructure/java/task-callback.service.ts:269) | NestJS → Java 的内部 HTTP 客户端。269 行取输入或认领，354 行检索依据，380 行回传报告 |
| 14 | [mysql-checkpointer.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/infrastructure/persistence/mysql-checkpointer.ts:658) | 共享持久化能力。445 行读取快照，549 行保存快照，658 行持久化步骤结果，759 行用执行锁约束同任务并行运行 |

### 3.4 完整请求链

```text
用户选定合同文件版本并提交
  → React ContractReviewPanel.submit
  → contracts.submitContract
  → Java ContractReviewController.submit
  → ContractReviewService.request
  → 同事务保存 async_task + contract_review + outbox_event
  → OutboxPublisher → RabbitMQ
  → NestJS ContractReviewConsumer.handle
  → ContractWorkflowService.run
  → Java /begin 认领任务、取得固定输入
  → LangGraph runContractReview
  → task-callback.contractResult
  → Java /result → ContractReviewService.apply
  → 保存报告和任务状态
  → React getContract 查询并展示报告
```

## 4. LangGraph 节点、人工复核与补材续审

节点行为集中在 [contract-review.nodes.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.nodes.ts:319)。状态字段见 [contract-review.state.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.state.ts:113)。条件边和恢复入口见 [contract-review.graph.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/workflows/contract-review/contract-review.graph.ts:83)。

| 节点/机制 | 源码位置 | 实际行为 |
| --- | --- | --- |
| `parseDocument` | 节点文件第 328 行 | 下载并解析固定版本合同。续审复用原报告合同正文 |
| `extractClauses` | 节点文件第 343 行 | 提取条款并检查引文。续审沿用原条款 |
| `retrieveKnowledge` | 节点文件第 358 行 | 有明确参考库才检索。未选择则返回空依据 |
| `analyzeRisk` | 节点文件第 372 行 | 根据合同、立场、关注点、依据与补材分析风险及修改建议 |
| `deepReview` | 节点文件第 398 行 | 有高风险或依据冲突时增加二次分析，保留已识别的高风险与冲突 |
| `generateReport` | 节点文件第 451 行 | 汇总正文、条款、风险、依据、待补材料和复核标记 |
| `humanReview` | 节点文件第 474 行 | 用 `interrupt` 持久化暂停，恢复时核对 Java 已保存的决定 ID |
| 条件路由 | 编排文件第 133、143 行 | 决定是否深入分析及是否进入人工复核 |
| 人工恢复 | 编排文件第 171 行 | 读取 checkpoint，用 `Command({resume: decisionId})` 继续人工节点 |

**人工确认链：**页面 678 行 → `approveContract` → Java `approve` 500 行 → 保存意见及决定 ID → 状态改为 RUNNING 并重新写 outbox → 同一工作流从 `humanReview` 恢复 → 结果回调后完成。人工确认接口本身不会直接宣布任务成功。

**补材续审链：**页面 638 行 → `supplementContract` → Java `supplement` 351 行 → 固定补材版本、摘要、原文与页段，保留上一轮报告 → 创建新轮次及 outbox → 同一个任务按 `contract-round-n` 隔离 checkpoint 与模型操作键，重新检索和分析。不能用新合同替换原合同。

**恢复的边界：**已成功模型步骤复用结果；遗留 STARTED 表示外部调用结果不确定，不能证明模型从未执行，因此不会盲目重发。人工复核与投递异常/结果不确定是不同状态，后两者不能通过「确认已复核」绕过。

## 5. 对照浏览器网络面板的 API

浏览器请求默认发往前端同源 `/api/...`，开发代理转给 Java。下面的 `{id}` 是审查任务 ID，`{chunkId}` 是引用片段 ID。

| 方法与路径 | 功能 | 前端函数 | Java Service |
| --- | --- | --- | --- |
| `POST /api/v1/contract-reviews` | 提交审查，返回 202 | `submitContract` | `request` |
| `GET /api/v1/contract-reviews/{id}` | 状态与报告 | `getContract` | `view` |
| `POST /api/v1/contract-reviews/{id}/approve` | 提交人工复核意见 | `approveContract` | `approve` |
| `POST /api/v1/contract-reviews/{id}/recover` | 排队/执行中任务同次继续 | `recoverContract` | `recover` |
| `GET /api/v1/contract-reviews/{id}/supplement-candidates` | 已解析补材候选 | `contractCandidates` | `candidates` |
| `POST /api/v1/contract-reviews/{id}/supplement` | 补材续审，返回 202 | `supplementContract` | `supplement` |
| `GET /api/v1/contract-reviews/{id}/citations/{chunkId}` | 查看真实原文定位 | `contractCitation` | `citation` |
| `GET /api/v1/contract-reviews/{id}/citations/{chunkId}/download` | 下载所引用的文件版本 | `downloadContractCitation` | `downloadCitation` |

内部接口供服务间调用，不是浏览器页面：

| 方法与路径 | 用途 |
| --- | --- |
| `POST /internal/v1/contract-reviews/{id}/begin` | 认领任务，返回当前状态版本和输入 |
| `GET /internal/v1/contract-reviews/{id}/input?attempt=...&stateVersion=...` | 读取固定输入并重新检查授权 |
| `POST /internal/v1/contract-reviews/{id}/evidence` | 在已授权参考库检索依据 |
| `POST /internal/v1/contract-reviews/{id}/result` | 按租户、执行轮次和状态版本接收结果 |

死信恢复另走共享 [TaskDeliveryRecoveryController.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/api/TaskDeliveryRecoveryController.java:21) 的 `/api/v1/tasks/{taskId}/delivery-recovery` 与 `/recover`，不能与合同 `/recover` 的同次继续混为一谈。

## 6. 数据与依赖，读到需要时再展开

| 数据/能力 | 文件 | 与本功能的关系 |
| --- | --- | --- |
| 合同业务表 | [V13__contract_review.sql](/Users/yangjinpeng/Documents/Projects/Java/lexflow/service/src/main/resources/db/migration/V13__contract_review.sql:1) | `contract_review` 保存立场、关注点、参考库、模型身份、报告与决定 |
| 补材与轮次 | [V21__contract_supplement_review.sql](/Users/yangjinpeng/Documents/Projects/Java/lexflow/service/src/main/resources/db/migration/V21__contract_supplement_review.sql:3) | `contract_review_round` 保存上轮报告；`contract_review_supplement` 保存固定补材及页段 |
| 任务与事务消息 | [V4__document_task_outbox.sql](/Users/yangjinpeng/Documents/Projects/Java/lexflow/service/src/main/resources/db/migration/V4__document_task_outbox.sql:53) | 共享 `async_task` 与 `outbox_event`；后续迁移继续增加执行协议等字段 |
| AI 独立持久化库 | [001_workflow_checkpoints.sql](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/sql/001_workflow_checkpoints.sql:1) | `workflow_thread/checkpoint/checkpoint_write/operation` 保存锁归属、图状态与步骤结果 |
| 可选知识检索 | [KnowledgeSearchBiz.java](/Users/yangjinpeng/Documents/Projects/Java/lexflow/api-core/src/main/java/com/lexflow/website/biz/KnowledgeSearchBiz.java:106)、[milvus-knowledge.store.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/knowledge/milvus-knowledge.store.ts:306) | 合同选定参考库后使用；需要读懂授权过滤、原文补全与引用复查 |
| 合同文件解析 | [document.parser.ts](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/src/documents/parsers/document.parser.ts:21) | 合同工作流直接下载固定版本并解析；补材候选则要求先有成功解析任务 |

初始合同的固定输入由任务绑定的 `fileVersionId`、文件版本/摘要、`contract_review` 业务参数和模型身份共同组成，Java 没有单独一张“合同输入快照 JSON 表”。补充材料有独立原文快照；LangGraph checkpoint 保存执行状态。

## 7. 最短查看与演示步骤

1. 打开开发入口，登录后进入非归档案件，选择已上传可用的 TXT、DOCX 或文本层 PDF 合同。
2. 进入「文件与证据管理」→「审查合同」，选择甲方/乙方/中立，填写关注点。首次理解主流程可选「仅审查合同原文」。
3. 核对费用提示后由用户主动提交；观察任务排队、审查、报告展示。没有参考依据时报告会要求人工复核，并保留依据不足提示。
4. 查看条款原文、风险原因和修改建议，填写复核意见并确认；观察同一任务恢复后完成。
5. 关闭弹窗，从「任务记录」→「查看审查」重新打开已保存结果。
6. 理解主流程后再看可选分支：选已发布索引的授权知识库以查看引用；或提前上传另一份材料并成功「提取文字」，在待复核报告中补材续审。

运行条件：Java、RabbitMQ、私有文件存储、NestJS、已初始化的独立 AI 工作流数据库，以及管理员保存的 LLM 配置、精确模型域名白名单和一致的服务间认证配置。选择知识库才额外要求 Embedding、Milvus 及已发布索引。初始化说明见 [ai-service/README.md](/Users/yangjinpeng/Documents/Projects/Java/lexflow/ai-service/README.md:24)。

本次只核对代码、URL 和入口响应，没有提交审查、调用模型或修改现有业务数据。

## 8. 作为一项全栈职责的表述

以下表述适用于实际负责该模块三层开发的情况：

> 负责合同智能审查与人工复核模块的全栈开发，完成 React 审查表单、任务状态、风险报告、原文溯源及补材续审交互；基于 Spring Boot 实现权限校验、审查任务、复核决定及轮次数据管理，通过事务 Outbox 与 RabbitMQ 驱动 NestJS/LangGraph 审查流程，结合 MySQL checkpoint、请求幂等及状态版本控制实现人工暂停恢复和已完成结果复用。

讲解时围绕一个任务展开：前端如何得到 taskId，Java 为什么返回 202，消息如何进入 NestJS，图在哪暂停，人工确认怎样继续，最后由谁决定报告可以发布。
