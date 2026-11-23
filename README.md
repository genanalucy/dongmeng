# 译路随行 · 实时语音翻译

Android 实时翻译项目，包含单人同传、面对面翻译、账户与云端历史，以及配套翻译服务和管理后台。早期源码与文档中使用的名称是“言枢智能”。

## 先看这里

| 想了解什么 | 入口 |
|---|---|
| 代码在哪里、模块怎样连接 | [项目结构与模块职责](docs/architecture.md) |
| 当前该看哪个版本、哪些 PR 尚未合入 | [GitHub 仓库与版本状态](docs/development/repository-status.md) |
| Mac 上开发、验证、选择测试包 | [开发与测试基线](docs/development/baselines.md) |
| 接口、部署和操作说明 | [文档导航](docs/README.md) |
| 手动测试与反馈 | [测试材料](translation-test-kit/README.md) |
| 查旧设计、旧实现和验收记录 | [可见归档](archive/README.md) |

## 版本状态

截至 2026-11-23，最新正式 Release 是 [v21](https://github.com/genanalucy/dongmeng/releases/tag/android-production-yilu-20261113-v21)，最新 Debug 预发布是 [v30](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30)。v30 已由维护者安装，尚待真机验收；此前使用的 v29 也有待打磨的问题。

`main`、已发布 APK 和运行中的服务是不同的状态，不能仅凭最新分支判断线上功能。候选代码在 [PR #1](https://github.com/genanalucy/dongmeng/pull/1)，此前的综合整理在 [PR #2](https://github.com/genanalucy/dongmeng/pull/2)；两者尚未合入。此轮从 `main` 独立整理文档和目录，不引入候选功能。

## 项目目录

```text
android/                 Android 主产品
agent/                   实时翻译服务（Go，不是编码代理配置）
cloud-api/               账户、权益、会话、历史和更新 API
  admin-web/             内部运营管理后台
web/                     本地辅助原型与协议调试
branding/                品牌资源
migrations/              PostgreSQL 迁移
scripts/                 开发、构建和检查脚本
translation-test-kit/    场景、质量检查和反馈表
docs/                    现行项目文档
archive/                 历史项目资料与来源索引
```

`site/` 下载站和部分后续工具仍在候选 PR 中；当前主线没有该目录。仓库仅保留项目代码、资源、构建配置和文档，AI 工具的提示词、技能和专用执行规约已退出本分支。

## 验证入口

Android 需要 JDK 17 或兼容版本、Android SDK Platform 36 / Build Tools 36.0.0：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

服务端默认检查：

```bash
(cd agent && go test ./... && go vet ./...)
(cd cloud-api && go test ./... && go vet ./...)
```

此处 `main` 的 Agent 真实链路使用火山 AST，默认无 tag 构建是安全占位；真实链路构建准备见 [Web 与 Agent 本地开发](docs/development/web-prototype.md)。Qwen 等候选实现的状态见 [仓库状态](docs/development/repository-status.md)。默认 Go 测试通过不代表真实供应商、数据库或手机端验收通过。

正式 APK、签名、下载和 OTA 操作见 [发布说明](docs/deployment/app-ota-update.md)。本轮整理不发布 APK、不更新 OTA、不部署服务。
