# Android 工程

项目主产品，原生 Kotlin + Jetpack Compose 单 Activity，包含收音/播放、单人同传、面对面翻译、Cloud 账户和历史功能。模块关系见[项目架构](../docs/architecture.md)，版本与候选功能见[仓库状态](../docs/development/repository-status.md)。

## Mac 开发

1. 用 Android Studio 打开本目录，安装 SDK Platform 36、Build Tools 36.0.0。
2. 选择 JDK 17 或兼容版本，通过 Gradle Wrapper 同步。
3. 选择 `app` / `debug`，运行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

工程使用 AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21，minSdk 26、compileSdk/targetSdk 36。工具版本以[构建配置](build.gradle.kts)和[应用配置](app/build.gradle.kts)为准。

## 包与连接

| 构建 | 包名 | 基线默认入口 |
|---|---|---|
| Debug | `com.verba.interpretation.debug` | `https://47-129-170-16.sslip.io` |
| Release | `com.verba.interpretation` | `https://yszn.nlplayer.org` |

WebSocket 使用相应主机的 `/ws/translate`。这些是[当前 main 构建配置](app/build.gradle.kts)中的默认值，不是对线上可用性或服务版本的验证；App 本地设置也可能覆盖它们。Release 签名在受保护环境单独保存。

本机联调可使用 USB 或无线 ADB；Android 模拟器的宿主机地址通常为 `10.0.2.2`，`adb reverse` 也可建立本地端口映射，但映射不会自动替换 App 内配置的服务地址。

## 音频与验证

采集协议使用 16 kHz、mono、PCM16，每包 1280 samples / 2560 bytes / 80 ms。播放侧处理 TTS PCM 和声道路由。协议或生命周期改动同时影响翻译服务、页面状态、收音和播放，关系见[架构说明](../docs/architecture.md)。

真机验证包括两种翻译模式、开始/停止、暂停/恢复、方向切换、字幕配对、尾音播放、耳机声道、断网与返回页面。单测通过不能替代这些操作；当前 v30 尚待维护者验收。

- [安装、哈希与测试包识别](../docs/development/baselines.md)
- [质量检查与反馈材料](../translation-test-kit/README.md)
- [OTA 发布](../docs/deployment/app-ota-update.md)
- [早期 Android 说明归档](../archive/documents/android-initial-guide.md)：保留原始验证步骤，内含旧端点和旧 UI 行为，不能直接当作当前验收规格。
