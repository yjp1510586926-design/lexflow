# LexFlow

按 [产品需求与架构 v1.1](docs/LexFlow-产品需求与技术架构-v1.1.md) 开发的法律业务与 AI 协作平台。V1 采用模块化 Java 主应用 + 独立 NestJS AI 服务，前端 React + TypeScript。

## 当前状态

当前为学习型 MVP，覆盖登录与成员权限、私有文件与文书版本、异步任务、核心 AI 业务和独立单机容器部署。现行业务与数据边界见 [业务规则](docs/business-rules.md)。

真实 Embedding/RAG 供应商、完整法律质量/性能/费用基准、完整备份恢复、另一操作系统安装及 V2/V3 服务拆分为后续范围。现有技术业务闭环不证明法律输出质量。

文档入口：

- [产品需求](docs/LexFlow-产品需求与技术架构-v1.1.md)：范围、角色和技术架构。
- [开发约定](docs/java-code-structure.md)：Java 分层、案件接口及兼容性约束。
- [部署说明](docs/mvp-deployment.md)：运行、管理员初始化、AI 配置与指标。
- [业务规则](docs/business-rules.md)：版本、权限、AI 复核、任务恢复与对象清理。

## 工程

- `api-core`：唯一 Java 启动应用，端口 8080。
- `service`：共享业务服务、DAO、数据库实体、权限策略及 MySQL 迁移。
- `framework`：通用类型。
- `web`：React 工程。
- `ai-service`：NestJS 独立服务，执行文档解析、按需知识索引、向量检索、基于授权原文的模型回答及持久化合同审查。

Java 目录按 `fullstack-2601` 的分层约定组织：业务服务放在 `com.lexflow.service`，DAO 与实体放在 `com.lexflow.dao`、`dao.model`；接口模块使用 `api`、`biz`、`biz.dto` 和 `core`。各层职责与兼容性约束见 [Java 代码结构](docs/java-code-structure.md)。

## 本地运行

单机容器交付步骤见 [MVP 部署](docs/mvp-deployment.md)，独立整栈及重建后的持久化已有实际验收。以下保留开发运行方式。

需要 JDK 21、Maven、Node.js 24 与 Docker Compose。

```sh
cp .env.lexflow.example .env
docker compose -f docker-compose.dev.yml up -d mysql redis rabbitmq minio
mvn package
java -jar api-core/target/api-core-0.1.0-SNAPSHOT.jar
```

当前默认配置对应示例开发凭据；修改 `.env` 后，本机 Java 进程也需设置对应环境变量，Java 不会自动读取 Compose 的 `.env`。数据库 URL 用 `DATABASE_URL`，用户名/密码用 `MYSQL_USER`/`MYSQL_PASSWORD`。

本机没有全局 Maven 时：

```sh
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn' package
```

首次账号通过 [管理员初始化说明](docs/mvp-deployment.md#管理员初始化) 创建，没有预置密码。

React：在 `web` 下运行 `npm ci && npm run dev`。NestJS：在 `ai-service` 下运行 `npm ci && npm run dev`。开始索引前，管理员需在设置中配置 Embedding Key；部署者还需向 NestJS 注入精确的 `LEXFLOW_AI_ALLOWED_HOSTS` 模型域名白名单。模型 Key 不放在环境示例或前端代码中。

登录前请核对入口：开发模式默认是 `http://127.0.0.1:5173`，连接 Java `8080` 和开发 MySQL `13306`；整栈容器使用部署配置中的 `WEB_PORT`（默认 `8088`，本机验收实例曾用 `8089`）及独立数据库卷。律所编号、账号和密码只在创建它们的数据库中有效，两套环境不共享账号；同名账号也不表示律所编号或密码相同。

AI 配置页中，律所管理员手动保存自己的 Key，再勾选费用确认后主动测试连接。只测试已保存且未编辑的配置；刷新和读取结果不会调用模型。连接测试需先初始化 [独立 AI 工作流数据库](ai-service/README.md)，并设置相同的内部凭据、`AI_WORKFLOW_DATABASE_URL` 和精确模型域名白名单。LLM 使用最多 32 个输出 token；Embedding 只发送一段合成文本，Rerank 只发送两段通用文档，三类均为 15 秒、无 SDK 自动重试。每租户每用途最多同时一项测试、每分钟新建三项。每次保存生成新配置版本，旧成功结果随替换或删除失效；网络中断或未知结果不会自动重发，可先只读取记录，再自行决定是否新测。接口可用不代表法律回答质量。

问答入口在案件详情 → 案件知识库 → 问案件资料，或律所知识库 → 根据律所资料提问；AI 助手页提供入口。管理员还需配置 LLM Key；当前接入 OpenAI 兼容的 HTTPS Chat Completions 接口，模型需支持 JSON 对象响应。首轮最多五段原文供 LLM；有效资料不足时在同一授权库扩大一次，合并最多 50 段候选、筛选最多十段，LLM 最多调用两次。错误或无效引用不自动重复付费请求。资料为空直接说明依据不足。会话私有，每会话最多 100 次提问；5 分钟未结束的请求在刷新历史或再次提问时标记失败。

可选排序在 AI 配置中选择“检索排序模型”，接口需兼容 Cohere v2，填写基础地址如 `https://服务商地址/v2`，服务端追加 `/rerank`。保存不调用模型；保存后主动检索和提问会使用排序配置并可能产生排序费用，删除配置即可停止调用。未配置时明确使用向量顺序；已配置但服务失败时直接报错，不假称已排序。返回编号、数量和分数须合法，排序前后都会重新检查来源权限。

仅在 RAG 阶段启动 `docker compose -f docker-compose.dev.yml --profile vector up -d`。停止用 `down`，不要使用删除数据卷的 `down -v`。

合同审查入口在案件文件 → 审查合同，后续在任务记录中查看并复核。需要管理员手动配置 LLM Key，且先按 [AI 服务说明](ai-service/README.md) 初始化独立工作流数据库并设置 `AI_WORKFLOW_DATABASE_URL`。可明确选择当前案件知识库或本律所公共知识库；此时还需对应的 Embedding 配置、已发布索引及相应资料读取/问答权限，不能选择其他案件。合同正文最多 4 万 UTF-16 字符，支持 TXT、DOCX、文本层 PDF。普通流程两次 LLM 调用，高风险增加一次深入分析，各次最多 1800 个输出 token，不自动重试。无依据时报告明确提示待补资料，人工确认后仍保留该提示；确认不代表法律结论已获验证。外部请求结果不确定时不能直接确认或重复调用，应取消后主动决定是否另建任务。

文书下载入口在案件详情 → 案件文书 → 导出（编辑页为“导出 TXT”）。提交后显示任务状态；“文件已准备好”后手动下载，也可在任务记录中再次查看。提交固定为当时的已保存版本，未保存的编辑不会进入文件；之后保存新版本不会改变已提交任务。界面版本在提交前已过期时须刷新。草稿可以导出，未通过人工复核的 AI 草稿不能导出。提交、查看和下载需当前有效账号、律所成员资格、案件读取范围、`document:read` 与 `task:read`；取消还需 `task:cancel`。编辑/发布/恢复需要 `document:write` 与案件编辑资格。归档案件可按权限导出，不能编辑。

导出由 Java 专用 RabbitMQ 消费者生成私有 MinIO TXT，不需要 AI Key 或启动 NestJS/Milvus。单次最多 8 MB；成功表示文件已生成，不表示用户已下载或草稿已发布。文件生成与下载都会复查权限，下载前校验字节摘要，并在读取文件后再次授权。取消会立即撤销结果发布资格；迟到上传的对象登记清理。存储暂时失败或处理租约过期最多延迟重试三次；排队任务可点击“继续处理”重新投递，失败终态须返回文书重新导出。旧同步下载接口保留兼容，新界面使用异步任务。详细规则见 [业务规则](docs/business-rules.md)。

AI 起草入口在案件文书编辑页，需先保存正文，再填写生成要求并确认可能产生费用。结果作为独立待复核版本保留；查看正文并复核通过后才切换当前版本。提交后的人工编辑不会被生成结果覆盖。权限、持久化和验收限制见 [AI 文书生成](docs/business-rules.md#ai-文书生成)。

案件资料问答在资料不足时扩大授权知识库的召回一次，仍不足明确拒答；错误不会触发第二次调用，重复请求沿用记录。页面说明扩大可能增加模型费用，权限失效后清空旧答案和引用。详细规则见 [资料问答与检索](docs/business-rules.md#资料问答与检索)。

公共资料问答、合同参考、案件分析和五类任务投递恢复已接入；知识索引要求初始化独立 AI 工作流数据库。现行权限与恢复规则见 [业务规则](docs/business-rules.md)。

## 代码格式与注释

在项目根目录安装开发工具后，可统一整理 Java、TypeScript、React、CSS、Maven POM 及运维脚本：

```sh
npm ci --ignore-scripts
npm run format
npm run format:check
```

格式规则由根目录 `.prettierrc.json` 与 `.editorconfig` 维护，Java 使用 4 空格缩进，其余代码使用 2 空格缩进。格式器版本由根目录 `package-lock.json` 固定；它不参与应用运行。构建产物、依赖、文档和数据库迁移不在格式化范围内。

注释优先解释业务约束和实现原因，例如租户权限、版本校验、任务幂等、消息确认、模型调用不确定结果及人工复核边界；修改这些流程时同步更新注释，避免重复描述字段名或显而易见的操作。
