# Java 代码结构与开发约定

LexFlow 采用 `fullstack-2601` 的 Java 分层与目录规则，保留当前三个 Maven 模块及唯一启动入口 `api-core`。依赖方向为 `api-core → service → framework`，目录调整不改变接口路径、数据库结构或运行配置。

```text
framework/src/main/java/com/lexflow/framework/
├── context/       请求追踪上下文
├── exception/     通用异常与响应处理
├── model/         通用响应、分页和实体基类
└── utils/         通用工具，例如 PageUtil

service/src/main/java/com/lexflow/
├── dao/           Mapper 与 JDBC Repository
│   └── model/     数据库实体
└── service/       业务 Service，按类名查找业务能力
    ├── vo/        业务值对象与报告
    ├── ai/        凭据加密辅助组件
    ├── audit/     审计缓冲、事件和持久化辅助组件
    ├── file/      文件检查、存储和生命周期辅助组件
    ├── identity/  保持兼容的会话身份类型
    └── permission/ 案件权限策略

api-core/src/main/java/com/lexflow/website/
├── WebsiteApplication.java
├── api/           HTTP Controller，内部接口位于 api/internal
├── biz/           接口业务编排
│   └── dto/       有明确业务名称的请求对象
└── core/
    ├── config/    Spring、Security、MyBatis、RabbitMQ 配置
    ├── filter/    请求追踪、内部认证和请求审计过滤器
    ├── client/    内部 AI 服务客户端
    ├── mq/        消息发布、消费和死信处理
    ├── storage/   对象清理、向量清理与导出维护
    ├── audit/     审计重放调度
    └── bootstrap/ 管理员初始化
```

## 各层职责

- Controller 负责 HTTP 参数、校验和响应包装。请求对象放在 `biz.dto`，名称应体现操作，例如创建案件或确认审查；JSON 字段和校验注解与接口契约保持一致。
- Biz 编排接口特有的多服务流程。直接调用一个 Service 的简单接口可以保持薄 Controller，无需增加只转发方法的 Biz。
- Service 负责业务授权、租户隔离、事务、状态流转与幂等处理。公共入口须检查当前身份和资源权限；通用 `BaseService` 本身不提供授权。
- DAO 负责持久化查询和更新，数据库实体放在 `dao.model`。业务报告放在 `service.vo`，不与持久化实体混用。
- Core 负责当前应用的技术接入；Framework 只承载通用能力，不依赖业务 Service 或 DAO。业务专属辅助组件按职责留在 Service 的子包中。

## 修改时保持的约束

`WebsiteApplication` 扫描 `com.lexflow`，以发现同级 DAO 中的 JDBC Repository；MyBatis 单独扫描 `com.lexflow.dao`。新增类的 `package` 必须与 Java 源码路径一致，测试与被测类的包可见访问关系也须同步维护。

`com.lexflow.service.identity.Identity` 是 Redis 会话中序列化的认证主体，保留其类名、包名和字段，避免让既有会话因类迁移无法反序列化。审计事件与缓冲组件保留同包，以维护其受限访问和重放契约。

重命名局部变量或提取私有方法时，保持校验、查询和写入顺序；尤其保留文件打开前后的二次授权、任务版本校验、消息确认顺序、模型调用的不确定结果处理及事务传播规则。SQL 参数名、DTO 字段、路由、配置属性和 Bean 名不能因可读性整理而改变。

格式化使用项目根目录的 `npm run format`，提交前运行 `npm run format:check`。Java 结构调整后使用 `mvn clean verify`，避免旧包下的编译产物干扰组件扫描；真实基础设施集成测试仍须按项目既有说明单独启用。

## 开发注释约定

类和公共业务方法的注释说明职责、输入约束及调用边界；复杂分支说明为什么需要该判断，例如重复回调、权限撤销、人工决定或不确定的模型执行结果。避免逐句翻译代码；修改状态流转、事务或恢复逻辑时，同步更新相邻注释。

任务的 `attempt` 表示执行轮次，`stateVersion` 表示任务状态版本；文书的记录修订号与正文版本 ID 也各有用途，不应混用。报告和解析片段使用解析正文中的 UTF-16 字符区间 `[startOffset, endOffset)`，不代表原文件字节位置。报告对象的格式校验不代替服务层的租户权限、材料版本和真实来源核验。

## 案件接口约定

所有接口使用服务端会话中的租户标识，写入操作必须附带 CSRF token。

### 列表

`GET /api/v1/cases?page=1&size=20&keyword=&status=&type=&ownerId=`

可选过滤字段应在未选择时省略，不发送空 status。关键词匹配标题或案件编号，百分号及下划线按普通字符处理。状态、类型、负责人和关键词组合生效；只统计调用者可访问的案件。

响应 `data`：`{items: CaseView[], total: number, page: number, size: number}`。ID 均使用字符串，版本号为整数。最大每页 100 条。

### 案件成员

- `GET /api/v1/cases/{id}/members`：成员列表。
- `POST /api/v1/cases/{id}/members`：`{userId,role}`，role 为 EDITOR 或 READER。
- `PATCH /api/v1/cases/{id}/members/{userId}`：`{role}`，调整非负责人角色。
- `DELETE /api/v1/cases/{id}/members/{userId}`：移除非负责人。
- `POST /api/v1/cases/{id}/owner`：`{userId,version}`，原子转交负责人。

转交时校验目标为本律所有效用户，原负责人保留 EDITOR 成员资格，新负责人设为 OWNER；案件版本递增，旧版本提交返回 409。归档案件禁止修改成员和转交，需先按权限恢复。

案例状态遵循 DRAFT → ACTIVE → ARCHIVED，授权负责人或律所管理员可以将 ARCHIVED 恢复为 ACTIVE。

### 案件日期

创建请求可带 `filedOn: "2026-09-15"`；详情和列表返回 `filedOn`、`archivedAt`。更新请求中省略 `filedOn` 保留旧值，显式 `null` 清空，非空值必须为有效的 `YYYY-MM-DD` 日期。日期改动沿用案件版本校验。归档时间由服务端生成，恢复办理时清空，客户端不能自行指定。

### 证据目录

- `GET /api/v1/cases/{caseId}/evidence?page=1&size=20`：目录分页，返回 `items/total/page/size/canEdit`，顺序相同按目录 ID 排列。
- `GET /api/v1/cases/{caseId}/evidence/{fileVersionId}`：读取某原件版本的目录，尚未记录返回 `data: null`。
- `PUT /api/v1/cases/{caseId}/evidence/{fileVersionId}`：`{version,source,collectedAt,description,sortOrder}`；创建用 version 0，更新使用读取到的目录版本，重复创建或冲突返回 409。

原件须属于当前租户和案件且可用。来源必填，最多 500 字；说明可为空字符串，最多 4000 字；顺序为 0–100000。收集时间可为空，非空须带时区，例如 `2026-09-20T09:30:00+08:00`，服务端以 UTC 保存/返回并支持历史日期。目录不会替换原件或改变其摘要。

读取要求 `file:read` 及案件读取权；保存还要求 `file:upload` 和案件编辑权。归档与只读成员不能编辑，失败或已删除的原件不出现在目录。原件下载沿用私有文件接口及其授权检查。

### 权限失效与时间显示

案件、文件、知识库和任务页面收到 401/403 后，清空已加载的内容、操作缓存、表单或弹窗；迟到的成功响应不能重新填回已撤销的数据。切换案件、知识库或任务后丢弃旧请求结果；下载权限失效时，迟到的 blob 不能触发下载。负责人候选仅列出当前用户可访问范围，前端隐藏控件不能代替后端授权。

任务时间由 Java 返回带 `Z` 的 UTC 时刻并保留微秒，浏览器按当地时间显示。历史无时区值明确标注，未知时间显示空占位；不得把无时区值当作已确认的 UTC 时间。任务记录保留关联对象、源版本、提交人、提交/开始/完成时间。对应历史专项见 [界面检查](evidence/README.md#ui-completion-checksjson)。

## 固定材料评估

12 项合成材料和人工示例只验证离线评分工具，尚未完成真实供应商的质量、性能和费用基准。核心 DeepSeek 业务的历史费用授权不自动扩展为该评估的授权；原件与法律质量仍需独立人工审核。

评估工具位于 `ai-service/evaluation/`，使用方式见 [工具说明](../ai-service/evaluation/README.md)。默认只生成无网络计划，输出路径须不存在：

```sh
node ai-service/evaluation/run-real-materials.cjs --config ai-service/evaluation/real-evaluation.example.json --output /tmp/lexflow-evaluation-plan.json
```

实际运行前固定 runId、数据集摘要、供应商及 HTTPS 地址、Embedding/LLM 模型、token/调用/金额上限、定价来源和核对日期。示例保持 `authorized: false`；`--execute` 不能跳过授权校验，未授权时不读取凭据或访问网络，不自动替换模型或地址。

真实评估使用独立空业务/AI schema、数据库账号、私有 bucket、RabbitMQ vhost 和 Redis namespace，仅用登记的合成律所、账号和材料。Key、转发器 token 和登录密码保存在独立 `0600` 文件，不进入命令参数、报告或仓库。私有 HTTPS 验收记录器仅转发批准的地址、模型和用途，不修改正常业务的工作流与重试规则。

每次出站前不可覆盖地记录意图、预留费用并 fsync；失败、超时、缺少 usage 或进程中断不释放未知预留，也不自动重发。使用对应模型的本地 tokenizer 与已审阅的消息封装开销。应用估算不是供应商最终账单，实际 usage、请求 ID、状态及响应摘要应与隔离供应商项目的账单另行核对。

每题通过实际 Java 登录/CSRF、上传、解析、索引、检索及问答链路，按真实 chunk/document/fileVersion 映射固定标签，不能把期望引用直接喂给模型替代检索。分别建立另一租户与未授权成员的拒绝探针、真实历史会话、公共/私有隔离材料及旧来源删除探针；拒绝请求的供应商调用增量必须为 0。延迟使用本地单调时钟，任务成功率的分母包括失败与未完成项，缺少 usage 记为未知。

报告区分计划、模拟、真实执行与账单核对。机器词匹配分数不证明法律正确性；逐题人工记录审阅人、日期、判定和理由，不能把手写答案计为模型通过。工作流人工暂停、重启和重复恢复另由业务专项证明。执行后仅清理本 runId 登记资源，按归属清单核对剩余项；缺少清理证明须如实保留。
