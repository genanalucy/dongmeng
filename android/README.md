# Verba Interpretation Android

原生 Kotlin + Jetpack Compose 单 Activity 客户端，包名 `com.verba.interpretation`。

## 工具链

- Android Studio（Mac 上建议安装当前稳定版）
- JDK 17（在 Android Studio > Settings > Build Tools > Gradle 中选择 Embedded JDK 17）
- Android SDK Platform 36、Build Tools 36
- AGP 8.13.2、Gradle 8.13、Kotlin 2.2.21
- minSdk 26，compileSdk/targetSdk 36

本仓库环境没有 Java 或 Android SDK，因此未执行 Android 构建。Gradle Wrapper 文件已完整提交；`gradle-wrapper.jar` 来自 Gradle 官方 GitHub 仓库 `gradle/gradle` 的 `v8.13.0` 标签，SHA-256 为：

```text
81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
```

Android Studio 首次打开时会通过 Wrapper 下载 Gradle 8.13 分发包并完成同步。

## Mac + Android Studio

1. Android Studio 选择 **Open**，打开本目录 `android/`。
2. 确认 Gradle JDK 为 17，并通过 SDK Manager 安装 Android 36 SDK。
3. 等待 Gradle Sync；选择 `app` 的 `debug` variant。
4. 运行 `./gradlew testDebugUnitTest`、`./gradlew lintDebug` 和 `./gradlew assembleDebug`（补齐 wrapper 后）。

## 模拟器

1. Device Manager 新建 API 36 模拟器，启用麦克风输入。
2. 启动本机 Agent（监听 `127.0.0.1:18765`）。
3. 模拟器访问宿主机通常需将 debug URL 临时改为 `ws://10.0.2.2:18765/ws/translate`，或使用下述 `adb reverse` 后保留默认 URL。
4. 运行 debug app，授权麦克风；模拟器只适合功能检查，不代表真实耳机声道和时延。

## USB 真机与 adb reverse

Debug 默认连接 `ws://127.0.0.1:18765/ws/translate`，请求携带 `Origin: http://127.0.0.1:5173`。连接 USB、开启开发者选项和 USB 调试后：

```bash
adb devices
adb reverse tcp:18765 tcp:18765
adb reverse --list
```

然后安装/运行 debug app。断开映射：

```bash
adb reverse --remove tcp:18765
```

## 无线 ADB

Android 11+：

1. 手机和 Mac 位于可信的同一局域网；开发者选项中开启 **无线调试**。
2. 用配对地址执行 `adb pair PHONE_IP:PAIR_PORT` 并输入配对码。
3. 用连接地址执行 `adb connect PHONE_IP:ADB_PORT`，再确认 `adb devices`。
4. 设备连上后执行 `adb reverse tcp:18765 tcp:18765`；若设备/系统不支持无线 reverse，应使用仅限局域网开发的可配置 Agent 地址，勿把 cleartext 配置带入 release。

## 真机音频测试

### 单人同传

1. 使用有明确左右声道的有线或蓝牙立体声耳机，关闭扬声器外放，确认系统左右声道未反转。
2. 在系统设置授予麦克风权限；进入“单人同传”，目标语言选 `English` 或 `中文`。
3. 分别选择“左耳”“右耳”“双耳”“仅字幕”，用已知 TTS 测试句验证：左/右仅对应声道出声，双耳同时出声，仅字幕无声。
4. 连续讲话至少 50 秒，确认会话保持连接、字幕继续滚动，PCM 上行无断包报错。
5. 验证“暂停”停止采集并完成当前 Turn，“恢复”创建新 Turn，“结束”发送 `finish` 并回到空闲。
6. 最新 Turn 的每个显示断句为一个配对气泡：原文在上、细分隔线下为译文；原文无对应译文时显示固定“正在翻译…”，仅译文到达时不显示虚构原文。气泡使用主题色 token，不使用硬编码色值。
7. 保持在列表最新处时，新字幕或错误卡片应平滑跟随至最新项；手动上滑查看历史后，新内容不得强制滚动，首次进入也不得跳转。

### 面对面翻译

1. 进入“面对面”，先选择“手动 PTT”。按住左侧说中文，松开后确认英文字幕出现且 TTS 只进入右耳；处理和播放完成前，左右按钮都应锁定。
2. 按住右侧说英文，松开后确认中文字幕出现且 TTS 只进入左耳。持续按住超过 25 秒时应自动结束输入。
3. 切换“自动交替”，点击“开始（默认左侧）”。连续说中文超过 50 秒，确认当前 Turn 保持连接；右侧按住抢话、松开恢复左侧时才切换 Turn，麦克风没有重复授权或明显物理重启。
4. 自动模式按住右侧立即抢话并说英文；松开后立即恢复左侧中文。确认任意时刻只有一个 active 输入 Turn，而已结束 Turn 仍可继续更新字幕和播放。
5. 点击“停止采集”，确认不再创建新 Turn，但已结束的后台 Turn 继续完成字幕和 TTS，最终返回空闲。
6. 自动交替至少重复 10 次左右切换，确认 TTS 始终按 Turn 创建顺序播放，不发生中英文 PCM 交错。
7. 每个显示断句只能出现一个双语气泡：原文在上、`outlineVariant` 细分隔线下为译文；同一 Turn 按断句索引配对，不合并多句。原文先到时下方固定显示“正在翻译…”，仅译文先到时显示译文而不虚构原文。左侧气泡使用 `surface` 与细描边，右侧使用 `primaryContainer`；译文分别使用 `onSurfaceVariant` / primary 色调。
8. 耳麦控制区在普通 Column 流中时，收音占位符下方只保留正常的小间距，不得出现大段空白尾部。

### 故障与生命周期

1. 录音中拔出麦克风/耳机、撤销权限或关闭 Agent，应用应进入错误状态并停止全部录音和后台 Session，而不是崩溃。
2. 录音中返回首页、切到后台或锁屏，确认采集和全部 Session 被取消；返回应用后必须重新开始。
3. Android 音频路由可能受蓝牙 profile、厂商 DSP 和无障碍声道设置影响；最终验收必须使用目标机型与目标耳机。

## 网络与协议

- debug：默认 `ws://127.0.0.1:18765/ws/translate`，仅 network security config 对 loopback/localhost 放行 cleartext。
- release：默认 `wss://api.example.com/v1/translation`，禁止 cleartext；这是需求指定的占位地址，不包含密钥。
- Agent 合约严格使用：`start` JSON（`mode=s2s`、zh/en、PCM 16000）、2560-byte 二进制上行、`finish` JSON；接收 `ready`、四类字幕事件、`finished`、`error` 和二进制 mono PCM16 TTS。
- 录音：16000 Hz、mono、PCM16，每包严格 1280 samples = 2560 bytes = 80 ms。
- 播放：16000 Hz、stereo、PCM16，支持 left/right/both/captions 软件路由。

工程不包含密钥、不使用 WebView，也没有加入任何未经当前 Agent 源码确认的供应商字段。
