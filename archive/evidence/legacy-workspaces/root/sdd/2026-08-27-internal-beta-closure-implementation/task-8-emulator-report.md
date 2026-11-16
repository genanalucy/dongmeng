> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-8-emulator-report.md`；原始 SHA-256 `5c14941c0895755a648c2ab7b374c8d62b20427e7616bc443639afe035ff3422`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 8：本机临时 Android Emulator 验证报告

日期：2026-08-27
工作区：`[旧临时路径]`

## 执行结果

### 追加验证（Clash 代理恢复）

- 通过 TCP 检查确认 `127.0.0.1:7890` 正在监听；通过代理访问 Android repository 元数据得到 HTTP 状态码 `200`。未输出代理配置内容。
- 已设置 `HTTP_PROXY`、`HTTPS_PROXY` 及小写变量为本机代理，仅用于 SDK 下载。
- 成功下载必要 `emulator` 与 `system-images;android-35;google_apis;x86_64`（另确认 platform-tools）；创建了隔离临时 AVD `dngmeng_task8_disposable`。
- Emulator 使用 headless、`-no-snapshot` 启动，但在等待窗口内未达到可用 `adb device` 状态（`boot_failed`）。清理 trap 已删除临时 AVD 及隔离 AVD home；共享 SDK 安装按授权保留。
- 因未完成启动，未安装 APK、授予 `RECORD_AUDIO` 或执行 UI/SharedPreferences 迁移实测。


- 已检查授权 SDK 目录 `$HOME/.local/share/verba-android-tools/android-sdk`：现有 `cmdline-tools/latest/bin/sdkmanager`、`avdmanager` 与 `platform-tools/adb`；未发现 `emulator` 二进制或现有 AVD。
- 已尝试使用 SDK 的 JDK 17 运行 `sdkmanager --list` 以及下载 `emulator`、`platform-tools`、单个 `system-images;android-35;google_apis;x86_64`。远程仓库获取在超时前未完成，因此未下载任何新 SDK 组件，未创建 AVD。
- 未启动 Emulator，故无法执行 boot、安装 APK、授予麦克风权限或 UI 操作验证。
- APK 已安全静态检查：文件存在，包名 `com.verba.interpretation.debug`，版本 `1.0-debug`，启动 Activity `com.verba.interpretation.MainActivity`，声明 `INTERNET` 与 `RECORD_AUDIO`。
- 已检查源码中的旧 WS endpoint 迁移逻辑：`EndpointSettings.current()` 会将精确旧值 `ws://[旧环境地址已脱敏]:18765/v1/translation` 替换为构建默认值并持久化；但因 Emulator 不可用，未能通过 `adb run-as` 预置 SharedPreferences 和实机确认。
- 音频入口为麦克风采集（`RECORD_AUDIO`）；未发现音频文件 import 功能。未伪造识别文本测试。

## 安全边界与限制

- 未读取、输出或使用任何 token、密码、DSN、API key 或用户账号。
- 未连接受保护的 `127.0.0.1:5432`，未发起账号/会话连接。
- 未修改源码；未提交 APK、SDK、AVD 或缓存。
- SDK 安装保持原状；由于本次下载未成功，没有新增临时系统资源可清理。没有创建 AVD，因此无需销毁 AVD。
- 未登录时无法验证真实云端连接、发送文本或识别结果；若需该路径，仍需要一次专用 disposable account。

## 使用过的命令（敏感值已排除）

```text
find [旧临时路径] ...
$SDK/cmdline-tools/latest/bin/sdkmanager --list
$SDK/cmdline-tools/latest/bin/sdkmanager emulator platform-tools system-images;android-35;google_apis;x86_64
$SDK/build-tools/36.0.0/aapt dump badging .../app-debug.apk
```

结论：代理已恢复且 SDK 组件下载成功，但 Emulator 未在等待窗口内启动到可用状态；未进行 APK/UI/adb 迁移实测。仅完成 SDK/构建产物检查与静态迁移逻辑确认。
