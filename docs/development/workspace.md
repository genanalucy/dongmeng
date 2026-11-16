# 整理工作树与环境

效力：2026-11-17 的隔离整理入口与环境探测记录。v30 是最新候选，用户已更新但尚未测试；环境可配置不代表产品体验已经验收。

## 应打开的目录

本机日常查看、讨论与继续整理使用 **`repo-organization` 工作树 / `chore/repo-organization` 分支**。工作树已存在，不必重新创建。运行下列命令找到实际目录并核对分支；其他机器可自行选择父目录，无需复用原机器的绝对路径。

```bash
git worktree list
cd <repo-organization工作树目录>
git status --short
git branch --show-current
git log -1 --oneline
git merge-base --is-ancestor dc587ef HEAD
```

最后一条退出码为 0 表示包含整理起点。开始任务前仍需查看实际差异，不能仅凭目录名确认版本；新任务在独立临时分支执行，检查后由整合线收口。

| 工作树 / 分支 | 现在的用途 |
| --- | --- |
| `repo-organization` / `chore/repo-organization` | v30 整理候选的用户入口与分批整合线 |
| `repo-org-task-*` / `chore/repo-organization-*` | 各批任务的临时执行环境；不是用户日常入口 |
| `main-baseline` / `test/android-phase1-baseline` | 原开发入口及 PR #1 的候选来源，保留原状供比较 |
| 旧根 / 本地 `main` | 旧提交与未提交 Agent、Qwen 成果及资料的保留位置；不直接 pull、覆盖或混入候选 |
| `integration` 和其他切片工作树 | 原 UI 集成、分功能及正式发布对照，逐项用途见 [来源清单](source-inventory.md) |

现有本地工作项的权威 tracker 仍在旧根的 `.scratch/repo-organization/`；任务工作树中的副本只作参考。具体本机目录和保全位置保存在本地执行报告，不要求公开仓库包含这些资料。

## 与版本、main 和 PR 的关系

| 来源 | 与整理候选的关系 |
| --- | --- |
| Debug v29 `51a7670` | 此前用户认可但仍需打磨的比较参考；到 v30 源码有两个后续提交 |
| Debug v30 `cd920a1` | 候选应用源码；保留当前翻译的 TXT/JSON 导出与已完成 Turn 可靠保存等成果 |
| 发布资料 `dc587ef` | v30 之后一个提交，仅改 `docs/deployment/download-center.md` 和 `site/download/releases.json`；整理分支从此起步 |
| GitHub `main` `f26d690` | 共同主线，比 `dc587ef` 少 35 个提交；不是最新 Debug 候选 |
| [PR #1](https://github.com/genanalucy/dongmeng/pull/1) | `test/android-phase1-baseline → main`，head 为 `dc587ef`；包含 Android、Agent、Cloud 和发布链路演进，不能按原 UI 标题理解为单纯界面改动 |
| [PR #2](https://github.com/genanalucy/dongmeng/pull/2) | `chore/repo-organization → main`，本轮整理入口；继承 PR #1 候选，再增加分批整理提交，并非仅含文档的独立代码基线 |

PR 与 GitHub main 于 2026-11-17 只读核对：两份 PR 均为 Open/Draft，PR #2 当时 head 为 `b11d2c9`（已合入来源清单），后续会随整理更新。候选尚未合入 main；合入、部署服务与发布 APK 是不同动作。正式 v21 独立保留。完整提交身份、标签与发布来源见 [来源清单](source-inventory.md)，本机不存在 v30 标签不代表缺少其源码对象。

旧根有字节不同的未提交 Qwen/Agent 文件，已独立保全；是否还有未被候选覆盖的行为需要另行判断，本批未自动导入，也未把文件差异当成功能缺失证据。

## 必要工具与依赖

现行目录布局保持不变：`android/`、`agent/`、`cloud-api/`、`cloud-api/contracttest/`、`web/`、`cloud-api/admin-web/`、`migrations/` 与 `site/download/`。相对 Go module replace、迁移目录与脚本入口依赖这些位置。

| 入口 | 环境及准备方式 |
| --- | --- |
| Android | JDK 21；仓库 Gradle 8.13 wrapper；Android SDK platform 36 与 build-tools 36.0.0；项目 JVM 目标为 17。SDK 路径通过 `ANDROID_HOME` 或仅本机的 `android/local.properties` 设置，后者已被忽略 |
| Agent / Cloud API / 授权契约 | Go；Agent 声明 1.23，Cloud 及独立契约模块要求 1.25.0。三个目录分别执行 Go 命令，根目录没有统一 go.work |
| Web 辅助原型 / 管理后台 | Node 22、npm；分别运行 `npm --prefix web ci` 与 `npm --prefix cloud-api/admin-web ci`，按各自锁文件安装 |
| 官方 AST 构建 | 从仓库根运行 `bash scripts/prepare-official-ast.sh`，下载并核对固定 SHA-256 后生成被忽略的协议文件；默认 Go 模式不验证此构建 |
| 下载工具 | Python 3、bash；不需要为了验证脚本而执行真实发布或加载线上凭据 |

例如，已安装 SDK 后可从根目录执行：

```bash
export ANDROID_HOME="<本机Android-SDK目录>"
(cd android && ./gradlew --no-daemon --offline help)
(cd agent && go list ./...)
(cd cloud-api && go list ./...)
(cd cloud-api/contracttest && go list ./...)
```

`--offline help` 需要已缓存 Gradle 与插件，只用于配置探测；新机器首次准备依赖可去掉 `--offline`。`go list` 只验证包解析。完整 Android 检查使用 `testDebugUnitTest lintDebug assembleDebug`；它们和服务端测试都应按改动范围另行执行。不要把旧树整份 `node_modules`、env、private 或本机配置复制进候选；服务私有配置独立准备，不能由本次源码检查推断线上环境已就绪。

## 本批实际探测与边界

本批在候选 `repo-organization` 工作树执行：

- 所有 tracked 路径均存在；Gradle wrapper 可执行，jar 的 SHA-256 为 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`，与 CI 固定值一致。
- 应用及配套服务、脚本和构建描述与 `dc587ef` 无差异；`cd920a1` 是候选祖先。原有工作树和未提交源码未改动。
- Go 1.26.5、Node 22.23.1、npm 10.9.8、JDK 21 和 Python 3.13.5 可调用。Agent、Cloud API、独立契约模块的 `go list ./...` 均成功。
- 候选树新增仅含 SDK 路径的被忽略 `android/local.properties`；platform 36、build-tools 36.0.0 和 SDK 内的 adb、sdkmanager 存在。`./gradlew --no-daemon --offline help` 成功配置项目；出现既有 `jvmTarget` 弃用警告，没有执行编译或测试。

仍缺两个前端的本地 `node_modules` 和官方 AST 生成协议；本批未安装或复制。`make` 不在 PATH，可使用各模块底层命令；37 个 `.pi/skills/` 链接缺少目标缓存，工具修复由后续整理任务处理。当前环境没有 `/dev/kvm`，没有启动模拟器，也没有检查用户设备连接。

本批未运行 Android 单测、lint、APK 构建、Go 测试/编译、前端检查、官方 AST 构建、数据库集成、下载发布、线上服务探测或真机体验。v30 发布说明中的既往自动检查属于发布者记录，不能算作本批实测。系统文件提供方、长期语音与跨设备同步仍待验收；用户手机的安装元数据未读取，APK 未重新下载计算哈希。

## 恢复原工作方式

若暂不使用整理候选，从 `git worktree list` 找到保留的 `main-baseline` 或原工作树，打开对应目录即可；先检查它的分支和未提交状态，不切换或重置其他工作树。旧根独有成果的恢复方法及限制见 [来源清单](source-inventory.md)。

本批未删除、移动或重建任何原工作树，未合入 main、部署或发版。SDK 配置为各工作树独立的忽略文件；保留原环境即可继续原工作方式，不需要撤销候选文档或覆盖旧源码。后续退役目录前需重新核对状态与保全范围。
