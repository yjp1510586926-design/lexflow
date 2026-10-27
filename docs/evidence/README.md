# 验收数据索引

当前项目状态见 [2026-10-09 需求完成度复核](../需求完成度复核-2026-10-09.md)。[verification-data.tar.gz](verification-data.tar.gz) 集中保留历史验收材料，供按具体问题查阅。

归档共 393 个文件：363 个原始证据文件（含 99 份日志）、29 份历史正文及附件、1 份原 V23 安装报告。文件按原 `docs/` 路径保存，正文与历史 JSON 均保留原始字节；历史路径和摘要继续描述记录时的对象。部分相对引用目标已清理，历史验收范围不能扩称为当前全部源码或环境重新通过。

从项目根目录查看清单，或解压到独立临时目录后按原路径阅读：

```sh
tar -tzf docs/evidence/verification-data.tar.gz
review_dir="$(mktemp -d /tmp/lexflow-verification.XXXXXX)"
tar -xzf docs/evidence/verification-data.tar.gz -C "$review_dir"
```

| 验收内容 | 包内主要记录 |
| --- | --- |
| 新基础设施核心业务与清理 | `docs/evidence/fresh-mvp-checks.json`、`docs/evidence/fresh-mvp-cleanup.json` |
| 独立整栈部署 | `docs/evidence/mvp-deployment-checks.json` |
| DeepSeek 真实业务、重启与浏览器 | `docs/evidence/deepseek-mvp-real-checks.json`、`docs/evidence/deepseek-mvp-browser-checks.json` |
| 原空数据安装 | `docs/evidence/fresh-install-checks.json` |
| 成员权限回归 | `docs/evidence/mvp-member-regression-checks.json` |
| 三类工作流投递恢复 | `docs/evidence/workflow-delivery-real-checks.json`、`docs/evidence/workflow-delivery-node-checks.json` |
| 界面权限与任务时间 | `docs/evidence/ui-completion-checks.json` |

原 V23 安装报告存于 `docs/fresh-install-v23-verification.md`，为清理前完整安装正文的前 4090 字节，SHA-256 为 `53f3434c0fc00325b0e965c308756d8c89b76c027547f7880e32112f4c83df2e`，精确匹配 `fresh-install-checks.json.installationReport`。完整 V28 追加报告仍存于包内 `docs/fresh-install-verification.md`，匹配 `fresh-mvp-checks.json.documentSha256`。

旧 `项目完成度检查.md`、`需求实现核查-2026-10-01.md` 的记录摘要在清理前已不匹配正文。包内最后保留的正文不能重构这些更早版本；其他可变文档也不等同于记录时的快照。历史 JSON 未改写。

已逐项验证全部 393 个成员的路径、原始字节和 SHA-256。归档 SHA-256：`86bf6ef0f4010225360adf3e689198d998bbcc1461311cc2ea7d604bd8d242d2`。

## 文档引用的归档成员

下列文件均在同一归档包内，解压后可按所列原路径查阅。

### audit-operations-checks.json

包内路径：`docs/evidence/audit-operations-checks.json`。

### case-analysis-checks.json

包内路径：`docs/evidence/case-analysis-checks.json`。

### contract-consumer-recovery-checks.json

包内路径：`docs/evidence/contract-consumer-recovery-checks.json`。

### contract-real-checks.json

包内路径：`docs/evidence/contract-real-checks.json`。

### contract-supplement-checks.json

包内路径：`docs/evidence/contract-supplement-checks.json`。

### deepseek-mvp-browser-checks.json

包内路径：`docs/evidence/deepseek-mvp-browser-checks.json`。

### deepseek-mvp-real-checks.json

包内路径：`docs/evidence/deepseek-mvp-real-checks.json`。

### document-concurrency-checks.json

包内路径：`docs/evidence/document-concurrency-checks.json`。

### fresh-install-checks.json

包内路径：`docs/evidence/fresh-install-checks.json`。

### fresh-mvp-checks.json

包内路径：`docs/evidence/fresh-mvp-checks.json`。

### fresh-mvp-cleanup.json

包内路径：`docs/evidence/fresh-mvp-cleanup.json`。

### knowledge-vector-retirement-checks.json

包内路径：`docs/evidence/knowledge-vector-retirement-checks.json`。

### manual-recovery-checks.json

包内路径：`docs/evidence/manual-recovery-checks.json`。

### material-evaluation-checks.json

包内路径：`docs/evidence/material-evaluation-checks.json`。

### mvp-deployment-at-initial-verification.md

包内路径：`docs/evidence/mvp-deployment-at-initial-verification.md`。

### mvp-deployment-checks.json

包内路径：`docs/evidence/mvp-deployment-checks.json`。

### mvp-member-regression-checks.json

包内路径：`docs/evidence/mvp-member-regression-checks.json`。

### object-lifecycle-checks.json

包内路径：`docs/evidence/object-lifecycle-checks.json`。

### ui-completion-checks.json

包内路径：`docs/evidence/ui-completion-checks.json`。

### workflow-delivery-node-checks.json

包内路径：`docs/evidence/workflow-delivery-node-checks.json`。

### workflow-delivery-real-checks.json

包内路径：`docs/evidence/workflow-delivery-real-checks.json`。
