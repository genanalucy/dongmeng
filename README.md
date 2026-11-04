# 言枢智能翻译

以 **Android 为主要开发方向**的实时语音翻译产品，提供对话、面对面双耳翻译、同声传译、账户与历史记录。`main` 是集成与验证入口，正式发布以不可变 Release 标签及 APK 校验值为准；`main` 最新代码不等于已发布或已通过真机验收。

## 从这里开始

- **开发、分支与测试包选择**：[开发与测试基线](docs/development/baselines.md)。切换工作树、恢复开发、测试或发布前先读。
- **Android**：`android/`，Kotlin、Jetpack Compose、Room、OkHttp WebSocket。
- **翻译 Agent**：`agent/`，Go、`net/http`、WebSocket，主线只使用火山引擎 AST。
- **Cloud API**：`cloud-api/`，Go、chi、PostgreSQL；账户、权益、会话治理、加密历史同步与 OTA 元数据。
- **管理端**：`cloud-api/admin-web/`，内部运营用途。
- **Web**：`web/`，保留为辅助原型与协议调试入口，见 [Web 原型开发](docs/development/web-prototype.md)。其能力不代表 Android 的正式功能。
- **测试材料**：`translation-test-kit/`，包含多语言场景、质量检查表与反馈表。

Azure 实验保存在独立分支，未作为主线可用引擎或静默降级。现有登录偶发失败、语言交换后无声、长时间翻译稳定性仍需回归，不因基线整合标记为已修复。

## Android 本地验证

需要 JDK 17 或兼容版本、Android SDK Platform 36 / Build Tools 36.0.0。使用项目 Gradle Wrapper；通过 `ANDROID_HOME` 或本地 `android/local.properties` 指定 SDK，勿提交本机路径。

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 在 `main` 的 Android 相关变更及 PR 上执行相同类别的检查；CI artifact 是验证产物，不自动成为 GitHub Release。

安装前检查 `output-metadata.json` 中的包名与版本，并记录 APK SHA-256：

```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.verba.interpretation.debug
```

`-r` 用于保留数据的覆盖安装，不保证允许降级或不同签名替换。若安装失败，先核对版本和签名；不要通过卸载、清数据来强行完成验收。

- Debug：`com.verba.interpretation.debug`，可与正式版共存。
- Release：`com.verba.interpretation`，需要独立保管的生产签名配置；密钥只保留在受保护环境。
- Debug 与 Release 的服务入口不同，必须分别验证登录和真实翻译。
- `versionName` 目前由构建时间生成，不能单独标识源码；测试时同时记录提交、包名、`versionCode` 和 SHA-256。

## 服务端验证

```bash
(cd agent && go test ./... && go vet ./...)
(cd cloud-api && go test ./... && go vet ./...)
make officialast-check
```

Agent 默认无 tag 构建仅覆盖安全占位行为；真实火山链路须使用 `officialast` build tag。`make officialast-check` 下载并验证固定官方附件、生成本地 protobuf，再测试、vet 与构建，不会进行真实翻译请求。生成代码和供应商密钥不提交。

Cloud API 的真实 PostgreSQL 集成测试需要专用隔离数据库，见 [Cloud API 运维与迁移说明](cloud-api/README.md)。未配置数据库的单测通过不代表已验证数据库或跨设备同步。

## 发布与部署

- [正式 OTA 操作](docs/deployment/app-ota-update.md)
- [内部测试版说明](docs/internal-beta-update-guide.md)
- [生产 Caddy 配置参考](docs/deployment/Caddyfile.production)
- [Cloud API 接口](cloud-api/API.md)

已有部署需求文档（[本地虚拟机](docs/deployment/local-vm-requirements.md)、[AWS 新加坡](docs/deployment/aws-singapore-requirements.md)）是历史规划，不作为当前线上部署事实。邮件 [SMTP 运维说明](docs/operations/email-registration-smtp.md) 也需先核对实际部署；文档存在不等于真实收码已验收。

本次将 Android 基线整合到 `main` 不触发服务部署、生产签名发布或 OTA 更新。不要提交 `.env`、签名文件、账号数据、数据库备份、`.scratch/`、`.pi/git/` 或构建产物。
