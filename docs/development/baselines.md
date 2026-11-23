# 开发与测试基线

## 源码与版本

`main` 是共同开发基线；已发布安装包由 Release 标签和附件标识。当前正式 v21、Debug v30 以及 PR #1/#2 的关系见[仓库状态](repository-status.md)。

从 GitHub 克隆并创建任务分支：

```bash
git clone git@github.com:genanalucy/dongmeng.git
cd dongmeng
git switch -c <task-branch>
```

已有开发目录时，先查看 `git status --short --branch` 和 `git worktree list`。原 Linux 开发目录中仍有独有未提交成果；从 GitHub 新克隆不会包含这些资料，不能用远端状态推断它们已经保全。此轮清理使用独立工作树，没有覆盖旧目录。

## Mac 上开发 Android

通过 Android Studio 打开 `android/`，安装 Android SDK Platform 36、Build Tools 36.0.0，选择 JDK 17 或兼容版本。SDK 路径放在本机 `android/local.properties` 或环境变量中。

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`，同目录 `output-metadata.json` 记录版本信息。Mac 上可用：

```bash
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell dumpsys package com.verba.interpretation.debug
```

以上命令从 `android/` 执行。`-r` 是保留数据的覆盖安装；签名不一致或降级失败时，先核对包名、版本和签名，再决定数据保全方案。Debug 包与正式包可以并存；不同 Debug 版本会相互覆盖。

## 各模块验证

| 范围 | 在仓库根目录执行 | 验证边界 |
|---|---|---|
| Android | 进入 `android/` 执行上述 Gradle 命令 | 单测、lint、编译；不代表真机音频通过 |
| Agent | `(cd agent && go test ./... && go vet ./...)` | 默认安全构建，不调用真实翻译 |
| 火山 AST 构建 | `make officialast-check` | 下载并校验官方附件、生成本地协议代码、测试/vet/构建；不进行翻译请求 |
| Cloud API | `(cd cloud-api && go test ./... && go vet ./...)` | 未配置隔离数据库时不覆盖真实 PostgreSQL 集成 |
| Web | `npm --prefix web ci` 后执行 `test`、`typecheck`、`lint`、`build` | 本地辅助原型 |
| 管理后台 | `npm --prefix cloud-api/admin-web ci` 后执行上述四个 npm 脚本 | 管理端测试与静态构建，不代表后端联调通过 |

CI 定义见 [Android CI](../../.github/workflows/android-ci.yml)，目前只覆盖配置中匹配的 Android 路径。本轮文档清理不修改 CI 或构建逻辑，也不将未运行的检查记为通过。

真实 PostgreSQL 使用专用隔离环境，操作见 [Cloud API 说明](../../cloud-api/README.md)。真实供应商调用会产生费用，需要另行确定测试范围和费用上限。

## 测试包识别与真机记录

记录包名、versionCode、versionName、源码提交、APK SHA-256、设备/Android 版本、实际服务入口、语言方向及操作步骤。版本名由构建时间生成，不能单独确定源码。

- Debug 包：`com.verba.interpretation.debug`。
- 正式包：`com.verba.interpretation`。
- 当前默认端点来自 [构建配置](../../android/app/build.gradle.kts)；App 的本地设置可能覆盖默认值。
- 历史包校验值及标签/源码错位说明见 [2026-11-04 基线归档](../../archive/documents/2026-11-04-development-baseline.md)。保留原标签和附件，不将重新构建的包冒充原二进制。
- 手动操作与结果使用[测试材料](../../translation-test-kit/README.md)记录。

既有 v30 真机验收、长时间翻译和并发验证仍未完成。本轮目录整理不改变这些结论。
