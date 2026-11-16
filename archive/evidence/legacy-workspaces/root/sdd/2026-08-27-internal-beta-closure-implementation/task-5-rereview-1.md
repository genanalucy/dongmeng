> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-rereview-1.md`；原始 SHA-256 `8e4b8f20eb2fffc66c32c2b4ec635c66ec75fb1d7c5df4b542a55999bf1eed81`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 Android 高风险独立复审（rereview 1）

## 范围与限制

只读审查：

- commit range `ae6e3b1..da87c23`（`00ebf68`、`da87c23`）；
- 初审 `task-5-review.md`；
- 实现报告 `task-5-report.md`；
- `review-ae6e3b1..da87c23.diff`；
- `[旧临时路径]` 中对应 Android 生产代码与单元测试。

未修改实现代码，未构建，未运行测试、设备或模拟器，未派发代理。本报告不把实现报告中既往 Gradle 成功记录当成本次独立运行证据。

## 结论

**代码不批准。发现 1 个 Critical、1 个 Important。**

修复已经建立了正确的 drain/generation/幂等状态机方向，但生产环境的 Cloud `usage/end` 被调度到 Android Main dispatcher，真实同步 HTTP 调用会失败；同时所谓 end retry 只提供了一个已被上层丢失 session ID 的手动入口，不构成实际可达的失败恢复。因此 I-4 未解决，并使 I-1、I-2、I-3 的“最终关闭 grant”部分无法成立。

## Critical

### C-1. `usage` 与 `end` 的同步 HTTP 被直接运行在 `viewModelScope` 的 Main dispatcher；生产端会吞掉失败并遗留 active session

**证据**

- `android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt`
  - `operationScope` 复制 `scope.coroutineContext`，仅替换 `Job`；两个 ViewModel 传入的是 `viewModelScope`，所以 dispatcher 仍是 Android Main。
  - `open()` 正确用 `withContext(NonCancellable + Dispatchers.IO)` 执行 `createTranslationSession()`。
  - 但 `end()` 的 `operationScope.launch` 内直接调用 `cloud.createUsageRecord(usage)` 和 `cloud.endTranslationSession(sessionId)`，没有 `Dispatchers.IO`。
- `android/app/src/main/java/com/verba/interpretation/cloud/CloudApi.kt`
  - 两个 service 方法最终调用同步 `OkHttpClient.newCall(...).execute()`。
- Android 上该同步网络调用不能在 Main thread 执行。异常又分别被 `runCatching` 吞掉：usage 静默失败；end 将 `endInFlight` 恢复为 `false`，但不会通知 ViewModel，也不会自动重试。
- 新增单测使用纯内存 `FakeService` 与 `runBlocking`，不会触发 Android Main-thread 网络约束，因此测试通过不能证明生产路径可用。

**影响**

- 正常 stop、manual empty、cancel、`onCleared()`、迟到 grant cleanup 的真实 Cloud end 都可能一次也未成功。
- 服务端 session 保持 active，可能阻塞下一次创建 session；usage 也不会可靠提交。
- 这是整个 Android 会话闭环的系统性失败，不只是测试覆盖不足。

**所需修复方向**

在受控 IO dispatcher 上执行两个同步 Cloud 调用，并增加能区分/验证 dispatcher 的测试；不能只依赖 JVM fake service。

## Important

### I-5. end failure 的“retry”在实际 ViewModel/迟到-grant 路径不可达；一次失败后仍没有最终关闭机制

**证据**

- `TranslationSessionCoordinator.end()` 在失败后保留 `SessionState`，仅允许未来再次显式调用同一 `sessionId`；它没有受限自动重试、回调、持久队列或 revoke fallback。
- `FaceToFaceViewModel.endCloudSession()` 与 `InterpretationViewModel.endCloudSession()` 都在调用 `cloudSessions.end(sessionId)` 前后立即把 `cloudGrant` 置空。end 异步失败后，上层已不再持有该 ID，也不会收到失败信号，因此没有第二次显式调用来源。
- cancelled late grant 只在 `open()` 中调用一次 `end(grant.sessionId)`；失败后 grant 不交付 UI，之后同样没有任何调用者能再次提供该 ID。
- `endTransportFailureRetainsEndEligibilityUntilASingleRetrySucceeds` 由测试本身保存 session ID、等待第一次失败、再人工调用第二次 `end()`；这不是任一生产调用路径的真实行为。
- `endInFlight` 确实在第一次调用同步置位，可阻止并发重复 end；`usageStarted` 也确保 usage 最多启动一次。但这只满足“无并发重复/usage 一次”，不满足“失败后实际可恢复”。

**影响**

即使修复 C-1 的 dispatcher，任意真实 transport/401 refresh/短暂网络失败仍可能永久遗留 active Cloud session。原 I-4 的核心问题仍存在。

## 原 I-1..I-4 逐项复核

### I-1 自动 stop drain：控制流已解决；最终 Cloud close 仍被 C-1 阻断

代码层时序修复成立：

- `stopAuto()` 不再立即无条件 end；
- `FaceToFaceCoordinator.canCloseCloudSession()` 仅在 `IDLE`、entries 空且无 playback 时为 true；
- `Finished`、session failure 和每次 playback/drain 完成后都会调用 `closeCloudSessionIfDrained()`；
- pure coordinator 测试覆盖两个 turn terminal 与两个 drain 后才具备 close 资格。

未发现新的“drain 前调用 end”路径。**原提前关闭问题的状态机/接线已修复**，但真实 end 因 C-1 不能据此验收为闭环成功。

### I-2 manual empty closes grant：触发路径已解决；真实关闭仍被 C-1 阻断

`endManualInput()` 对 discard 返回 `closeCloudSession = true`，`applyTransition()` 调用统一 end 路径；`manualRelease()` 还会在存在 `cancelSessions` 时再次调用（第二次因 `cloudGrant` 已清空而 no-op）。因此“空输入触发 close”的代码意图已落实。

但没有 ViewModel/facade 测试证明已创建 grant 的实际 close 调用，且 C-1 使真实 HTTP end 失败。故只能批准触发逻辑，不能批准端到端关闭结果。

### I-3 cancel/clear/mode-phase 后迟到 grant：socket/mic 隔离基本解决；grant 最终关闭未解决

静态确认：

- `OpenHandle.cancel()`、`operationGeneration` 与 callback 内 generation/phase/mode guard 可阻止取消后 grant 交给创建 socket/microphone 的分支；
- Face-to-Face 所有相关 guard 与创建动作在 `actionLock` 内；
- `onCleared()` 调用 `cancel()`；coordinator 使用脱离 owner Job 的 operation scope，并让 in-flight create 完成后走迟到 grant end，避免 `viewModelScope` 清除直接丢失结果；
- Face-to-Face 的旧 socket callbacks 由 `containsTurn`/coordinator entry 检查丢弃；cancel 后 playback generation 也失效。

新增测试只覆盖 coordinator-level `OpenHandle.cancel()` 后不交付 `onGranted`，没有覆盖两个真实 ViewModel、`onCleared()`、mode/phase 变化、socket/microphone 不启动以及旧 callback 不改写 UI。尽管如此，静态路径未发现 cancel/onCleared 后迟到 grant 启动 socket/mic 的直接缺陷。

但是迟到 grant 的 cleanup 最终依赖 C-1/I-5：end 会在 Main thread 失败且没有可达 retry。因此 **“不能启动 socket/mic”可代码批准，“grant 必须关闭”不可批准**。

### I-4 end failure retry、usage once、无并发重复 end：未解决

- `usageStarted`：静态满足最多启动一次。
- `endInFlight`：静态满足同一 session 不并发执行重复 end。
- end 成功后移除 state、失败后恢复资格：内存状态机本身正确。
- 实际 transport：C-1 导致生产调用 dispatcher 错误。
- 实际 retry：I-5 表明失败资格虽保留，但没有生产调用者可触发第二次尝试。

因此原 I-4 不能关闭。

## callback threading、onCleared 与 stale callback 结论

- grant callback 继承 `viewModelScope` 的 Main dispatcher；两 ViewModel callback 内也使用 `actionLock`。create 本身在 IO，符合 UI callback 串行化预期。
- 问题恰恰是同一 scope 中的 usage/end 没有切回 IO。
- `onCleared()` 均走 `cancel()`；Face-to-Face 还使 playback generation 失效并关闭 executor。迟到 create 不因 owner Job 取消而消失，会进入 cleanup 分支。
- Face-to-Face stale socket/TTS callbacks 在 entries 清空后不会恢复 capture 或创建新 socket。Interpretation 的旧 subtitle/finished callback 可能继续更新保留的旧 turn 展示字段，但从所审代码未见其在 cancel 后重新开启 socket/mic或关闭新 grant的路径；未提升为 Important。
- 缺少真实 ViewModel/lifecycle deterministic tests，尤其是 `onCleared()` 与旧 callback 对新一代操作的隔离；这是高风险覆盖缺口，但本报告不把“缺测试”单独重复计为另一个缺陷。

## 固定 PCM / token-free logging 契约

本提交范围未修改这些协议路径；静态复核未发现回归：

- PCM：`16_000 Hz`、mono PCM16、`1_280` samples、`2_560` bytes、`80 ms`；`MicrophoneCapture` 经 `PcmPacketizer` 输出，`AgentSocket.sendAudio()` 只接受 `2_560` bytes。
- JWT：仅通过 `Sec-WebSocket-Protocol` 的 `translation.v1, translation.jwt.<JWT>` 传递；start payload 使用同一 grant 的 session/user/install 绑定字段。
- 审阅到的 Android transport/protocol 代码未显式打印 token，未使用 HTTP body logging。

这只是源码与 diff 的静态确认。真机 logcat、代理、崩溃采集以及系统网络栈是否泄漏 token 仍需动态审计。

## 代码批准与真机验证边界

### Code approval

**不批准 commit range `ae6e3b1..da87c23` 作为 Task 5 完成态。** 必须先修复 C-1，并让 I-5 的失败恢复成为生产可达路径，再以真实 ViewModel/dispatcher 测试证明。

### Deferred real-device verification

即使后续代码复审通过，以下仍明确延期到受控真机/模拟器/集成环境，不能由本次复审宣称通过：

1. `AudioRecord` 16 kHz mono PCM16 支持与停止释放；
2. `AudioTrack` drain、playback-head、蓝牙/扬声器路由；
3. 后台、旋转、导航、权限弹窗的真实生命周期时序；
4. Cloud/Agent 真实网络中的 end/usage/JWT refresh/Finished/TTS 尾包时序；
5. 真机 logcat、代理和崩溃采集的 token-free 审计；
6. 服务端 session 最终 ended、usage 最多一条，以及下一会话 slot 已释放。

## 短结论

**不批准。I-1/I-2 的触发与 drain 时序、I-3 的 socket/mic generation guard 已有实质修复；但 Cloud usage/end 在 Main thread 执行是 Critical，失败后的 retry 又没有生产可达入口，故 I-4 仍未解决，所有“grant 已最终关闭”的声明均不能成立。**
