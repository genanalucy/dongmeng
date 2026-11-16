# 译路随行

以 Android 为主要维护方向的实时语音翻译产品，提供对话、面对面翻译、单人同传、账户与翻译历史。配套服务负责实时翻译、账户与权益、云端历史副本、管理后台及下载更新。

**当前整理候选为 Debug v30：用户已更新，尚未测试。** 发布、自动检查和用户体验验收是不同状态；v29 是此前认可但仍需打磨的比较参考，正式 v21 独立保留。

## 从这里开始

- **打开项目、继续开发**：[整理工作树与环境](docs/development/workspace.md)。本机日常入口是 `repo-organization` 工作树 / `chore/repo-organization` 分支；用 `git worktree list` 找到实际目录。临时任务工作树用于分批实施。
- **理解改动影响**：[模块与依赖地图](docs/architecture/README.md)，按翻译、账户、历史、下载链路定位源码和检查。
- **找当前说明与既有决策**：[文档导航](docs/README.md)、[领域上下文](CONTEXT.md)、[云端历史加密与管理员访问决策](docs/adr/0001-cloud-history-encryption-and-admin-access.md)。
- **查旧方案与来源**：[可见归档](archive/README.md)、[源码来源与成果保全](docs/development/source-inventory.md)。历史资料分批核实迁移，尚未归档不代表可以删除。

## 产品由哪些部分组成

| 部分 | 职责与入口 | 主要关联 |
| --- | --- | --- |
| [Android](android/) | 用户界面、采集与播放、翻译控制、本机历史副本及 TXT/JSON 导出 | 通过 WebSocket 使用 Agent，通过 HTTP 使用 Cloud API；导出交给系统文件选择器 |
| [实时翻译 Agent](agent/) | 活动后端，连接翻译供应商，转发字幕与语音，验证翻译会话授权 | 消费 Cloud API 的授权约定；源码支持 Qwen 与火山，实际供应商由部署配置决定 |
| [Cloud API](cloud-api/) | 账户、权益、翻译会话治理、加密云端历史副本及更新元数据 | 使用[根 PostgreSQL 迁移](migrations/)，服务 Android、Agent 和管理后台 |
| [管理后台](cloud-api/admin-web/) | 管理员登录与运营操作界面 | 依赖 Cloud API 管理接口与角色校验；[运行说明](cloud-api/admin-web/README.md) |
| [下载与更新](docs/deployment/download-center.md) | 官网下载页、正式与 Debug 双通道 OTA 的发布同步 | [版本清单](site/download/releases.json)是下载发布元数据的单一来源，APK 保存在 Release 附件与下载服务 |
| [Web 辅助路径](docs/development/web-prototype.md) | 浏览器原型与协议调试，仍有活动开发脚本和检查 | [web/](web/)使用 Agent；其旧限制不代表 Android 的现行能力 |
| [测试材料](translation-test-kit/) | 多语言场景、翻译质量检查表、用户反馈表 | 用于人工体验验收；回归证据由[文档导航](docs/README.md)标明效力 |

## 当前版本与验收

以下为 2026-11-17 整理核对的身份；实时分支状态仍以 Git 和 PR 为准。

| 对象 | 源码或来源 | 状态 |
| --- | --- | --- |
| 共同主线 `main` | GitHub `f26d690` | 共同整合目标；与最新 Debug 候选不同 |
| 最新 Debug v30 | tag `android-debug-yilu-20261117-v30` → `cd920a1`；版本 `20261117.0343` | 用户已更新，尚未测试；保留保存、重试、TXT/JSON 快照导出成果 |
| 整理候选 | `chore/repo-organization`，从 `dc587ef` 加入分批整理提交 | `dc587ef` 比 v30 源码多一个发布说明与清单提交；候选尚未合入 main |
| Debug v29 | `51a7670`；版本 `20261116.2307` | 此前认可的比较参考，仍有问题待打磨 |
| 正式 v21 | `20da583`；版本 `20261113.1836` | 独立正式发布来源；不随整理重新发布 |

完整身份与恢复边界见[来源清单](docs/development/source-inventory.md)；候选、main 与 [PR #1](https://github.com/genanalucy/dongmeng/pull/1)、[PR #2](https://github.com/genanalucy/dongmeng/pull/2) 的关系见[工作树说明](docs/development/workspace.md)。v30 的 [Release](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30) 记录过既往自动检查，它们不算本轮重新执行的验证。真实设备文件提供方、长期语音、跨设备同步仍待验收。

## 按影响范围检查

环境准备见[工作树说明](docs/development/workspace.md)。从仓库根执行所需检查；以下是现有命令入口，并非本次导航整理已经全部运行的结果。

```bash
(cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug)
(cd agent && go test ./... && go vet ./...)
(cd cloud-api && go test ./... && go vet ./...)
(cd cloud-api/contracttest && go test ./... && go vet ./...)
npm --prefix cloud-api/admin-web run test
npm --prefix web run test
python3 -m unittest discover -s scripts -p 'test_download_release.py'
```

Agent 默认无 tag 构建使用安全占位客户端；Qwen 路径由运行配置选择。真实火山链路另需 `make officialast-check` 准备固定官方协议并检查 `officialast` 构建，不发起真实翻译。Cloud API 与授权契约是两个独立 Go 模块。真实数据库集成需要[专用隔离测试库](cloud-api/README.md)，未配置时的跳过不算通过；两份前端还各有 `typecheck`、`lint`、`build` 检查。详见[模块检查边界](docs/architecture/README.md#已有检查入口)。

Android 包名为 Debug `com.verba.interpretation.debug`、正式 `com.verba.interpretation`，两通道不能混同。安装验收同时记录源码提交、包名、版本、签名和 APK SHA-256；不要靠卸载或清数据掩盖覆盖安装问题。合入源码、部署服务和发布 APK 分别执行；本次导航整理不代表已部署或完成真机验收。
