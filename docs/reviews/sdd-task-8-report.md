# SDD Task 8 Report — Android 内部 Beta 真机验收准备

**范围：** `/tmp/dngmeng-internal-beta-integration` 的 Task 8 准备工作。

**状态：** Debug 构建与验收资料准备完成；真机真实翻译、受控服务健康检查和只读 session/usage 核对均待批准操作员与验收用户执行。

**安全立场：** 本报告及关联文档不含密码、token、JWT、DSN、API key、Provider key、环境变量、数据库行、logcat、音频或对话正文。

## 1. 已核对依据

| 依据 | 核对结论 |
| --- | --- |
| Design：`docs/superpowers/specs/2026-08-27-internal-beta-closure-design.md` | M3 需要普通用户注册/登录、trial/兑换权益、一次中文→英语、正常结束及 Cloud session/usage 正确；仅属内测，不等于生产上线。 |
| Task 8 plan：commit `0281332` 中的 `docs/superpowers/plans/2026-08-27-internal-beta-closure-implementation.md` | 要求 token-safe 一页式清单、Debug APK、受控服务预检、真实设备结果、最小化只读 post-session 核对及 token-free 记录。该计划文件未保留在当前 HEAD，但已从该已提交历史读取。 |
| Existing handoff：`handoff.md` | Android 为原生 Kotlin/Compose；Debug 可临时使用 HTTP/WS；Release 只接受 HTTPS/WSS；不得记录秘密；真实设备验收仍是未完成项。 |
| Task 5 evidence | `a1874c1`、`2ccf115` 与后续 `da87c23` 覆盖 Cloud session 结束重试、终态失败可见性及 drain 后收口；`TranslationSessionCoordinatorTest` 覆盖单次最小化 usage、usage 失败不阻断结束和取消后晚到 grant 收口。 |
| Task 7 evidence | `fb3f6dd`、`7d837a9` 引入并加固 Cloud–Agent fake-Provider 授权 E2E harness；它证明授权边界，不证明真实 Provider 翻译。 |

## 2. Android 构建与网络配置核对

- `android/app/build.gradle.kts`：AGP `8.13.2`、Kotlin `2.2.21`、Java/Kotlin target `17`；Debug variant 的包名为 `com.verba.interpretation.debug`。
- Debug 仅在受控 internal beta 使用临时 `http://114.132.83.144:8080`、`http://114.132.83.144:18765` 和 `ws://114.132.83.144:18765/ws/translate`；它们不是生产 endpoint。
- Release 保持 HTTPS/WSS 占位地址；`CloudEndpointSecurityPolicy` 仅在 Debug 允许 HTTP，Release 仅接受 HTTPS。
- `CloudAgentHandshake` 通过 WebSocket subprotocol 传递 session JWT；`CloudApi` 注释与实现避免记录 token。
- `TranslationSessionCoordinator` 仅提交聚合 usage（`session_id`、`audio_seconds`、`characters=0`），且 usage 失败不阻断 end。
- 已使用明确的 `JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17` 与 `ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk` 运行 `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`；exit code 为 `0`，Gradle 报告 `BUILD SUCCESSFUL`。生成产物为 `android/app/build/outputs/apk/debug/app-debug.apk`，且该路径与 Gradle 缓存受 Git ignore 规则保护。

## 3. 生成的交付物

| 文件 | 目的 |
| --- | --- |
| `docs/reviews/internal-beta-android-device-checklist.md` | 给操作员与验收用户的受控两分钟操作单；包含启动前提、端口/URL、注册/登录/trial、单次中译英、停止与 usage、失败收口、日志禁令及已知限制。 |
| `docs/reviews/internal-beta-android-device-acceptance.md` | 真机结果 token-safe 记录模板；区分通过/失败/未运行/阻塞，禁止录入敏感值和正文。 |
| `docs/reviews/sdd-task-8-report.md` | 本 SDD Task 8 范围、前置证据、构建配置与待执行边界记录。 |

## 4. 明确未执行的动作

- 未启动 Cloud API、Translator Agent 或真实 Provider。
- 未连接、查询、迁移或修改任何数据库。
- 未执行真实网络健康检查，以免隐式触发受控服务依赖。
- 未执行用户真机安装、注册、登录、翻译或截图采集。
- 未收集 logcat、网络包、token、DSN、API key、环境文件或会话正文。

## 5. 待批准执行的验收顺序

1. 授权操作员仅在 approved internal beta 网络中启动受限配置的 Cloud API 与 Agent，并在不输出秘密的前提下确认 readiness/health。
2. 验收用户安装本次 Debug APK，允许麦克风，使用普通用户注册/登录，确认 trial/entitlement。
3. 在单人同传完成一次中文→英语；只报告字幕/语音是否出现，不发送内容。
4. 点击停止，确认 UI 和麦克风收口；授权操作员按独立只读受控流程确认 session 已结束、usage 恰一条且无正文/PCM/JWT/密码持久化。
5. 以 `internal-beta-android-device-acceptance.md` 记录事实；失败时遵循清单的安全收口，不推断成功。

## 6. 限制与生产前缺口

本任务仅准备内测验收，尚未产生真实设备翻译证据。生产仍需要正式 HTTPS/WSS 域名、Secret Manager、托管数据库与迁移部署流程、备份、监控/告警、对象存储清理、Release 签名与分发、容量与限流验证。
