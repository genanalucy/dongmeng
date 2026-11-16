# 文档导航

效力：2026-11-17 的当前候选导航，依据 v30 源码 `cd920a1`、发布资料 `dc587ef` 及后续整理提交。它说明到哪里找事实，不把目标、旧报告或线上记录当作本轮验收。

## 当前说明

| 想知道什么 | 阅读入口 | 使用边界 |
| --- | --- | --- |
| 产品、版本与用户日常入口 | [项目首页](../README.md)、[工作树与环境](development/workspace.md) | v30 用户已更新但尚未测试；临时任务工作树不是日常入口 |
| 模块职责、依赖和改动影响 | [模块与依赖地图](architecture/README.md) | 源码关系；不证明线上开关、部署或真实端到端可用 |
| 运行检查、读取结果与人工验收 | [统一入口与选择矩阵](testing/README.md)、[CI 触发与覆盖](testing/ci.md)、[本轮实测](testing/repo-organization-20261117.md)、[人工清单](testing/manual-acceptance.md) | 08 的 DB 跳过与 09 的真实隔离 PostgreSQL 结果分开；GitHub 运行以对应提交为准 |
| 代理工具安装与检查 | [Pi 工具](../.pi/README.md) | 固定公开上游，37 个技能；忽略缓存不作为产品源码 |
| 剩余问题与后续打磨 | [独立后续条目](development/follow-ups.md) | UI/音频耦合、跨模块接口和文档残留独立跟进；不是本轮已实施功能 |
| 领域概念与既有决策 | [单一领域上下文](../CONTEXT.md)、[云端历史加密与管理员访问 ADR](adr/0001-cloud-history-encryption-and-admin-access.md) | 沿用翻译会话、已完成 Turn、本机历史副本、云端历史副本和删除优先等既有词汇 |
| 源码版本、旧工作树与保全 | [来源清单](development/source-inventory.md)、[基线记录](development/baselines.md) | 清单是时间点记录；基线文档保留的 11 月 4 日正文为历史证据 |
| Cloud API 与数据库迁移 | [接口说明](../cloud-api/API.md)、[迁移与隔离数据库检查](../cloud-api/README.md) | 具体路由以[当前路由源码](../cloud-api/internal/http/router.go)为核对入口；不证明生产配置 |
| Agent 翻译会话授权 | [授权信任约定](../agent/internal/sessionauth/README.md) | 提供方 Cloud、消费方 Agent 和 Android 必须一起考虑 |
| 管理后台与 Web | [管理后台说明](../cloud-api/admin-web/README.md)、[Web 辅助开发](development/web-prototype.md) | 管理端 README 部分接口状态是旧快照；Web 旧固定语言等限制不作为 Android 约束 |
| 下载与更新 | [运维效力与核验](operations/README.md)、[双通道下载发布](deployment/download-center.md)、[下载清单](../site/download/releases.json) | 发布文档中的各次部署与检查按日期作为历史记录，当前公网需另查 |
| 用户测试与反馈 | [测试材料与源版本](../translation-test-kit/README.md)、[质量检查表](../translation-test-kit/translation-quality-checklist.md)、[反馈表](../translation-test-kit/software-usage-feedback-form.md) | 人工使用材料；PDF 与 Markdown 的一致性尚未重新核实 |
| 代理与工作项约定 | [AGENTS](../AGENTS.md)、[本地工作项](agents/issue-tracker.md)、[领域文档约定](agents/domain.md) | 工作项在本地 `.scratch/`，不要求公开仓库包含本机执行证据 |
| 如何读懂一次交付 | [中文改动说明示例](development/change-report-example.md)、[PR 模板](../.github/pull_request_template.md) | 按实际范围说明变化、影响、旧内容处理、实测与未验证部分 |

[Android 说明](../android/README.md)包含设备与音频检查场景，但“本环境没有 SDK”、固定服务地址和部分账户流程是旧状态；当前环境准备以[工作树与环境](development/workspace.md)为准，行为以候选源码及实际验收为准。[管理端接口映射](../cloud-api/admin-web/README.md#当前接口映射)仍有旧的 501/未接入描述，而当前路由已提供部分对应接口；这批保留原文供后续核实，不据此断言当前功能缺失。

## 如何判断一份资料是否生效

- **现行说明**：明确适用源码或候选，核对实现后用于导航与操作；部署开关、线上可用性和设备体验需要单独证据。
- **待实现目标**：设计、spec、plan 中未核实完成的内容仅表示目标；日期新、文件名含 acceptance 或任务勾选不构成产品已验收的证据。
- **历史证据**：阶段报告、旧基线、旧发布记录保留当时的版本和未完成项，用来追溯，不覆盖当前说明。

旧原型约束、阶段设计/计划、交接与相关评审已成组进入[任务 05 归档索引](../archive/indexes/05-prototype-documents.md)，逐文件列出来源、效力、哈希与未完成验收。旧手机号/邮箱验证码要求与当前滑块注册存在已记录的阶段差异，不继承为当前要求。其他历史运维与测试材料继续按[可见归档](../archive/README.md)中的批次索引定位；不能把历史报告当成当前产品已验收。

遇到冲突，先记录冲突文档、适用提交和源码证据，再明确说明效力；不要仅凭日期删除资料。原始环境记录不等于当前环境事实，发布者既往测试不等于本轮实测，自动检查不代替用户验收。

## 两条阅读路径

查“当前翻译保存与导出”：项目首页 → 模块与依赖地图 → 用户功能归属 → Android 历史与导出源码；结合 v30 Release 的范围，再按模块检查及设备验收确认行为。

查“旧方案为什么没有继续使用”：项目首页 → 可见归档 → 批次索引目录 → 对应条目的原路径、来源和替代入口；本批旧原型和阶段资料可直接进入[任务 05 索引](../archive/indexes/05-prototype-documents.md)，按原路径与适用版本阅读；其他批次进度以实际索引为准。
