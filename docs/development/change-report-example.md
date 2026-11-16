# 中文交付说明：隔离 v30 整理入口

这是一份已完成改动的交付示例，沿用 [PR 模板](../../.github/pull_request_template.md) 的三个栏目。对象是任务 02 的文档提交 `f1796655c46648198d1a7d7e57557f4c5b022334`，不是整个整理 PR 的交付结论。任务 01 的前置提交 `bc389e03b1bcd17fdeeaf5d495950ec3f71f14ca` 提供 [源码来源与保全清单](source-inventory.md)。两者是本地整理提交，远端可见性随后续推送更新；可在包含这些提交的 checkout 审阅：

```bash
git show --stat bc389e03b1bcd17fdeeaf5d495950ec3f71f14ca
git show f1796655c46648198d1a7d7e57557f4c5b022334 -- docs/development/baselines.md docs/development/workspace.md
```

## Summary

目的：让用户找到独立的 v30 整理入口，避免把旧工作树、历史基线或已发布版本当成已验收的最新源码。

```diff
 docs/development/
+  source-inventory.md  # 任务 01：来源、保全与恢复限制
~  baselines.md         # 任务 02：现行入口与 11 月 4 日历史效力分开
+  workspace.md         # 任务 02：工作树、候选来源、环境与恢复入口
```

- 实际变化：任务 01 新增来源清单；任务 02 新增 [工作树与环境说明](workspace.md)，修改 [基线说明](baselines.md)，指向 `repo-organization / chore/repo-organization`。该工作树已存在，任务 02 没有重新创建。没有界面、产品行为或缺陷修复变化。
- 影响范围：用户和代理选择工作目录、源码版本、依赖准备及验证入口。任务 02 另在候选和任务工作树新增被忽略的本机 SDK 定位文件，只影响这两个工作树的 Android 环境，不进入公开提交。
- 旧实现处理：11 月 4 日基线正文保留作历史证据，仅把历史绝对目录改为工作树名称并标明历史效力；旧开发工作树、分支、源码和环境均保留。旧根未提交 Agent / Qwen 成果独立保全，没有导入候选。本批没有删除或替换产品实现，也没有声称旧代码残留已经清理。
- 审阅范围：任务 01 提交仅新增一份公开清单；任务 02 提交仅改两份入口文档。整理 PR 还继承 v30 及此前多组件候选，整体范围见 [工作树与 PR 来源关系](workspace.md)，不能用这两份文档提交代表整个 PR。

## Evidence

- **Before：** 任务 02 父提交中的基线文档直接进入 11 月 4 日记录，并列出本机绝对目录；没有 `workspace.md`。
  **After：** 任务 02 差异新增当前候选、历史效力说明和工作树指南；v30 明确为用户已更新、尚未测试。上述 `git show` 可查看逐行差异。
- 本例编写时复核（2026-11-17，在任务 04 工作树）：`git show --format=fuller --stat <上述提交>` 核对提交身份和文件范围；`git diff --check f179665^ f179665` 通过；`git diff --exit-code f179665^ f179665 -- agent android cloud-api web migrations scripts Makefile docker-compose.yml` 退出 0，确认该提交未改这些产品与构建入口。这里只复核 Git 证据。
- 任务 02 当时的执行记录：在整理候选分别运行三个 Go 模块的 `go list ./...`，均成功；Android `./gradlew --no-daemon --offline help` 成功，仅有既有 `jvmTarget` 弃用警告。这是包解析与配置探测，不是测试或编译；本例没有重跑。执行边界和结果见 [当批环境记录](workspace.md)。任务 01 的本地恢复与哈希核验边界见 [来源清单](source-inventory.md)；原始本机清单留在本地工作项，不公开私有快照。
- 未运行 / 未覆盖：本例未运行产品构建、Android 单测或 lint、Go 测试、前端检查、数据库集成、APK 下载验证或设备体验；文档改动未触及产品行为。既往 Release 的自动检查不是本例实测。
- 人工验收：文档入口仍待用户确认可读性；可按工作树说明定位候选，核对分支并找到版本、环境和恢复入口。v30 的真实文件提供方、长期语音和跨设备同步仍待产品验收，本例没有这些实际结果。

## Merge Danger

**Door：** two-way。文档变更可以按提交差异回退，旧资料与原环境仍保留；本机 SDK 定位文件被忽略，不随文档回退自动删除。源码保全的覆盖与恢复限制仍以来源清单为准。

**Blast Radius：** 开发入口。错误文档可能让人选错工作树或误解验收状态；本批未改应用、服务、协议或下载清单。

- 合入状态：截至本例编写，任务 01 / 02 已分别经 `b11d2c9` / `8d06231` 整合到本地 `chore/repo-organization`；这不等于已合入 GitHub `main` 或完成用户验收。
- 发布状态：这两项任务没有部署服务或发布 APK；v30 的既有 Release 是候选来源，不能据此宣称本轮整理已发布或稳定。
