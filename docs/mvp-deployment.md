# MVP 单机部署

本流程部署同一套真实 React、Java 与 NestJS 应用，业务数据写入 MySQL，登录会话保存在 Redis，文件和导出结果进入私有 MinIO，异步任务使用 RabbitMQ。Web 的 Nginx 将 `/api/` 转到 Java，与页面保持同源。Java、NestJS、数据库、消息队列和对象存储没有宿主机公开端口；内部 `/internal/` 不经过 Web 代理。

基础闭环包含登录、案件和成员、文件上传与解析、人工文书版本及异步 TXT 导出。AI 功能使用管理员在应用中保存的模型配置；没有配置时明确报错，不返回模拟结果。知识索引和资料问答还需要真实 Embedding 配置及可选 `vector` profile。此流程不包含生产备份平台、通知服务或全量模型基准评估。

## 全新安装

宿主机需要 Docker Engine / Docker Desktop、Docker Compose v2 和 OpenSSL。镜像构建分别使用 JDK 21、Node.js 24；宿主机无需另装 Java、Node 或 Maven。默认入口仅监听 `127.0.0.1:8088`。基础服务的内存上限合计约 3.9 GiB；启用 Milvus/etcd 后约 6.2 GiB，构建时需另留内存和磁盘空间。

从仓库根目录开始。生成器只创建不存在的文件，权限为 `0600`，不会覆盖已有配置：

```sh
sh deploy/configure-mvp.sh
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml config --quiet
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml build api
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml build ai-service
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml build web
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml up -d --wait mysql redis rabbitmq minio
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml run --rm workflow-setup
```

`.env.mvp.local` 已由项目忽略规则排除，保留随机基础设施密码、服务间凭据和 AI 配置加密主密钥。它不含模型 API Key。工作流账号 `lexflow_ai` 只获得独立 `lexflow_ai` 库权限，无法读取 Java 业务库。MySQL 首次空卷启动创建账号；`workflow-setup` 明确执行幂等的工作流建表步骤。已有 MySQL 数据卷不会重新执行首次脚本，修改配置文件也不会自动修改其中的账号密码。

首次管理员使用现有 `bootstrap` 入口，只能初始化空业务库。以下交互输入适用于 Bash 和 Zsh；密码不写入命令参数：

```sh
export LEXFLOW_TENANT_NAME='演示律所'
export LEXFLOW_ADMIN_USERNAME='admin'
printf '管理员初始密码（至少12字符，最多72个UTF-8字节）：'
read -r -s LEXFLOW_ADMIN_PASSWORD
printf '\n'
export LEXFLOW_ADMIN_PASSWORD
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml run --rm --no-deps \
  -e LEXFLOW_TENANT_NAME -e LEXFLOW_ADMIN_USERNAME -e LEXFLOW_ADMIN_PASSWORD \
  api --spring.profiles.active=bootstrap
```

看到“首次初始化完成，律所 ID=…”后记下律所 ID，再按 `Ctrl+C` 停止该初始化容器。初始化命令会保持 Java 进程在线，不把进程启动当成初始化完成；重复初始化会拒绝已有业务数据。随后普通启动：

```sh
unset LEXFLOW_ADMIN_PASSWORD
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml up -d --wait
curl --fail http://127.0.0.1:8088/api/health
```

打开 `http://127.0.0.1:8088`，用刚才的律所 ID、用户名和密码登录。`/healthz` 只表示 Web 在线；`/api/health` 表示 Java 可响应；NestJS 内部健康表示进程与消费者启动完成，均不代表外部模型已经可用。验证业务时应实际创建案件、上传 TXT、等待解析任务成功、保存文书并发起 TXT 导出，再下载核对正文。

容器部署与 `5173` 开发入口使用独立数据库。请使用当前实例创建的律所编号、账号和密码；开发环境账号不会自动同步到部署环境，详见 [本地运行说明](../README.md#本地运行)。

## AI 配置与向量能力

部署默认白名单为 `api.deepseek.com`，与已选 DeepSeek 供应商一致。管理员进入应用设置保存 LLM 配置，API Key 经 Java 主密钥加密后按律所持久化，不放进 Compose、React 或镜像。保存与查看配置不会调用模型；连接测试和实际 AI 任务必须由用户主动确认费用后触发。模型名称与当前官方可用模型及应用兼容说明保持一致。

Embedding 是独立用途，不能把 LLM 模型名称直接当作 Embedding。需要知识检索时，先选好真实向量供应商和模型，把其准确域名加入 `LEXFLOW_AI_ALLOWED_HOSTS`，再启动已有向量服务：

```sh
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml --profile vector up -d --wait
```

修改白名单后需重新创建 `ai-service` 容器。单独解析文件与文书 TXT 导出不需要向量模型、Milvus 或模型费用。合同审查、AI 起草和案件分析需要 LLM；选择知识库作为参考时还需要已授权、已发布的真实索引。

## 运行、升级与停止

查看状态和运行日志：

```sh
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml ps
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml logs --tail 100 api ai-service web
```

服务使用具名数据卷，正常重启、镜像重建和 `down` 保留数据。Java 启动时 Flyway 校验并执行新增迁移，不能修改已执行的迁移文件。升级前保存配置与现有数据，完成必要备份后按前述命令重新构建镜像，再 `up -d --wait`。AI 主密钥必须保持不变，否则已有模型配置无法解密。导出期限与详细清理记录归档默认均为 `0`，按已有业务策略明确决定后再启用。

```sh
docker compose --env-file .env.mvp.local -f docker-compose.mvp.yml down
```

若通过远程主机部署，可先保持 loopback 入口并通过 SSH 转发访问。面向外部用户时将 Web 接在已有 HTTPS 反代后，并设置 `COOKIE_SECURE=true`；项目不自动申请域名或证书。需要改变端口或绑定地址时修改私有配置中的 `WEB_PORT` 和 `WEB_BIND_ADDRESS`，再重新创建 Web 容器。

本地开发仍可使用原 `docker-compose.dev.yml`。手动为独立项目补建工作流账号时，`infra/mysql/init-ai-workflow.sh` 支持 `LEXFLOW_COMPOSE_FILE`、`LEXFLOW_COMPOSE_ENV_FILE` 与 `LEXFLOW_COMPOSE_PROJECT_NAME`，默认保持原开发项目行为；必须准确指向要初始化的本地项目，不覆盖已有账号密码。

## 当前验证边界

2026-10-09：独立单机容器部署验收已通过，结果记录在 [mvp-deployment-checks.json](evidence/README.md#mvp-deployment-checksjson)。真实构建的 Java、NestJS、React/Nginx 镜像完成首次管理员初始化和同源登录；真实页面创建案件、上传 TXT、经 RabbitMQ/NestJS 提取原文、保存人工文书版本、提交异步 TXT 导出并实际下载。上传原文 112 字节、导出正文 136 字节均与输入逐字节一致。只读成员能够查阅授权案件和版本，携带有效 CSRF 的文书写入及机构管理请求实际返回 403，业务库没有新增文书。

单独重建 Java 时，额外占用旧 IP 使其地址确实更换，原 Web 容器保持不变，同源健康和业务请求恢复为 200。随后整套 `down` / `up -d --wait` 完成：7 个常驻容器身份全部更换且健康，工作流建表容器退出 0，5 个具名卷保留；11 张业务域表的同一客户端TSV输出摘要一致，包括ASCII模型配置密文与版本。该旧快照没有显式指定UTF8客户端，不能单独证明Unicode字段存储字节相同。重新加载真实页面后 Redis 会话仍有效，案件、成员、文件、文书、解析结果和脱敏配置的7个业务读取均200，MinIO原文件和导出文件的实际下载字节保持一致。

首次尝试暴露的实际部署问题已经修复：MySQL 健康检查使用 TCP，避免首次初始化的临时 socket 被误认作服务就绪；Nginx 使用 Docker 动态 DNS，支持 Java 容器地址变化；Java 五处内部服务客户端统一允许精确的 `ai-service` HTTP 地址及原有本机 HTTP/HTTPS 地址，外部模型配置仍要求 HTTPS。内部地址及既有配置探测、恢复协议的 27 项选定 JUnit 检查均通过；镜像构建跳过测试，测试结果由独立执行提供，不能据此声称全库测试通过。JSON 中的记录还包含准备观察及已经修复的历史失败，记录条数不等于通过测试数。

初次部署阶段使用合成业务及真实基础设施，没有Mock API，真实模型调用与费用为0；该阶段的 [原文档快照](evidence/README.md#mvp-deployment-at-initial-verificationmd) 已按原摘要保留。随后用户明确授权最多5次DeepSeek调用、合计0.05美元，实际4次完成连接、文书起草与合同审查，并在真实页面人工批准。API和AI再次重建后，16张业务表及4张工作流表使用显式utf8mb4、逐字段二进制HEX摘要比较，20张表全部一致；真实页面刷新后的17个读取均200，无新增模型调用。当前模型证据及费用估算见 [DeepSeek验收](evidence/README.md#deepseek-mvp-real-checksjson)。

只读成员仍可看见部分成员管理按钮，后端拒绝无权操作；按钮呈现尚未优化。可选向量profile、Embedding/RAG真实供应商、另一操作系统、公网HTTPS及生产恢复演练均不在当前MVP通过范围。原开发环境容器未被本轮重建；原Milvus退出状态单列，不能描述为全部开发依赖健康或推断退出原因。验收实例保留在 `http://127.0.0.1:8089`，供检查核心流程；临时付费护栏与专属账本卷撤下，正常部署不依赖验收脚本。模型配置仍按律所加密存于应用，后续实际模型请求须在页面主动确认费用。

## 管理员初始化

整栈容器的首次初始化按上文流程执行。以下步骤适用于本机开发模式的全新空库；已有律所数据会拒绝初始化，不覆盖用户和密码。先启动项目 Docker 基础设施并完成 `mvn verify` 构建。

在本机终端设置三个环境变量，再以 `bootstrap` profile 启动 Java：

```sh
export LEXFLOW_TENANT_NAME='我的律所'
export LEXFLOW_ADMIN_USERNAME='admin'
# zsh：交互输入，不把密码写进命令历史或文档
read -s 'LEXFLOW_ADMIN_PASSWORD?初始密码（至少12字符）: '
export LEXFLOW_ADMIN_PASSWORD
java -jar api-core/target/api-core-0.1.0-SNAPSHOT.jar --spring.profiles.active=bootstrap
```

密码的 UTF-8 编码不得超过 72 字节。启动日志只显示新建律所 ID，不输出密码。记下律所 ID，登录时使用该 ID、用户名和密码。初始化失败会回滚整个创建事务。

初始化成功后可停止应用，执行 `unset LEXFLOW_ADMIN_PASSWORD`，以后用普通启动命令，不再携带 bootstrap profile。重复初始化会让该次启动失败，以免误覆盖已有数据。

初始角色：律所管理员、律师、只读成员。律师拥有业务操作权限，但仍须是目标案件的成员；只读成员不能写入案件或文件。

管理接口：

- `GET /api/v1/organization/users`：本律所账号列表。
- `POST /api/v1/organization/users`：新建账号，字段 username、password、name、role。
- `PATCH /api/v1/organization/users/{id}`：设置租户成员 status（ACTIVE/DISABLED）与 role（TENANT_ADMIN/LAWYER/READER）。

这些接口必须使用已登录律所管理员会话，写入附带 CSRF token。现有同名账号不会被静默加入另一个律所。操作不会修改其他律所成员关系；最后一名有效管理员不能被禁用或降级。

## AI 配置

产品入口为“设置 → AI 配置”，由律所管理员手动填写本律所自己的服务商、模型、地址和 Key。普通成员不需要填写，也无权查看 Key。保存配置不触发模型调用，不使用内置公共 Key。

### 部署主密钥

Java 环境变量 `LEXFLOW_AI_MASTER_KEY` 接收 Base64 编码的 32 字节随机密钥，用于加密用户提供的 API Key。它与模型 API Key 是两种不同凭据。主密钥由部署者保管，不提交代码、不回显到前端；多个实例必须使用一致的主密钥。

未设置时普通业务可启动，AI 凭据保存不可用。不得配置默认公共主密钥。丢失或替换主密钥后，旧凭据将无法解密；轮换必须先设计迁移流程，不能直接覆盖环境变量后声称轮换完成。

### 当前已实现

`AiCredentialCipher` 使用 AES-256-GCM、随机 12 字节 nonce 与 128 位认证标签；认证上下文包含租户 ID、用途（LLM / EMBEDDING / RERANK）和格式版本。解密错误不携带凭据内容或底层异常。

测试覆盖加解密、随机密文、跨租户/用途拒绝、篡改拒绝、错误主密钥拒绝、未配置主密钥以及非法主密钥。

### 执行与验收边界

按租户取用凭据、主动连接测试和模型调用已接入 Java → NestJS。管理员保存配置不会联网；连接测试和 AI 业务均须用户主动提交。供应商请求禁止重定向、SDK 自动重试，出站域名须列入 `LEXFLOW_AI_ALLOWED_HOSTS`。外部操作结果使用独立 AI 工作流数据库保存；成功结果复用，遗留不确定请求不自动重发。没有实现供应商账户充值或统一账单系统。

已有接口、MySQL、工作流、浏览器检查证明配置权限与调用协议；其中历史合成模型结果不能证明真实供应商能力或业务质量。2026-10-09 用户明确授权最多5次调用、合计0.05美元；实际DeepSeek调用4次完成连接、文书起草与合同审查，页面人工确认成功。应用配置保存与脱敏回读均200，MySQL密文在私有内存中解密比对通过；API/AI实际重建后，20表HEX摘要一致，结果与配置可读取且没有新增调用。具体证据、usage估算及限制见 [DeepSeek MVP 验收](evidence/README.md#deepseek-mvp-real-checksjson)。


### 配置 API

- `GET /api/v1/settings/ai`：管理员读取本律所用途、服务商、地址、模型和更新时间，以及 `storageReady`；不返回 Key 或密文。
- `PUT /api/v1/settings/ai/{purpose}`：用途为 LLM / EMBEDDING / RERANK，JSON 字段 provider、baseUrl、model、apiKey。替换配置需要重新输入 Key；保存不联网。
- `DELETE /api/v1/settings/ai/{purpose}`：删除本律所对应配置。

V6 迁移增加 ai_configuration 表，按 tenant_id + purpose 唯一。Controller → Biz（授权与事务审计）→ Service（校验及加密）→ Mapper。服务地址只接受不含账号、查询参数与片段的 HTTPS URL；NestJS 仅允许部署者指定的精确域名并禁止重定向。域名白名单由部署者控制；不应加入可由普通用户控制的主机。保存接口本身不执行网络请求。


### 设置页面

侧栏“AI 配置”可选择对话、向量、排序三种用途，填写服务商、HTTPS 地址、模型和只写 API Key。服务未配置主密钥时禁用保存；接口错误明确展示；替换需要重新输入 Key，提交后清空输入，删除需页面确认。不写入浏览器持久存储。主动连接测试使用少量合成文本并须逐次确认费用；读取、刷新和重复读取结果不会重新调用模型。成功只表示当前配置的接口兼容性，不表示法律分析正确。

### DeepSeek 的最小配置

在部署环境设置 `LEXFLOW_AI_ALLOWED_HOSTS=api.deepseek.com`，并生成独立 `LEXFLOW_AI_MASTER_KEY`。管理员在页面选择 LLM，填写服务商 `DeepSeek`、基础地址 `https://api.deepseek.com`、模型 `deepseek-flash` 与自己的 Key。Key 不放在 React 构建参数、Compose 示例或源码中。

本项目对官方 DeepSeek 域名使用 `thinking: {type: "disabled"}`，保留 JSON 对象输出和已有 token/超时上限；其他兼容服务不带这一专有参数。当前官方模型目录提供对话模型，不能以该 Key/对话模型冒充已配置的 Embedding 或 Rerank。合同审查和 AI 文书起草可只用 LLM；知识索引与 RAG 问答还须单独配置支持向量接口的模型。

## 运行指标与审计缓冲

`GET /internal/v1/metrics` 返回 Prometheus 文本，必须携带有效 `X-Internal-Secret`。普通业务会话不能代替该凭据，错误或缺失凭据返回 401。响应设 `Cache-Control: no-store`；部署时保持内部网络范围，不将此端口作为公共指标入口。

输出只使用代码固定的类别、状态和结果标签：任务、outbox、文件清理、投递死信登记数量和最早等待年龄，JVM 堆使用，以及请求审计的提交、缓冲、重放、幂等命中、完整性失败、满额和持久化不可用计数。没有租户/用户/任务 ID、追踪 ID、材料、模型名、原始 URL、Cookie、密钥或错误堆栈标签。该汇总提供整个实例的运维状态，不能作为租户业务查询接口。

汇总 SQL 设置 3 秒查询超时和 32 行上限；查询失败仅返回 `lexflow_operational_database_available 0` 并省略无法确认的业务数量，不用零掩盖故障。`lexflow_audit_database_available` 表示最近一次独立请求审计或重放写入结果；进程尚无写入时为 0，因此判断数据库可用性应同时参考聚合查询指标。缓冲可用性和容量指标通过实际私有目录检查取得；完整性错误另有累计计数，不应只依赖容量判断记录能否重放。

审计配置从 Java 环境注入：

| 环境项 | 默认值 | 用途 |
| --- | --- | --- |
| `AUDIT_BUFFER_DIRECTORY` | `${user.dir}/.lexflow-audit-buffer` | 已存在父目录下的私有持久目录 |
| `AUDIT_BUFFER_MAX_RECORDS` | 10000 | 未确认事件及残留文件数量上限 |
| `AUDIT_BUFFER_MAX_BYTES` | 20971520 | 缓冲数据字节上限 |
| `AUDIT_REPLAY_BATCH_SIZE` | 100 | 一轮重放上限，允许 1–1000 |
| `AUDIT_REPLAY_DELAY_MS` | 15000 | 重放轮次间隔 |
| `SCHEDULING_ENABLED` | true | 为 false 时停用自动重放和其他后台业务调度 |

目录、`.key` 与待处理文件必须一起备份和迁移。不得通过删除缓冲文件处理满额，也不得在数据库未确认提交时删除单条记录。损坏文件需要运维确认原记录和恢复路径；程序只保留、报警和继续有限批次，不伪造内容。全局数据库持续不可用时应优先恢复数据库或扩容已经验证的私有持久磁盘。

`infra/monitoring/lexflow-alerts.example.yml` 是供已有 Prometheus 运维系统审阅的告警规则示例。阈值是可配置的起点，不是性能承诺；实际值应根据数据量、模型时延和运维值守确定。接入采集器时安全注入 `X-Internal-Secret`，不要把实际密钥写入仓库、命令行日志或告警标签。该仓库未部署监控系统、配置对外通知或触发实际通知。

数据库汇总反映已登记的工作状态；RabbitMQ 队列长度、MinIO/Milvus、Redis、MySQL 服务器磁盘和连接池等基础设施状态需由相应基础设施的已有 exporter/健康检查观测，不能把此接口的成功返回当成所有外部服务健康。完整模型质量、费用和性能验收另需选定供应商、预算及运行环境。
