> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-review.md`；原始 SHA-256 `1543af9a3ee0d2b438fcc4ada3b1e5261adf336a37c27d50944bf0bfd70e3e8e`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 Android 高风险只读审查

## 结论

**未发现 Critical；发现 4 个有源码直接证据支持的 Important 缺陷。Task 5 当前不能按“Android 会话闭环已满足”验收。**

本次仅静态读取 `[旧临时路径]` 的当前源码与单元测试，并对照：

- `docs/superpowers/plans/2026-08-27-internal-beta-closure-implementation.md` 的 Task 5；
- `docs/superpowers/specs/2026-08-27-internal-beta-closure-design.md`。

按约束未修改 Android 源码、未运行 Gradle/build、未运行设备或模拟器、未派发代理。因而本文不声称运行时或真机验证通过。

## Important defects

### I-1. 自动面对面停止立即结束 Cloud session，早于未完成 turn/TTS drain

**证据**

- `android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceViewModel.kt:92-95`：`stopAuto()` 先执行 `coordinator.stopAuto()`，随后无条件立即调用 `endCloudSession()`。
- `android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceCoordinator.kt:170-194`：`stopAuto()` 只把状态置为 `STOPPING` 并对当前输入 session 发出 `finishSessions`；已有 entries、尚未返回的 `Finished`、排队 TTS 和 `PlaybackWork.Drain` 仍然存在。
- `FaceToFaceCoordinator.playbackWorkFinished()`/`settleIfDrainedLocked()`（同文件约 `240-340`）才是 entries 和播放真正排空、`STOPPING → IDLE` 的位置，但这里没有触发 Cloud close。
- `android/app/src/test/java/com/verba/interpretation/ui/FaceToFaceCoordinatorTest.kt:139-158` 只验证纯 coordinator 仍为 `STOPPING`，没有验证 ViewModel 的 Cloud session 结束时机。

**影响**

明确违反 Task 5 的“automatic face-to-face stop does not terminate cloud session before outstanding turn/TTS drain is complete”和“Delay `endCloudSession()` only until ... all started turns ... terminal/drained”。控制面会在数据面仍收尾或本地仍播放时提前标记 session 结束并上报 usage。

### I-2. 手动面对面空输入只取消 AgentSocket，不关闭已创建的 Cloud grant

**证据**

- `android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceCoordinator.kt:109-139`：空输入被判定为 discard，返回 `cancelSessions = listOf(session)`，并回到 `IDLE`。
- `android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceViewModel.kt:62-64, 154-165`：`manualRelease()` 仅应用 transition；`cancelSessions` 只调用 `AgentSocket.cancel()`，没有调用 `endCloudSession()`。
- `android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceViewModel.kt:116-130`：`cloudGrant` 因而继续保留，下一次操作会复用该 grant。
- `android/app/src/test/java/com/verba/interpretation/ui/FaceToFaceCoordinatorTest.kt:160-173` 只断言 socket session 被取消、转录为空和 phase 为 `IDLE`；没有覆盖“已经创建的 Cloud grant 被关闭”。

**影响**

直接违反 Task 5 的“manual face-to-face empty input closes the already-created Cloud grant”。用户松开空输入后 UI 已显示 idle，但 Cloud session 仍 active；若用户不再操作且应用继续前台，session 会一直留到退出、失败或服务端过期。

### I-3. 生命周期取消与异步 grant acquisition 存在竞态；ON_STOP/导航后仍可能启动 socket 和麦克风

**证据**

- `TranslationSessionCoordinator.open()`（`android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt:31-43`）启动一个未返回句柄、无 generation/cancel 标记的 coroutine；Cloud 请求成功后始终调用 `onGranted(grant)`。
- `InterpretationViewModel.openTurn()`（`.../ui/InterpretationViewModel.kt:111-128`）和 `FaceToFaceViewModel.startWithCloudGrant()`（`.../ui/FaceToFaceViewModel.kt:116-132`）在 callback 中直接保存 `cloudGrant` 并继续创建/启动 socket；没有检查此前是否已 `cancel()`、是否仍处于原始 STARTING/IDLE 操作代次。
- 两个 ViewModel 的 `cancel()` 在 grant 尚未返回时看到 `cloudGrant == null`，所以 `endCloudSession()` 无事可做；稍后 callback 仍可落地 grant。
- `MainActivity.kt:515-529` 和 `681-695` 在 `ON_STOP`/`onDispose` 调用 cancel，但这不能取消上述 in-flight acquisition。
- Face-to-Face 更严重：grant 回来后其模式/phase 前置检查失败时只执行 `created.socket.cancel()`（`FaceToFaceViewModel.kt:53-60, 67-74, 83-90`），仍未关闭刚落地的 `cloudGrant`。
- 当前 Android tests 中没有 ViewModel lifecycle recreation、in-flight open 后 cancel、或 `onCleared()` 的测试。

**影响**

违反“`cancel()` and ViewModel clear terminate a grant without leaving the microphone active”及设计中的“网络中断/生命周期收口停止录音并释放资源”。可导致离开页面或 Activity stop/recreation 后建立隐藏 WebSocket、启动录音，或遗留 active Cloud session；同时可能让旧 callback 污染重建后的状态。

### I-4. `end()` 把本地幂等状态在网络成功前永久删除，并吞掉 end 失败；一次瞬时失败即可遗留 active Cloud session

**证据**

- `android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt:46-61`：第一次 `end(sessionId)` 在发网络请求前执行 `startedAtMillis.remove(sessionId)`；后续调用立即 no-op。
- 同方法对 `cloud.endTranslationSession(sessionId)` 使用 `runCatching` 且不检查、不传播结果，也没有受限重试或 revoke fallback。
- `android/app/src/test/java/com/verba/interpretation/cloud/TranslationSessionCoordinatorTest.kt` 只覆盖重复调用和 **usage** 失败；没有 end 请求失败、scope cancellation 或重试后 exactly-once completion 测试。

**影响**

代码保证的是“至多一次尝试”，不是“Cloud session 最终结束一次”。网络中断、401 refresh 失败或进程/`viewModelScope` 取消时，请求可能未成功，而本地已经失去再次 end 的资格；服务端 active session 会阻塞下一次 session，直到过期/外部 revoke。该行为与 Task 5 产物“one cloud session ends exactly once”和设计要求的 session 释放不一致。

## 已确认满足的静态契约

### PCM 固定 framing

静态代码与测试一致：

- `PcmPacketizer.SAMPLE_RATE = 16_000`；
- `SAMPLES_PER_PACKET = 1_280`；
- `PACKET_BYTES = 2_560`（mono PCM16）；
- `PACKET_DURATION_MS = 80`；
- `MicrophoneCapture` 使用 16 kHz、mono、PCM16，并经 `PcmPacketizer` 输出；
- `AgentSocket.sendAudio()` 只接受 `2_560` bytes；
- `PcmPacketizerTest` 明确锁定 16k/2560/80ms。

未发现 640-byte/20ms 双包长兼容路径。

### token 传递与日志

- session JWT 仅由 `CloudAgentHandshake.subprotocols()` 形成 `translation.v1, translation.jwt.<JWT>`，并由 `AgentSocket` 写入 `Sec-WebSocket-Protocol`；start payload 使用同一个 grant 的 `sessionId/userId/installId`。
- Android 当前源码未发现 `Log.*`、`println` 或 `HttpLoggingInterceptor`；`CloudApi` 错误处理不回显 response token；未发现 token 被显式写入日志。
- access/refresh token 由 `KeystoreTokenStore` 加密持久化；translation session grant/JWT 仅保存在 ViewModel 内存字段。

以上是静态源码确认，不等同于代理、系统网络栈、崩溃采集或真机 logcat 的动态审计。

### 已有 exactly-once 正向覆盖

`TranslationSessionCoordinatorTest.endsOnceAndReportsOnlyMinimalAggregatedUsage` 对同一已登记 session 连续调用两次 `end()`，断言 usage request 和 end request 各一次；`usageFailureDoesNotBlockSessionTermination` 证明 usage 抛错时仍会尝试 end。该覆盖不消除 I-4 的 end-failure/scope-cancellation 缺口。

## Deferred real-device conditions（非本次代码缺陷判定）

下列条件必须留到受控真机/模拟器或集成环境验证，不能由本次只读审查宣称通过，也未据此新增 Critical/Important：

1. `AudioRecord` 在目标设备上是否真实支持 16 kHz mono PCM16、停止是否在 1 秒 join 内可靠释放麦克风。
2. `AudioTrack` 的 playback-head wrap/drain 在目标设备和蓝牙/扬声器路由上是否可靠，是否会永久停在 `STOPPING`。
3. Activity 后台、旋转/进程重建、权限弹窗和快速返回操作下的实际 callback 时序；I-3 已由代码竞态成立，真机仅用于复现与验证修复。
4. Cloud/Agent/Provider 真实网络下 end、usage、JWT refresh、Agent `Finished` 与 TTS 尾包的时序。
5. 真机 logcat、网络代理和崩溃收集系统的 token-free 动态检查。
6. 真实翻译、字幕/TTS 和服务端 session/usage 数据库最终状态；Task 8 才能验收。

## 建议的最小修复验证范围

Task 5 实现阶段至少应增加确定性测试：

- ViewModel/可注入 facade 测试：auto stop 只在所有 turn terminal 且 playback drain 后 close Cloud session；
- manual empty release 立即走同一幂等 Cloud close 路径；
- open 尚未返回时 cancel/ON_STOP/onCleared，迟到 grant 必须立即关闭且绝不能启动 socket/mic；
- end transport failure/scope cancellation 的明确策略，证明不会永久丢失关闭资格，同时保持 usage/end 不重复；
- lifecycle recreation 后旧代 callback 不得写入新状态。

在这些测试失败并完成最小修复前，不应把当前 Task 5 标记完成。
