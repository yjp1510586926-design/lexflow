# LexFlow 产品需求与技术架构设计

版本：v1.1 · React 版 · 开发基线  
日期：2026-09-29  
定位：可分阶段实施的法律业务与 AI 协作平台

> 来源说明：根据本聊天中用户补充的产品范围、Docker 开发方案、架构演进及 AI 技术分工整理。此前 Word 附件未取得，本文件不是原附件的逐字恢复。用户随后授权按本设计开发；当前实现范围见 [项目说明](../README.md#当前状态)。

## 1. 产品目标与边界

LexFlow 面向律所及其工作人员，将案件、成员、文书和证据集中管理，并逐步增加法律问答、知识库检索、合同审查及案件分析能力。

第一目标是完成“登录 → 创建案件 → 添加成员 → 上传文件”的真实业务闭环。第二目标是实现可靠异步任务和可追溯的 AI 结果。第三目标是在业务成熟后按实际负载与维护成本拆分服务。

AI 输出作为辅助材料，必须能追溯引用、处理版本和人工复核记录。系统不得将模型生成内容自动标记为已审核法律结论，不因检索不到资料而编造出处。

### 1.1 首期范围

- 律所租户、用户登录、角色权限与案件数据权限。
- 案件创建、列表、详情、状态管理和成员管理。
- 文件上传、下载、证据元数据与访问控制。
- 基础操作审计、错误处理与必要回归测试。

### 1.2 后续范围

文书及版本、任务中心、RabbitMQ 异步处理、知识库入库与 RAG、AI 法律助手、合同审查、案件分析、人工确认与工作流恢复。

### 1.3 暂不纳入

支付计费、法院系统对接、电子签章、自动提交法律材料、移动端、多地域容灾及第一天拆分大量微服务。外部法律数据采购与授权、OCR 供应商、模型付费套餐另行选型。

## 2. 用户、租户与权限

### 2.1 建议角色

| 角色 | 职责与权限边界 |
|---|---|
| 律所管理员 | 管理本律所用户和角色；默认可管理本律所案件，敏感操作留痕 |
| 案件负责人 | 管理被授权案件、案件成员、文件及文书 |
| 案件成员 | 按案件授权查看或编辑，不因同属律所自动获取全部案件 |
| 只读成员 | 查看被授权材料，不允许编辑、删除和管理成员 |

平台运维账号不默认获得跨租户材料读取权。若未来需要平台支持访问，应设计显式授权、时效及审计流程。

### 2.2 权限判断

每次业务请求执行：已登录 → 有效租户成员 → 操作权限 → 目标对象属于租户 → 案件或知识库数据权限。前端隐藏按钮仅改善体验，最终权限由 Java 服务判定。

建议权限码包括 `case:read/create/update/member`、`file:read/upload/delete`、`document:read/write`、`task:read/cancel`、`knowledge:manage`、`ai:query/review`、`audit:read`。

租户标识从服务器验证后的会话获得，不能信任请求体传入的 tenantId。切换租户必须重新验证成员资格。Redis 键、对象存储路径、消息、向量记录和工作流 checkpoint 均须包含租户范围。

## 3. 业务需求与验收

### 3.1 登录与组织

首期建议账号密码登录，由律所管理员创建用户，不开放自助注册。密码使用成熟单向哈希算法，不保存明文。会话建议采用 Redis 服务端会话和 HttpOnly Cookie；本机开发经 React 代理访问 Java，生产启用 HTTPS、Secure Cookie 及 CSRF 防护。

验收：错误凭证使用统一提示；退出立即失效；禁用用户后旧会话失效；登录失败有限速；日志中不出现密码或会话密钥。

### 3.2 案件管理

字段包括案件编号、名称、类型、简介、负责人、状态、立案日期及归档日期。建议状态为 `DRAFT → ACTIVE → ARCHIVED`；恢复归档需负责人或管理员权限并留痕。

负责人创建案件后自动成为管理成员。列表支持关键词、状态、类型和负责人过滤及分页。成员须为当前租户有效用户。替换负责人需原子更新角色；不得删除最后一名负责人。

验收：案件编号租户内唯一；无权用户不能通过猜测 ID 读取案件；跨租户成员添加失败；归档案件默认只读。

### 3.3 文件与证据

首期建议采用 Java 接收 multipart 后流式写入 MinIO，降低浏览器直传签名流程的实现成本。限制建议为单文件 50 MB，允许 PDF、DOCX、TXT、JPEG、PNG；具体上限待确认。验证扩展名、内容类型和文件签名，文件名只作展示，不作为对象路径。

对象键由服务端生成：`tenants/{tenantId}/cases/{caseId}/{fileId}/{versionId}`。Bucket 保持私有。下载前重新校验权限，首期优先 Java 代理下载；若后续改为短期签名链接，需要接受其有效期内无法立即撤回的限制。

文件状态：`UPLOADING → AVAILABLE`，失败进入 `FAILED`。先登记元数据再上传，成功后确认可用；失败补偿或定时清理孤立对象。证据另存来源、收集时间、说明和顺序，不修改原始文件。SHA-256 用于完整性核验，不据此跨租户共享权限。

验收：超限和类型错误拒绝；失败文件不能下载或进入 AI；重复完成回调不生成重复版本；删除后检索和下载不可见；删除对象异步重试可追踪。

### 3.4 文书

首期业务闭环之后实现文书创建、草稿编辑、版本列表、恢复历史版本和导出。文书归属案件，版本不可覆盖，恢复历史版本产生新版本。AI 生成结果保存为待复核草稿。

验收：并发编辑通过版本号识别冲突；历史版本可追溯作者与时间；无权限用户无法读取历史版本。

2026-10-01 实施进度：案件文书页可导出当前已保存版本 TXT，中文文件名和 UTF-8 正文已有实际下载校验。同步接口校验当前文书权限、案件范围及可选 expectedVersionId，版本变化时拒绝导出；未审核 AI 当前版本不能导出。保存或恢复新版本将文书置为草稿，需重新发布。前端在权限失效时清除旧正文，不重复请求。此同步下载只记审计。§3.5 的异步 TXT 导出已接入 V14 表结构、Java 消费者和任务中心，并通过独立 HTTP/RabbitMQ/MinIO 与浏览器验收。新提交绑定当时的当前已保存版本，完成后下载该固定版本；后续编辑不改变结果。排队/运行中取消立即撤销发布资格，重复消息不新增文件。旧同步接口保留兼容，不能用它的验收代替新链路验收。

### 3.5 任务中心

统一展示解析、知识入库、合同审查及导出任务。状态为 `QUEUED/RUNNING/WAITING_HUMAN/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED`。状态转换需条件更新；终态不能被迟到消息覆盖。进度未知时展示处理阶段，不伪造百分比。

TXT 导出由 Java 专用消费者处理，无需模型配置。提交返回 202 与 taskId，使用租户和操作者范围内的幂等标识。文件先生成到私有 MinIO，成功后才发布下载资格；公开任务详情不返回对象路径。读取文件前后重新授权并校验摘要。存储失败和五分钟租约到期最多延迟重试三次（10/60/300 秒），新 attempt 与 stateVersion 隔离旧执行者；排队任务可人工重新投递。每次上传前登记对象清理记录，取消或旧尝试不能发布结果；成功发布的对象不进入清理。删除记录保留并每日复查，防止迟到写入残留；记录压缩和完整导出保留期仍属待补运维策略。

展示任务类型、关联对象、提交人、开始结束时间、重试次数和可理解错误。取消为尽力执行，任务确认停止前显示取消中。人工恢复必须校验当前暂停节点、状态版本和权限。

### 3.6 知识库与 RAG

区分租户公共知识库与案件私有材料。上传后执行解析、切分、Embedding、向量写入，全部成功后发布知识版本；旧版本在切换成功前保持可用。

检索限定 tenantId、授权 knowledgeBaseId/caseId 和有效文档版本，不能先全库召回再只依靠提示词隔离。召回后通过 Java 权限接口再次确认材料有效性，避免成员权限变化或删除传播延迟造成泄露。

结果必须包含文档名、版本、页码或段落、片段 ID。没有足够证据时明确说明不足。模型或向量维度更换需新建版本化 Collection 并重建索引，不混用不同模型向量。

### 3.7 AI 法律助手与合同审查

法律助手支持会话、历史问答及引用查看；答案明确区分材料事实、引用内容与模型建议。

合同审查输入为可访问的合同版本、审查立场与关注点。输出合同类型、条款摘要、风险等级、依据、修改建议、待补材料和人工复核状态。风险分级不能仅依赖模型自报置信度；缺少关键材料、存在高风险或依据冲突时进入人工复核。

验收：引用能定位原文；无依据不产生伪造法条；用户取消或重复提交有明确结果；中断后从已保存节点恢复；越权材料不出现在答案与引用中。

### 3.8 审计日志

记录操作者、租户、动作、对象、结果、时间、请求追踪 ID，以及权限/成员变化前后值。记录登录、下载、删除、授权、AI 提交与人工复核。避免记录全文证据、模型密钥、密码和完整 Cookie。失败审计不得随业务回滚丢失，应有独立写入或可靠缓冲机制。

## 4. V1 技术架构

**模块化 Java 主应用 + 独立 NestJS AI 服务。V1 不宣称为完整微服务架构。**

```text
React + TypeScript
        │ HTTP / 同源代理
        ▼
Spring Boot 3 / Java 21（单一启动、发布和扩容单元）
  ├─ 组织、登录、RBAC / 数据权限
  ├─ 案件、成员、文书、文件
  ├─ 任务、审计、知识库元数据
  ├─ MySQL：业务事实与任务结果
  ├─ Redis：会话、缓存、限速
  ├─ MinIO：私有文件
  └─ RabbitMQ：异步命令与结果
        │ 内部 HTTP / MQ
        ▼
NestJS AI Service
  ├─ LangChain.js：模型、Embedding、Prompt、Tools、Retriever
  ├─ LangGraph.js：状态、分支、人工确认、恢复、流式执行
  ├─ Milvus：向量索引
  └─ Embedding / Rerank / LLM 适配器
```

Java 拥有业务数据库和业务权限决策。NestJS 不直接更新案件、用户和文件表，通过内部接口获取经授权的任务上下文、提交结果。内部请求需要服务身份认证，并在 Java 端验证任务、租户及资源关联，不能仅依赖消息中的 tenantId。

同一个 MySQL 实例可承载业务库和独立的 AI 工作流状态库，但使用不同库及不同账号隔离所有权。LangGraph 持久化适配器需另行技术验证；不能把内存 checkpoint 宣称为跨进程恢复，也不为使用默认适配器擅自增加 PostgreSQL。

### 4.1 Java 工程分层

参考 `fullstack-2601` 的 Maven 与 Controller → Biz → Service → DAO 方式。V1 建议为：

- `api-core`：唯一 Java 启动入口，HTTP Controller、端侧 DTO、Biz 编排与安全配置。
- `service`：按 auth、organization、permission、casework、document、file、task、knowledge、audit 划分业务包，含领域服务、DAO 和实体。
- `framework`：统一响应、基础异常、分页和通用上下文；不依赖业务模块。
- `web`：React + TypeScript。
- `ai-service`：独立 NestJS 应用。

依赖方向 `api-core → service → framework`。模块化是代码组织，不等于独立服务。统一 Java 21，Spring Boot 保持 3.x；具体依赖锁定到验证后的版本。

### 4.2 AI 目录职责

```text
src/
  ai/       models、prompts、embeddings、llm
  rag/      loaders、splitters、retrievers、rerankers、milvus
  agents/   legal-agent、contract-agent、case-agent
  graphs/   contract-review、case-analysis、legal-qa
  tools/    search-law、search-case、get-case、generate-document
  tasks/    消息消费、幂等、取消、结果回传
```

所有 Tool 都经过权限与输入边界校验；文档中的指令属于资料，不得提升为系统指令或触发任意工具调用。外部模型调用设超时、额度和重试上限，不在日志中输出密钥或完整隐私材料。

## 5. MySQL 逻辑表设计（补充设计）

以下为逻辑模型，确认后再编写 DDL 与迁移。业务 ID 建议 BIGINT，向前端以字符串传输，避免 JavaScript 精度问题。时间统一存 UTC，展示转用户时区。可变实体包含 created_at、updated_at、version；删除策略按数据类别确定。

| 表 | 关键字段与约束 |
|---|---|
| tenant | id、name、status |
| sys_user | id、username 唯一、password_hash、status；身份表不混入租户业务权限 |
| tenant_user | tenant_id、user_id、status；唯一(tenant_id,user_id) |
| role | id、tenant_id、code、name；唯一(tenant_id,code) |
| permission | code 主键、name；系统定义权限目录 |
| user_role | tenant_id、user_id、role_id；复合唯一，角色和成员必须属于同租户 |
| role_permission | tenant_id、role_id、permission_code；复合唯一 |
| case_info | id、tenant_id、case_no、title、type、owner_id、status、version；唯一(tenant_id,case_no) |
| case_member | tenant_id、case_id、user_id、member_role；唯一(tenant_id,case_id,user_id) |
| case_party | id、tenant_id、case_id、name、party_type、contact_encrypted |
| file_object | id、tenant_id、case_id、status、display_name、created_by |
| file_version | id、tenant_id、file_id、object_key、sha256、size、mime_type、version_no；唯一(tenant_id,file_id,version_no) |
| evidence | id、tenant_id、case_id、file_version_id、source、collected_at、description、sort_order |
| document | id、tenant_id、case_id、title、current_version_id、version |
| document_version | id、tenant_id、document_id、version_no、content_object_key、source_task_id、review_status |
| knowledge_base | id、tenant_id、case_id 可空、name、scope、status |
| knowledge_document | id、tenant_id、knowledge_base_id、file_version_id、index_version、status |
| knowledge_chunk | id、tenant_id、knowledge_document_id、index_version、page_no、offset、text_object_key |
| ai_session | id、tenant_id、user_id、case_id 可空、title |
| ai_message | id、tenant_id、session_id、role、content_object_key、task_id、citation_json |
| async_task | id、tenant_id、case_id、type、resource_version_id、status、idempotency_key、attempt、state_version、result_object_key |
| contract_review | id、tenant_id、case_id、file_version_id、task_id、risk_level、review_status、reviewed_by |
| audit_log | id、tenant_id、actor_id、action、object_type、object_id、result、trace_id、created_at |
| outbox_event | event_id、tenant_id、task_id、routing_key、payload、status、next_retry_at |
| inbox_event | consumer_name、event_id、status、processed_at；唯一(consumer_name,event_id) |

租户表关联应使用复合唯一键及复合外键，或在所有持久化操作中强制校验 tenant_id，不能仅靠前端传参。案件列表建立(tenant_id,status,created_at,id)索引；任务列表建立(tenant_id,created_by,created_at,id)索引。审计与消息表按实际增长归档。软删除后是否复用业务编号需确认，默认不复用。

## 6. Milvus Collection（补充设计）

建议命名 `lexflow_chunks_{embeddingModelVersion}_{schemaVersion}`，使用一个 Collection 的强制租户过滤作为初期方案；更高隔离等级可按租户拆库/集合，但不提前创建大量空集合。

字段：chunk_id（VARCHAR 主键）、tenant_id、knowledge_base_id、case_id、document_id、file_version_id、index_version、page_no、embedding（FLOAT_VECTOR）。维度由选定 Embedding 模型决定，不写死未经选择的数值。正文与原始文件保存在 MySQL/MinIO，向量记录只保留必要引用元数据。

建议使用 COSINE，相似度与向量归一化策略以模型要求为准；初期索引选择 AUTOINDEX，数据量增长后用基准测试决定是否调整。TopK、Rerank 候选数、切片大小和重叠范围属于可评估参数，不作为固定质量保证。

发布索引时以 MySQL 中的有效 index_version 为准；检索必须匹配该版本。删除及撤权立即影响 Java 可见性，向量清理异步进行。查询过滤由服务端结构化构造，禁止拼接用户输入表达式。

## 7. HTTP API 边界（补充设计）

对外入口统一为 Java `/api/v1`。前端不直连 NestJS、Milvus、MySQL 或 MQ。列表统一分页并限制最大页大小；写入验证参数；错误返回稳定业务码、可理解消息和 traceId。

| 方法与路径 | 用途 |
|---|---|
| POST /auth/login、POST /auth/logout、GET /auth/me | 会话 |
| GET /tenant/users | 选择本租户成员 |
| GET/POST /cases | 案件列表与创建 |
| GET/PATCH /cases/{id} | 详情和乐观锁更新 |
| GET/POST /cases/{id}/members | 成员列表与添加 |
| PATCH/DELETE /cases/{id}/members/{userId} | 角色调整与移除 |
| POST /cases/{id}/files | 首期 multipart 上传 |
| GET /files/{id}/download、DELETE /files/{id} | 下载和逻辑删除 |
| GET/POST /cases/{id}/documents | 文书管理 |
| GET/POST /documents/{id}/versions | 历史版本与新版本 |
| GET/POST /knowledge-bases | 知识库 |
| POST /knowledge-bases/{id}/documents | 创建入库任务 |
| POST /ai/sessions、POST /ai/sessions/{id}/messages | 法律问答 |
| POST /contracts/{fileVersionId}/reviews | 审查任务 |
| GET /tasks、GET /tasks/{id} | 任务列表与详情 |
| POST /tasks/{id}/cancel、POST /tasks/{id}/resume | 取消与人工恢复 |
| GET /audit-logs | 授权范围内审计 |

异步创建返回 202 与 taskId，前端先轮询任务详情，后续再增加 SSE。耗时请求使用租户范围内的 Idempotency-Key；同键不同输入应拒绝，不能返回错误任务。

内部 Java 接口建议 `/internal/v1/tasks/{id}/context`、`/internal/v1/tasks/{id}/results` 和 `/internal/v1/resources/authorize`。只供认证服务使用；结果回传校验 taskId、attempt 和 stateVersion，过滤重复和迟到结果。

## 8. RabbitMQ 约定（补充设计）

Exchange：`lexflow.commands`、`lexflow.events`，类型 topic，持久化。按消费者职责绑定队列，不按租户创建无界数量队列。

| Routing Key | 消费者 | 目的 |
|---|---|---|
| document.parse.requested.v1 | AI 解析消费者 | 解析指定文件版本 |
| document.export.requested.v1 | Java 导出消费者 | 生成固定文书版本的私有 TXT 文件 |
| knowledge.index.requested.v1 | AI 入库消费者 | 切分和索引 |
| contract.review.requested.v1 | AI 审查消费者 | 合同流程 |
| case.analysis.requested.v1 | AI 案件消费者 | 案件分析 |
| ai.task.completed.v1 | Java 结果消费者 | 落库成功结果 |
| ai.task.failed.v1 | Java 结果消费者 | 记录失败 |
| file.delete.requested.v1 | 文件清理消费者 | 清理已删除对象 |

消息信封：eventId、schemaVersion、tenantId、taskId、resourceVersionId、attempt、traceId、occurredAt、payload。不得携带长期密钥、完整证据正文或默认有效的跨租户下载地址。

业务事务写入任务和 outbox，发布器使用 publisher confirm 后标记已发送。消费者按 eventId 幂等，提交结果后 ACK；崩溃重投允许重复传输但不重复生效。结果落库与 inbox 状态在同一事务完成。

失败采用有限次数延迟重试（建议 3 次，间隔 10 秒、1 分钟、5 分钟），通过 TTL 重试队列与死信交换机实现，避免无限 requeue 热循环。进入死信后展示人工处理入口；重试策略区分瞬时故障和永久输入错误。首次版本不引入分布式事务框架。

## 9. LangGraph 流程与恢复

合同审查流程：

```text
START → 授权与文件版本校验 → parseDocument
  → classifyContract → extractClauses → retrieveLegalKnowledge
  → analyzeRisk → 条件分支
      材料不足：WAITING_HUMAN → 补充材料后重新校验
      高风险/依据冲突：深入分析 → 人工复核
      普通风险：普通分析
  → generateSuggestions → generateReport → 保存结果 → END
```

Graph State 建议包含 taskId、tenantId、文件版本、审查立场、条款、检索引用、风险、待确认事项、节点版本和报告引用。threadId 由服务器生成并绑定租户与任务，不能由客户端任意指定他人的线程。

每个可恢复节点前后保存 checkpoint；恢复时重新校验权限和文件版本。人工确认提交 decisionId 与预期 stateVersion，确保只消费一次。外部写入节点设置幂等键，避免恢复后重复生成文书或扣费。模型请求超时、限流与重试均需计入任务预算。

法律问答流程：权限检查 → 问题规范化 → 检索 → Rerank → 证据充足判断 → 生成带引用回答 → 引用校验。证据不足可扩大授权范围内检索一次，仍不足则说明无法作答，不能循环无限检索。

持久化方案是阶段二技术验证项：需证明 NestJS 重启后可恢复、租户不能读取他人 checkpoint、并发恢复不会重复执行。LangGraph 能力参考官方文档，实际适配器与版本以验证结果为准。

2026-10-01 实施进度：已加入 LangGraph.js 与独立 MySQL 数据库/账号的 checkpoint 适配器，验证合成工作流的跨进程暂停恢复、执行互斥及连接丢失后的旧执行者写入隔离。外部操作记录在请求前保存开始状态，成功结果在图快照之前保存并供恢复复用；请求发出后崩溃则保留不确定状态，禁止自动重复请求。现已接入合同审查消费者、Java 专用任务接口及前端人工复核入口。真实 HTTP/MQ 链路验证暂停后 NestJS 重启，再确认完成，重复决定不再调用模型；浏览器已验证提交、查看原文/风险、复核与任务中心报告。已新增明确选择律所公共参考并复查权限；补充材料后续审及显式依据冲突分支仍待实现，不能视为完整法律审查产品已完成。具体初始化方式和调用约束见 `ai-service/README.md`。

## 10. 本地开发与成本

React、Java、NestJS 在本机热更新；MySQL、Redis、RabbitMQ、MinIO 使用 Docker。Milvus、etcd 及其卷初始化仅在 `vector` profile 启用。基础设施使用项目独立命名卷、回环地址端口及开发专用凭据，不复用真实业务库。

根目录提供 `.env.lexflow.example` 和 `docker-compose.dev.yml`。首次复制 `.env.lexflow.example` 为 `.env`；若保留自定义文件名，命令必须显式增加 `--env-file`。

```sh
cp .env.lexflow.example .env
# 日常开发
docker compose -f docker-compose.dev.yml up -d mysql redis rabbitmq minio
# RAG 阶段
docker compose -f docker-compose.dev.yml --profile vector up -d
# 暂停高资源组件，保留数据
docker compose -f docker-compose.dev.yml --profile vector stop milvus etcd
# 全部停止，保留命名卷
docker compose -f docker-compose.dev.yml --profile vector down
```

不要把 `down -v` 作为日常操作，它会删除开发数据卷。修改环境密码不会自动修改已有数据库卷中的账号，需要执行相应账号变更。

建议端口：React 5173、Java 8080、NestJS 3001、MySQL 13306、Redis 16379、RabbitMQ AMQP 15672/控制台 15673、MinIO API 19000/控制台 19001、Milvus 19530/健康检查 19091。端口只是本地默认，可通过配置调整。

Milvus 官方当前列出 Standalone 最低约 8 GB、推荐 16 GB 内存，因此只在向量开发时开启。Embedding、Rerank、LLM 先以接口适配层隔离；测试使用确定性假实现，真实模型请求使用手动开关和额度，不自动产生费用。

参考：[Milvus 资源要求](https://milvus.io/docs/prerequisite-docker.md)、[官方 Compose 安装说明](https://milvus.io/docs/install_standalone-docker-compose.md)。仓库配置需固定镜像版本，并验证本机 CPU 架构、权限与健康检查后才称为可运行。

## 11. 三阶段交付计划

### 阶段一：Java 核心业务闭环

实现登录、租户成员、RBAC、案件 CRUD、案件成员、文件上传下载和基础审计。先不接真实 LLM、Milvus、LangGraph，不因容器已存在就提前加入 MQ 业务流程。

完成标准：两个租户及不同案件权限测试通过；登录到上传链路可由界面完成；文件失败补偿、越权下载、负责人变更有测试；新环境能按 README 初始化和启动。

### 阶段二：异步任务与 AI/RAG

先实现任务状态、outbox/inbox、RabbitMQ 重试死信与 NestJS 消费；再接解析、知识库、Milvus 和带引用法律问答；最后接 LangGraph 合同审查及人工复核。每次只增加一条可验收链路。

完成标准：重复消息不重复生效；消费者崩溃后恢复；过期版本不可引用；越权检索为零；模型失败和取消可见；持久化工作流在进程重启后恢复且不重复副作用。

### 阶段三：强化与服务演进

补全文书体验、案件分析、导出、运维观测与评估集。依据实际负载选择拆分文档服务；再按学习和项目需要拆案件、认证等服务。拆分前先明确定义数据所有权和接口。

不要承诺无证据的 QPS、工期或模型准确率。性能目标在数据量、硬件和模型选型明确后制定，用基准测试记录。

## 12. 架构演进 V1 / V2 / V3

| 架构版本 | 部署形态 | 进入条件 |
|---|---|---|
| V1 | 单 Java 主应用 + NestJS AI | 当前基线；先完成业务闭环 |
| V2 | Java Core + Document Service + AI | 文件/文档任务形成独立负载或发布瓶颈 |
| V3 | Gateway + Auth/Case/Document + AI，按需增加其他服务 | 需要独立扩容、数据所有权与运维能力已具备 |

微服务按业务能力拆分，不按 Controller/Service/DAO 拆。服务独立发布和扩容，拥有自己的逻辑数据库及账号。不同服务可以使用同一个物理 MySQL 实例，但不得跨服务直接 JOIN 或写入他方表；使用 API 或事件同步必要只读投影。

拆分文档服务时，先定义文件元数据、对象存储、解析任务的所有权，再迁移数据、切换调用、验证事件一致性和回滚方案。Gateway、Nacos、OpenFeign、Sentinel 仅在对应问题出现后选用，不把它们作为 V1 的前置任务。

## 13. 测试与验收矩阵

| 层面 | 必测场景 |
|---|---|
| 身份权限 | 失效会话、禁用用户、跨租户 ID、同租户未授权案件、只读用户写入 |
| 业务一致性 | 创建负责人、最后负责人保护、归档只读、乐观锁冲突 |
| 文件 | 超限、伪造类型、上传中断、孤立对象、私有下载、删除后访问 |
| MQ | 重投、发布后崩溃、消费后未 ACK、死信、迟到结果、取消竞争 |
| RAG | 版本替换、撤权、删除、无证据、引用定位、恶意文档指令 |
| Graph | 人工暂停、重复恢复、进程重启、模型超时、重复副作用 |
| 工程 | Java/React/NestJS 构建，基础 profile 与 vector profile 健康检查 |

AI 评估使用获授权或合成的固定材料，记录问题、期望引用、允许答案范围和拒答样例。评估引用准确性、检索相关性、权限隔离、任务成功率、延迟和调用成本。禁止将缺少真实模型与数据的单元测试当作 AI 质量验收。

## 14. 待确认事项

以下保留形成需求基线时的待确认清单；后续选型和实现不在此重复登记，以现行业务规则及部署说明为准。

1. 前端采用 React + TypeScript；粘贴内容中的 Vue3 视为旧版描述。
2. V1 是单 Java 主应用 + 独立 NestJS，不在首期引入 Spring Cloud。
3. 首期账号由管理员创建，角色采用管理员/负责人/成员/只读成员；律所管理员默认可管理租户内案件。
4. 首期上传采用 Java 代理，建议 50 MB 白名单类型；暂不支持超大文件分片续传。
5. Embedding、Rerank、LLM 供应商与预算尚未选定，向量维度随模型确定；不得自行触发付费调用。
6. LangGraph 的 MySQL 持久化适配方案需在阶段二验证；不默认新增 PostgreSQL。
7. 保留期限、备份周期、文件清理宽限期、租户退出导出规则需在真实上线前明确。
8. 本文中的表、API、Routing Key 和状态机为设计基线；实际已实现范围以代码和现行业务规则为准。

## 15. 交付状态

当前学习型 MVP 的实现范围及后续计划见 [项目说明](../README.md#当前状态)。本文保留产品与架构基线，现行业务细节见 [业务规则](business-rules.md)。

## 补充确认：用户自行配置 AI Key

用户确认 AI Key 由用户手动提供，项目不内置共享 Key、不自动购买额度。产品增加“设置 → AI 配置”，V1 由律所管理员为本律所配置，普通成员使用本律所已授权的模型配置。

- 配置项：服务商、接口地址、模型名称、API Key；Embedding 与 Rerank 可独立配置。没有选定服务商前不写死模型或向量维度。
- Key 为只写字段：保存后只返回“已配置”及脱敏标识，不回显原文，不存 localStorage，不进入源码、日志、审计详情或 MQ 消息。
- 服务端按租户加密保存凭据，加密主密钥由部署环境提供；NestJS 只在执行已授权任务时取得对应租户凭据，禁止跨租户复用或回退至公共 Key。
- “测试连接”由用户主动触发，界面说明可能产生少量模型费用；保存配置本身不调用模型。用户可替换或删除配置。
- 未配置时登录、案件、成员、文件和文书照常可用；AI 操作提示先配置，不创建注定失败的任务，也不返回模拟答案。
- 验收覆盖未配置、错误 Key、连接超时、保存后脱敏、租户隔离、替换/删除配置；真实调用仅在用户配置并主动触发后进行。


配置方式见 [部署与 AI 配置](mvp-deployment.md#ai-配置)，实际调用、持久化及质量边界见 [业务规则](business-rules.md)。
