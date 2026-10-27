# NestJS AI Service

独立进程，消费 Java 经 RabbitMQ 发布的 `DOCUMENT_PARSE`、`KNOWLEDGE_INGEST`、`CONTRACT_REVIEW` 与 `DOCUMENT_GENERATE` 任务。解析器从 Java 内部接口下载已授权文件，提取 TXT、DOCX 或含文字层 PDF 的原文。知识索引消费者在 Java 再次确认权限与来源后，使用律所管理员配置的 Embedding Key 调用模型，并将向量写入 Milvus；只有 Java 接受当前任务的成功回调后，文档才显示“可检索”。内部检索接口按 Java 提供的授权文档范围查询 Milvus，Java 再次核对来源权限后返回原文出处。内部 `/internal/v1/ai/answer` 使用 LangChain ChatOpenAI，根据 Java 授权的原文生成 JSON 回答，拒绝未知引用；Java 再次检查来源并保存问答。合同审查由 LangGraph 提取条款、检索明确选择的案件或律所公共资料、分析风险，高风险增加深入分析；缺依据、缺材料或高风险暂停人工复核。Java 保存报告及复核决定，恢复时重新检查权限、来源和模型身份。

使用 Node.js 24：`npm ci`、`npm run dev`。生产式本地验证：`npm run build && npm start`。
解析依赖：[pdf-parse](https://github.com/mehmet-kozan/pdf-parse) 提供 PDF 逐页文字，[Mammoth](https://github.com/mwilliamson/mammoth.js) 提供 DOCX 原文。单文件上限 50 MB，PDF 最多 500 页，提取正文最多 100 万字符；当前不执行 OCR。`npm test` 使用合成 PDF/DOCX，不含真实业务材料。

本服务承接 LangGraph.js，Java 保持业务数据所有权。不得把模型密钥放入 React。Embedding、LLM 和 Rerank 组件只接收用户手动配置的 API Key，并要求部署环境设置 `LEXFLOW_AI_ALLOWED_HOSTS` 精确域名白名单；留空时拒绝请求。当前 LLM 接口需兼容 OpenAI Chat Completions 的 JSON 对象响应；问答/合同每次最多 1800 个输出 token，文书生成最多 6000 个，60 秒超时且无 SDK 自动重试。不执行工具调用；来源与历史问题作为不可信数据，历史问题仅用于理解代词，事实依据必须来自本次原文。没有原文或模型判断依据不足时返回明确拒答。供应商错误由内部接口转为通用提示，不将原始错误写日志。

内部 `/internal/v1/knowledge/rerank` 接收 Java 再次授权的最多 50 段原文，调用 Cohere v2 兼容的 `基础地址/rerank`。请求为 model/query/documents/top_n，结果读取 results 的 index/relevance_score，参见 [Cohere 官方 API](https://docs.cohere.com/v2/reference/rerank)。适配器要求返回恰好 top_n 个唯一、未越界编号和 0–1 有限分数，忽略供应商返回的原文；30 秒超时、禁止重定向、响应上限 100 KB、没有自动重试。内部返回编号与分数，Java 映射原文并在返回前再次检查来源。配置保存不调用排序服务。

内部 JSON 请求上限为 256 KB，覆盖 50 段中文候选及模型配置；超限仍返回 413。各接口还分别限制片段数量、字符数和参数类型。实际 HTTP 测试确认大于默认 100 KB 的合法中文批次能到达控制器，而超出 256 KB 的请求被拒绝。

`npm test` 不会调用付费模型；要运行真实 Milvus 隔离测试，先启动 `vector` profile，再使用 `LEXFLOW_VECTOR_INTEGRATION=true node --test test/milvus-knowledge.test.cjs`。ChatOpenAI 的 JSON 对象响应参数见 [LangChain 官方参考](https://reference.langchain.com/javascript/langchain-openai/ChatOpenAI)；JSON 语法不能保证引用真实，本项目还独立校验正文标记和引用集合。

索引重建由 Java 分配独立且递增的版本与切片编号，NestJS 使用下发的版本写向量，不覆盖已发布版本。Java 成功接受回调才切换发布指针；失败或取消保留原索引。取消只撤销发布权限，不能保证停止已发送的模型请求。真实 upsert 前登记精确向量位置；Java 定时退役失去发布资格的版本，通过内部回收接口精确删除并持续复查墓碑，覆盖迟到写入。方案、历史位置缺失限制与真实 MySQL/Milvus 证据见 [向量回收说明](../docs/business-rules.md#知识向量退役)。

固定材料评估默认只生成无网络计划：`node evaluation/run-real-materials.cjs --config evaluation/real-evaluation.example.json --output /tmp/lexflow-evaluation-plan.json`（输出须不存在）。固定 12 场景的真实模型执行须另行审阅供应商、模型、调用和金额上限，历史核心 DeepSeek 验收的费用授权不扩展为该评估授权。准备、命令和证据边界见 [真实评估方案](../docs/java-code-structure.md#固定材料评估)。

## 合同审查与工作流持久化

合同消费者、Java 专用接口、任务状态与前端复核入口已接入 MySQL checkpoint 和外部操作记录。案件文件可提交审查立场、关注点及可选本案件或本律所公共知识库；任务中心可查看报告、提交复核意见、确认或取消。报告精确引用合同原文，知识引用只接受当前授权切片。公共参考、补材续审、依据冲突与文件/页段定位已接入，并在每次模型输入和报告发布/读取时重新授权。现行规则见 [合同审查](../docs/business-rules.md#合同审查与补充材料)，专项模型与实际链路边界见 [最新验收结论](../docs/需求完成度复核-2026-10-09.md)。

AI 服务使用同一 MySQL 容器中的独立 `lexflow_ai` 数据库和账号，不读取 Java 业务表。第一次从项目根目录执行：

```sh
sh infra/mysql/init-ai-workflow.sh
cd ai-service
export AI_WORKFLOW_DATABASE_URL='mysql://lexflow_ai:lexflow_ai_dev_only@127.0.0.1:13306/lexflow_ai'
npm run setup:workflow
LEXFLOW_WORKFLOW_INTEGRATION=true npm test
```

独立安装或隔离验收可在初始化前设置 `AI_WORKFLOW_MYSQL_DATABASE` 和 `AI_WORKFLOW_MYSQL_USER`，默认为 `lexflow_ai`。名称只允许小写字母、数字、下划线，必须为 `lexflow_ai` 或 `lexflow_ai_` 加非空后缀；库名最多64字符、账号最多32字符。随后将 `AI_WORKFLOW_DATABASE_URL` 指向同一库和独立账号。初始化只授予该库权限，不重置已有账号密码；AI 服务仍拒绝 Java 默认账号和 root。不要把隔离实例指向已有业务库。

以上密码仅为本地示例。自定义时向初始化脚本设置 `AI_WORKFLOW_MYSQL_PASSWORD`，并使用同一密码组成连接 URL；已有账号的密码不会被脚本重置。普通 NestJS 启动不创建数据库或表，也不会自动读取项目根目录的 `.env`。连接配置只接受独立 AI 数据库/账号，拒绝 root 或 Java 业务账号。

`TenantMySqlCheckpointer` 将每次操作绑定到服务器生成的 `lf:租户编号:任务编号`。每次图执行和恢复必须包在 `withThreadLock` 中，并使用同步持久化；执行锁由 MySQL 管理，连接断开后旧执行者的写入会被拒绝。快照保存显式状态、元数据和待写入，不保存 RunnableConfig。模型 Key 必须留在运行时闭包中；存储器会拒绝已知凭据字段，但这不能识别任意字符串中的密钥，调用方仍须保证状态不含凭据和原始供应商错误。

模型调用等外部动作必须使用 `runOnce(config, operationKey, inputHash, callback)`：操作标识按节点/协议版本固定，输入摘要包含材料版本、审查输入和模型身份，排除 Key。开始请求前提交 `STARTED`；结果先保存为 `SUCCEEDED`，之后图节点才保存状态。结果已保存而图尚未保存时，恢复直接复用结果。失败不自动重试；进程在请求发出后崩溃留下的 `STARTED` 视为结果不确定，需要人工确认后决定是否发起新操作。此机制不能承诺远程供应商恰好执行一次。

真实本地 MySQL 与独立 Node 进程测试覆盖暂停/恢复、跨租户拒绝、并发互斥、进程终止、连接丢失后的迟到写入、结果复用及不确定请求不重复调用；外部请求由合成回调模拟，不访问真实供应商。真实 HTTP/RabbitMQ 合同链路已验证 Java WAITING_HUMAN 状态、NestJS 重启后恢复、decisionId/stateVersion 幂等确认及完成报告；浏览器提交、人工意见与确认、任务中心查看也已验收。接口测试覆盖撤权、删除来源、取消、迟到结果和不确定结果拒绝确认/恢复。全部模型结果来自本机测试服务，不代表真实法律分析质量。

审查正文最多 4 万 UTF-16 字符，模型提取最多 20 条条款，引用原文须逐字匹配。常规两次模型调用，高风险最多三次；每次使用现有 1800 输出 token、60 秒、无自动重试限制。人工中断节点不调用模型，重复恢复复用已保存结果。Key 仅在运行时闭包中，消息、审计、图状态不携带 Key。缺少工作流数据库配置时合同任务无法执行；初始化完成后可从任务页主动恢复。结果不确定的任务禁止确认/恢复，只能取消后自行决定是否另建任务。取消阻止结果发布，无法保证停止已发出的远程模型请求。

## 主动连接测试

`POST /internal/v1/ai/configuration-test` 仅供 Java 通过内部凭据调用，参数绑定租户、服务器生成的测试编号、用途和当前配置版本。先验证 HTTPS 地址与精确域名白名单，再访问独立工作流库和模型。Java 在短事务中提交 PROCESSING 标记后才发出 HTTP；模型请求期间不持有业务数据库事务。

LLM 发送通用合成文本，最多 32 输出 token，要求返回 `{"ok":true}` JSON；Embedding 发送一段合成文本，检查有限、非零且 2–4096 维的向量；Rerank 对两段通用文档排序。三类均限制 15 秒、禁止重定向、没有自动重试。只返回状态、耗时和可选向量维度，不返回模型正文、Key 或供应商错误。

连接测试同样要求先完成上面的独立数据库初始化并设置 `AI_WORKFLOW_DATABASE_URL`。使用 `lft:租户编号:测试编号` 线程，与合同的 `lf` 线程隔离；`runOnce` 记录调用意图并缓存成功、失败或不确定结果。遗留 STARTED 不会重新请求模型。Key 只在运行时闭包中，输入摘要使用配置版本而非 Key。Java 对同一请求标识只领取一次；配置替换、删除和权限变化后重新检查，旧成功结果不能沿用。

前端要求用户逐次确认可能产生费用，保存、刷新和读取测试结果均不调用模型。每租户每用途同时至多一项、每分钟新建至多三项。超时或丢响应不能证明供应商未执行；用户应先读取结果，再自行决定是否发起新的付费请求。测试只验证接口兼容性，不验证实际法律质量。


## AI 文书生成

`DOCUMENT_GENERATE` 消费 `lexflow.q.document.generate`，通过专用内部接口领取不可变源版本、案件背景和生成要求。LangGraph + 独立 AI MySQL 保存执行进度及模型操作结果，返回正文和待补信息；Java 再次授权后保存独立待复核版本，不覆盖当前人工正文。用户在页面主动确认费用，读取和恢复不自动重发不确定的模型调用。接口、权限、取消、复核与实际验收见 [文书生成说明](../docs/business-rules.md#ai-文书生成)。

索引恢复资格使用内部 `POST /internal/v1/index-recovery/eligibility`，由 Java 发送 `{taskId,input}` 并提供相同 `X-Internal-Secret`；input 为索引输入去除 apiKey，最多 10000 段、每段 1000 字，专用路径 JSON 上限 16 MB。探针取得当前任务同名数据库执行锁（不等待），只读取已有 Embedding 批次的状态与摘要，返回 `{status}`：`SAFE`、`BUSY`、`UNCERTAIN`、`FAILED`、`INPUT_CHANGED` 或 `UNAVAILABLE`。它不初始化或改写工作流记录、不调用模型或向量服务、不返回材料及向量；人工恢复仍由 Java 重新校验业务状态、版本、权限和费用确认。同一 task 的线程及批次身份不随业务 attempt 改变。

默认连接解析、合同、知识索引和文书生成四个队列。`AI_TASK_QUEUES` 可指定逗号分隔的短名称：`document.parse,contract.review,knowledge.ingest,document.generate`；仅接通列出的消费者，空值、未知名称或重复项导致启动失败。独立验收可选 `document.generate`，避免消费其他开发任务。声明队列及 DLQ 仍使用现有 transport 参数，不改变公共消息协议。

资料问答先召回五段；空结果或有效资料不足时，由 Java 在原授权知识库内扩大一次，内部检索支持至多 50 个候选，排序后至多十段供问答。错误、超时和无效引用不自动重试，同一证据不重复推理；仍不足明确拒答。调用预算、隔离与本机验收见 [扩大检索](../docs/business-rules.md#资料问答与检索)。

公共知识来源的解析/索引消费者及合同图沿用原协议，Java 根据明确 TENANT 范围授权；公共问答仍为用户私有会话。真实本机上传、HTTP/MQ/Milvus、问答/下载和公共合同报告已验收，模型均为本机合成。见 [公共资料验收](../docs/business-rules.md#律所公共知识库)。

## 任务投递恢复

解析/索引的瞬时故障最多经 10/60/300 秒 TTL 队列重试三次，再进入 DLQ。知识索引新增逐批 Embedding 结果持久化，向量写入或成功回调失败后复用已保存批次；不确定的模型操作不自动重发。**知识索引现在也必须配置并初始化独立 AI 工作流数据库**，Java 内部输入必须包含配置 revision，请一起升级两个服务。五类任务均已接入死信登记和任务中心人工处理；原协议记录、不确定或明确失败的模型操作仍不得重放。具体边界与实际 SQL/RabbitMQ 验证见 [投递恢复](../docs/business-rules.md#任务投递与恢复)。
