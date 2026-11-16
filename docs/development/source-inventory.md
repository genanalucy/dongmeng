# 源码来源与本地成果保全

效力：2026-11-17 整理任务的来源记录；不是产品稳定性或真机验收结论。采样为 2026-11-16 20:25:53–20:25:54 UTC（北京时间 11 月 17 日 04:25）。后续迁移前必须重新核对状态。

## 版本身份

| 对象 | 来源 | 状态与用途 |
| --- | --- | --- |
| Debug v30 | `android-debug-yilu-20261117-v30` → `cd920a1b96b35c52d77b8e415a8219cc30cf707c`；`20261117.0343` | 用户已更新，尚未完成用户验收；保存与导出成果作为整理候选保留 |
| v30 后续发布资料 | `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` | 比 v30 源码多一个发布说明、下载清单提交；本轮隔离整理的起点 |
| Debug v29 | `51a76704bbb8c853525fa903c840223160b09d87`；`20261116.2307` | 用户此前认可的比较参考，仍有待打磨问题 |
| 正式 v21 | `release/yilu-production-v21` → `20da583d39eb39d47e2ad104143f4ab185317b1a` | 独立正式发布来源，不随目录整理重新发布 |
| 共同主线的本地缓存 | `origin/main` → `f26d6902fc8586a89d5b6bb8f7e4bda90ab1b334` | 比整理起点少 35 个提交；本轮未 fetch，不能代指实时远端状态 |
| 旧根工作树 | `main` → `bec885983928973849757f5d31df38b903b116a8` | 旧来源，另有未提交 Agent 成果与独有资料；不得直接 pull 或覆盖 |

v30 标签身份沿用已核实的 [Release](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30) 记录；本次本地没有对应标签引用，但能读取源码提交对象。未重新下载 APK，也未读取手机安装信息。Release 中的既往测试结果不算本轮实测。

## 工作树与重要分支

表中工作树使用名称定位，不要求其他机器复用本机绝对目录。差异数为相对 `dc587ef` 的“基线独有 / 分支独有”提交数，只表示 Git 可达性。cherry-pick、等价实现和后续替代均需另外核实，不能据此认定功能缺失或再次合并。

| 工作树名称 | 分支 / HEAD | 差异数 | 用途及保留条件 |
| --- | --- | --- | --- |
| root | `main` / `bec8859` | 262 / 0 | 旧根目录；4 个 tracked 修改、15 份未跟踪源码另存；其他资料按清单保留 |
| main-baseline | `test/android-phase1-baseline` / `dc587ef` | 0 / 0 | v30 后续发布资料及原开发入口；本地研究与工作项已补充保全 |
| integration | `android-ui-redesign` / `8e625a6` | 48 / 0 | 原 UI 集成对照；34 份本地工作项保全后仍保留原处 |
| account | `ui-account-slice` / `c08e217` | 133 / 2 | 账户界面切片，后续实现是否覆盖其行为待核实 |
| dual-ear | `ui-dual-ear-slice` / `cdccc32` | 133 / 2 | 双耳控制界面切片，语义归属待核实 |
| history | `ui-history-slice` / `1c0a476` | 131 / 1 | 历史删除生命周期切片，语义归属待核实 |
| save | `ui-save-slice` / `0461f04` | 133 / 1 | 本机历史保存状态切片，语义归属待核实 |
| production-rename-v21 | `release/yilu-production-v21` / `20da583` | 51 / 1 | 正式发布对照，独立保留 |
| repo-organization | `chore/repo-organization` / `dc587ef` | 0 / 0 | 本轮隔离整理整合线；表中 HEAD 为采样时状态 |
| repo-org-task-01 | `chore/repo-organization-01` / `dc587ef` | 0 / 0 | 本任务临时分支；公开来源清单在此提交 |

其他重要引用已逐项记录对象、可达性和提交摘要，并验证 68 个引用均可解析到提交：

- `baseline/20261104/*` 为整合前快照；`chore/android-main-baseline` 为共同主线整合记录；`internal-beta-integration` 为早期测试集成记录。
- `android`、`android-cloud-auth`、`android-test-endpoints` 保留早期 Android 分叉，分别有 6、29、22 个基线不可达提交；`azure-auto-omit-from`、`azure-experiments` 分别有 12、16 个，属于 Azure 实验来源。
- `cloud-admin`、`cloud-admin-live`、`cloud-api-business`、`cloud-auth`、`cloud-platform`、`cloud-qa`、`cloud-tests` 保留 Cloud API 与后台阶段成果，基线不可达提交数依次为 12、15、17、7、8、11、10。
- `luna-ticket02`、`luna-ticket03-face-manual` 分别有 3、2 个基线不可达提交；`luna/ticket04-face-continuous`、`agent/ticket04-debug-settings` 已可达；`ui-solo-slice` 有 1 个。这些均作为阶段对照保留。
- `pi-agent-*` 是早期账户、翻译路由、连续翻译、验证码与调试任务引用，逐支有 0 或 1 个基线不可达提交；本轮不因名称或可达性删除。

引用及共有 Git 对象仍保留原仓库；没有制作完整 Git bundle。未经逐项复核和独立对象备份，不删除分支、标签、工作树或运行会丢失对象的清理操作。历史分支存在不表示其实现仍生效；旧开发规则参见 [基线记录](baselines.md)，现行项目入口参见 [README](../../README.md)。

## 检查点覆盖与恢复

恢复材料仅存于本地工作项的 `checkpoints/`，由该目录忽略规则排除；不提交原始补丁、文件副本、设备记录和私有清单。

| 内容 | 保存与核验 |
| --- | --- |
| 旧根 4 个 tracked Agent 文件 | 复用原源码检查点的 3 份二进制补丁，保留整体与 index 分层恢复方式；与当前文件哈希一致，不合入 v30 |
| 旧根 15 份未跟踪源码 | 复用已核验的逐文件副本，记录原 HEAD、相对路径和 SHA-256 |
| 旧根文档、设计与构建描述 | 新增 102 份本地副本，包括上下文、ADR、文档、历史评审、5 张 PNG，以及 Makefile、go.mod、开发 compose 描述 |
| 原 integration 工作项 | 新增 34 份 Markdown 副本 |
| main-baseline 研究与工作项 | 新增 51 份副本：21 份文档、28 张 PNG、2 份研究脚本 |
| 两个整理工作树的起始工作项 | 各 12 份副本；权威工作项仍由原本地 tracker 维护，副本只是时间点证据 |

原三个源码检查点分别核验了 18 个文件哈希。本轮补充检查点有 211 份副本及 manifest、SHA256SUMS；清单含采样时间、worktree、HEAD、相对路径、内容哈希和逐项保留策略。

在独立临时 clone 的原始 HEAD 上，实际运行 `git apply --check` 和 `git apply`，整体补丁及 staged/unstaged 分层恢复均成功；4 个 tracked 文件 SHA-256 全部符合原记录。原源码副本及全部补充副本也恢复到不存在的目标路径并核对 SHA-256。临时恢复目录已移除；原工作树和原 index 未改动。

需要恢复时，先核对 manifest 和 SHA256SUMS，再从记录的原 HEAD 建立独立 checkout。整体补丁与分层补丁二选一；未跟踪副本只写入不存在的相对路径，逐项验证哈希后审查归属。检查失败或来源漂移时重新取样，不覆盖现有工作。

## 未覆盖与迁移前置条件

- 凭据、env、private、本机配置、技能链接保留原处，不复制、不读取私密正文、不公开；需要搬机或退役工作树时先单独制定私有迁移方案。
- 设备原始日志、bugreport、界面转储、未分类运行资料保留原处，不作为可公开文档归档。两份可能含敏感内容的历史 diff 未复制，需脱敏审查后再决定保存方案；本轮不允许清理原件。
- 依赖、构建目录、生成协议代码、运行缓存未备份，仍留原处；未承诺可重建。清理前需验证依赖获取及构建复现，APK 另按 Release 和哈希管理。
- Git 内部对象、仓库外资料及整机环境未完整备份。本地清单不是整仓或整机备份，也不证明全部功能独有性已核实。

采样前后未观察到源码、状态或引用漂移；没有冻结其他进程。后续移动或退役前，重新比较状态、引用与受影响内容哈希，变化的资料先补快照。公开归档前逐项检查敏感内容与现行引用，验证替代入口；功能合并另行验收。本轮仅验证来源与恢复，没有重跑产品构建、服务测试或真机体验，也未 push、部署或发版。
