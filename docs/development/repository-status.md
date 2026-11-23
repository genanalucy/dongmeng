# GitHub 仓库与版本状态

核对日期：2026-11-23。仓库：[genanalucy/dongmeng](https://github.com/genanalucy/dongmeng)，默认分支 `main`。本页是本轮清理开始时的快照，基线为 `f26d6902fc8586a89d5b6bb8f7e4bda90ab1b334`；后续提交、PR 和发布会改变这些数字。

## 先区分四种状态

| 状态 | 当前事实 |
|---|---|
| GitHub main | 日常共同基线；不能等同于正式版或最新 Debug 的源码 |
| 正式安装包 | 最新正式 [v21](https://github.com/genanalucy/dongmeng/releases/tag/android-production-yilu-20261113-v21)，包名 `com.verba.interpretation` |
| Debug 安装包 | 最新预发布 [v30](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30)，包名 `com.verba.interpretation.debug`；维护者已安装但尚未完成验收 |
| 运行中服务 | 需要单独核对部署身份、配置和实测结果，本轮未部署或重新核验线上运行版本 |

此前认可的 v29 也有待打磨的问题。APK 已发布不代表相应代码已合入 `main`，也不代表长时间翻译、100 人并发和完整真机流程已经通过。

## PR 与工作项

| PR | 清理前状态 | 与 main 的关系 | 本轮处理 |
|---|---|---|---|
| [#1 v30 候选](https://github.com/genanalucy/dongmeng/pull/1) | 开放草稿 | `test/android-phase1-baseline@dc587eff`，领先 35 提交 | 保留，功能验收单独进行 |
| [#2 综合整理](https://github.com/genanalucy/dongmeng/pull/2) | 开放 | `chore/repo-organization@23a7346b`，领先 71 提交，包含 #1 的全部 35 提交，再增加 36 提交 | 保留，不把它作为纯文档清理直接合入 |
| 本轮独立清理 | `chore/github-project-cleanup` | 从上述 main 新建，只处理工具文件、项目文档、归档与忽略规则 | 单独 PR 评审，不合入候选产品逻辑 |

盘点时 GitHub 没有开放 Issue。之前的本地工作项和软件审查资料不因 Issue 列表为空而消失；它们尚未随本轮清理发布为 GitHub Issue。

本轮影响是文档入口、历史资料位置、仓库内 AI 工具配置的移除及本地忽略规则。Android、Cloud、翻译服务、Web 的产品实现、现有测试、数据库迁移、构建与 CI 配置保持原样。

## 分支盘点

下表包含清理前 13 个远端分支，不含本轮新建分支。领先/落后表示相对上述 main 的提交图差异，不能单凭数字判断功能是否等价。只列清理候选，本轮没有删除远端或本地分支。

| 分支 | HEAD | 领先 / 落后 | 处理依据 |
|---|---|---|---|
| `android` | `0fc66d02` | 22 / 261 | 保留待语义评审：含尚未进入main/PR1/PR2的历史实现或实验提交；可达性不能证明功能已退出。 |
| `android-cloud-auth` | `35445f18` | 29 / 261 | 保留待语义评审：含尚未进入main/PR1/PR2的历史实现或实验提交；可达性不能证明功能已退出。 |
| `android-ui-redesign` | `a32f2159` | 12 / 25 | 可退役候选，需单独决定：远端提交已由baseline/20261104/legacy-remote-ui同SHA保存；但同名本地工作树HEAD不同，不能当作工作树已完成或删除其本地分支。 |
| `baseline/20261104/android-integration` | `8e625a67` | 0 / 13 | 保留来源快照：提交已进入main，但当前旧工作树仍以该SHA为HEAD；保留来源身份与恢复定位。 |
| `baseline/20261104/azure-experiments` | `aeec6aa3` | 16 / 25 | 保留待语义评审：含尚未进入main/PR1/PR2的历史实现或实验提交；可达性不能证明功能已退出。 |
| `baseline/20261104/legacy-remote-ui` | `a32f2159` | 12 / 25 | 保留来源快照：保存远端旧UI实验；与android-ui-redesign远端同SHA，该提交未进入main/PR1/PR2。 |
| `baseline/20261104/local-main` | `bec88598` | 0 / 227 | 保留来源快照：提交已进入main，但当前旧工作树仍以该SHA为HEAD；保留来源身份与恢复定位。 |
| `baseline/20261104/remote-main` | `3ca49501` | 0 / 244 | 可退役候选，需单独决定：已进入main/PR1/PR2且有11个同SHA发布标签，无同HEAD或同分支注册工作树；命名快照有来源定位用途。 |
| `chore/repo-organization` | `23a7346b` | 71 / 0 | 保留：开放PR来源与当前工作树；本轮不合入、关闭或改写PR。 |
| `internal-beta-integration` | `4e01129e` | 0 / 210 | 可退役候选，需单独决定：全部提交已进入main/PR1/PR2；无开放PR、同名工作树或同HEAD工作树。 |
| `main` | `f26d6902` | 0 / 0 | 保留：GitHub默认共同主线；本轮清理以此为基线。 |
| `release/yilu-production-v21` | `20da583d` | 1 / 16 | 保留：正式v21来源，有1个未进入main/PR1/PR2的提交；虽有同SHA发布标签，仍保留发布分支。 |
| `test/android-phase1-baseline` | `dc587eff` | 35 / 0 | 保留：开放PR来源与当前工作树；本轮不合入、关闭或改写PR。 |

其中 `internal-beta-integration` 已全部进入 main，无开放 PR 或注册工作树依赖，是最直接的退役候选。`baseline/20261104/remote-main` 仍有命名快照的来源价值；`android-ui-redesign` 的远端与同名本地工作树 HEAD 不同，不能把远端重复快照等同于本地工作已完成。

部分提交没有被任何其他当前远端分支覆盖：`android-cloud-auth` 7 个、Azure 快照 4 个、`chore/repo-organization` 36 个、正式 v21 分支 1 个。原工作区还存在未提交成果，本轮没有重置、拉取覆盖或删除它们。

## Release 登记

清理前共 24 个 Release：4 个正式、20 个预发布。全部保留标签和原 APK 附件；仅在此集中说明用途，不修改历史 Release 名称、标记、标签或下载内容。时间列为 GitHub 发布日期（UTC）。

| 发布 | 类型 | 标签 | 日期 |
|---|---|---|---|
| [译路随行 Debug v30 · 当前会话导出与可靠保存](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30) | 预发布 | `android-debug-yilu-20261117-v30` | 2026-11-16 |
| [译路随行 Debug v29 · 修复静音空白气泡](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261116-v29) | 预发布 | `android-debug-yilu-20261116-v29` | 2026-11-16 |
| [译路随行 Debug v28 · 修复松手与连续翻译](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261115-v28) | 预发布 | `android-debug-yilu-20261115-v28` | 2026-11-15 |
| [译路随行 测试版 v27](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261113-v27) | 预发布 | `android-debug-yilu-20261113-v27` | 2026-11-13 |
| [译路随行 正式版 v21](https://github.com/genanalucy/dongmeng/releases/tag/android-production-yilu-20261113-v21) | 正式发布 | `android-production-yilu-20261113-v21` | 2026-11-13 |
| [Android Debug v26 — Focused Translation Workbench](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-workbench-20261110-v26) | 预发布 | `android-debug-workbench-20261110-v26` | 2026-11-10 |
| [Android Debug v25 — Translation Controls and Guidance](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-guidance-20261110-v25) | 预发布 | `android-debug-guidance-20261110-v25` | 2026-11-10 |
| [Android Debug v24 — Manual Update Progress Fix](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-update-progress-20261105-v24) | 预发布 | `android-debug-update-progress-20261105-v24` | 2026-11-04 |
| [Android Debug v23 — Rounded Navigation Feedback](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-nav-corners-20261105-v23) | 预发布 | `android-debug-nav-corners-20261105-v23` | 2026-11-04 |
| [Android Debug v22 — Refined UI Trial](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-visual-trial-20261105-v22) | 预发布 | `android-debug-visual-trial-20261105-v22` | 2026-11-04 |
| [Android Debug Internal Beta 20260915.0225](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260915-0225) | 预发布 | `android-debug-internal-beta-20260915-0225` | 2026-09-20 |
| [Android Debug Internal Beta 20260920 Liquid Glass](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260920-liquid-glass) | 预发布 | `android-debug-internal-beta-20260920-liquid-glass` | 2026-09-20 |
| [言枢智能翻译 20260915 v20](https://github.com/genanalucy/dongmeng/releases/tag/android-production-20260915-v20) | 正式发布 | `android-production-20260915-v20` | 2026-09-14 |
| [Android Debug Internal Beta 20260914 Log Export](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260914-log-export) | 预发布 | `android-debug-internal-beta-20260914-log-export` | 2026-09-13 |
| [Android Debug Internal Beta 20260913 Diagnostic Log](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260913-diagnostic-log) | 预发布 | `android-debug-internal-beta-20260913-diagnostic-log` | 2026-09-13 |
| [Android Debug Internal Beta 20260913 Auto Mode UI](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260913-auto-mode-ui) | 预发布 | `android-debug-internal-beta-20260913-auto-mode-ui` | 2026-09-13 |
| [Android Debug Internal Beta 20260913 Auto JSON](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260913-auto-json) | 预发布 | `android-debug-internal-beta-20260913-auto-json` | 2026-09-13 |
| [言枢智能翻译 20260913.2030](https://github.com/genanalucy/dongmeng/releases/tag/android-production-20260913-72e8f75) | 正式发布 | `android-production-20260913-72e8f75` | 2026-09-13 |
| [Android Debug Internal Beta 20260912 Stability](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260912-stability) | 预发布 | `android-debug-internal-beta-20260912-stability` | 2026-09-12 |
| [Android Debug Internal Beta 20260912 Auto Entry](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260912-auto-entry) | 预发布 | `android-debug-internal-beta-20260912-auto-entry` | 2026-09-12 |
| [Android Debug Internal Beta 20260912 Continuous Auto](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260912-continuous-auto) | 预发布 | `android-debug-internal-beta-20260912-continuous-auto` | 2026-09-12 |
| [Android Debug Internal Beta 20260911.0502](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260911-azure) | 预发布 | `android-debug-internal-beta-20260911-azure` | 2026-09-11 |
| [Android Debug Internal Beta 20260911.0154](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260910-3b6fb7c) | 预发布 | `android-debug-internal-beta-20260910-3b6fb7c` | 2026-09-10 |
| [言枢智能翻译 20260910.0312](https://github.com/genanalucy/dongmeng/releases/tag/android-production-20260910-3002067) | 正式发布 | `android-production-20260910-3002067` | 2026-09-09 |

历史 `android-debug-internal-beta-20260915-0225` 的标签和 APK 源码不一致，具体来源及哈希已保存在[旧基线](../../archive/documents/2026-11-04-development-baseline.md)。本轮没有移动标签或替换附件。

## 与 PR #2 的衔接

PR #2 基于旧 main，仍有本轮决定退出仓库的工具文件和工具依赖。未来收口该 PR 时，下列差异需要一起处理：

| 项目 | PR #2 中的位置与影响 |
|---|---|
| AI 规则与历史副本 | `AGENTS.md`、`archive/documents/prototype-and-stages/agent.md` 及工具目录不能重新进入最终树 |
| 技能安装工具 | `scripts/setup-agent-skills.py`、对应测试、README 中的 Pi/技能安装入口，是本轮退出的工具材料 |
| 文档检查 | `scripts/check-navigation.py` 要求存在 `AGENTS.md`，测试 fixture 也包含它；后续应对齐现行项目文档入口 |
| 综合检查与 CI | `scripts/check.sh` 中的技能安装器检查、CI 路径分类需要随工具移除一起调整，保留项目本身的构建与验证 |
| 文档归档 | PR #2 有更大范围的归档和独有资料，需按来源逐项对照，保留项目知识并避免重复入口 |

本轮未修改或关闭 PR #1/#2，也不认为 Git 自动合并成功就代表上述衔接完成。

## 盘点依据

分支关系来自 GitHub 分支/PR/Release API 与本地 `git rev-list`、`git merge-base --is-ancestor`、`git worktree list`，不是仅按分支名称分类。可在仓库中重新读取：

```bash
gh pr list --state open
gh issue list --state open
gh release list --limit 100
git ls-remote --heads origin
git worktree list
git rev-list --left-right --count origin/main...origin/<branch>
```

[盘点快照](repository-inventory.json)保留当时的分支 SHA、提交图计数与 Release 列表。这里只做仓库和引用核对，没有重测 APK 安装、实际下载内容、线上容量或供应商配额。

## 本轮清理检查

- 对照基线逐字节核对 419 个产品源码、资源、现有测试、构建和 CI 文件，内容均保持一致（Markdown 项目说明单独整理）。
- 检查 72 份 Markdown 中的 180 个本地链接目标，无断链；这是路径存在性检查，不是公网可达性验证。
- 49 份迁移资料均有新位置及来源索引；测试目录中迁移的 Markdown/PDF 内容与原始哈希一致。
- 129 个 AI 专用文件或链接已从本轮最终树移除；构建脚本和 CI 未发现对这些工具文件的运行依赖。
- `git diff --check` 通过。文档清理未在本地重跑产品测试、真机、数据库、供应商或压力测试；既有审查和测试结论没有被本轮重新认证。
