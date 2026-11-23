# 译路随行 · 实时语音翻译

译路随行是一款 Android 实时语音翻译应用。两人交谈时，在「对话」中轮流按住各自的麦克风说话；听一人连续发言时，切到「同传」查看原文和译文。已保存的会话可以在「我的 → 历史记录」中搜索和分享。早期源码与文档中使用的名称是“言枢智能”。

## 先看这里

| 想了解什么 | 入口 |
|---|---|
| 怎样登录、开始翻译、找回刚才的对话 | [Android 功能与使用指南](docs/android-features.md) |
| 代码在哪里、模块怎样连接 | [项目结构与模块职责](docs/architecture.md) |
| 当前该看哪个版本、哪些 PR 尚未合入 | [GitHub 仓库与版本状态](docs/development/repository-status.md) |
| Mac 上开发、验证、选择测试包 | [开发与测试基线](docs/development/baselines.md) |
| 接口、部署和操作说明 | [文档导航](docs/README.md) |
| 手动测试与反馈 | [测试材料](docs/testing/README.md) |
| 查旧设计、旧实现和验收记录 | [可见归档](archive/README.md) |

## 版本状态

截至 2026-11-23，最新正式 Release 是 [v21](https://github.com/genanalucy/dongmeng/releases/tag/android-production-yilu-20261113-v21)，最新 Debug 预发布是 [v30](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30)。v30 已由维护者安装，尚待真机验收；此前使用的 v29 也有待打磨的问题。

功能指南对应 `main` 源码。安装包请按 Release 标签核对；v30 候选改动和此前的综合整理分别见 [PR #1](https://github.com/genanalucy/dongmeng/pull/1) 和 [PR #2](https://github.com/genanalucy/dongmeng/pull/2)。

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
docs/                    现行项目文档
  testing/               手测场景、质量检查和反馈表（Markdown 与 PDF）
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

Agent 的启动入口将翻译请求路由到 Qwen。仓库也保留火山 AST 客户端及其带 `officialast` 标签的构建检查。供应商配置与本地开发步骤见 [Web 与 Agent 本地开发](docs/development/web-prototype.md)。

正式 APK、签名、下载和 OTA 操作见 [发布说明](docs/deployment/app-ota-update.md)。本轮整理不发布 APK、不更新 OTA、不部署服务。
