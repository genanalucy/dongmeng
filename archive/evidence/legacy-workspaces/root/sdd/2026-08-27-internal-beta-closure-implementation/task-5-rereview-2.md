> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-rereview-2.md`；原始 SHA-256 `61f03a6f5cad65031fbf4c2b26e9d828ac16283a3b57e28b0ccdf193681cd434`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 Android 高风险独立复审（rereview 2）

## 范围与限制

只读复核 `[旧临时路径]` 的 commit `a1874c1` 相对 `da87c23`，并对照 `task-5-rereview-1.md`、`task-5-fix-round-1-report.md` 与 `review-da87c23..a1874c1.diff`。静态检查了 coordinator、两个 ViewModel、同步 Cloud transport、相关单元测试及未变化的 PCM/JWT/logging 路径。

未修改实现、未构建、未运行测试、设备或模拟器，未派发代理；实现报告中的既往 Gradle 成功记录不作为本次独立执行证据。

## 结论

**未发现 Critical；发现 1 个 Important。代码仍不批准为 Task 5 完成态。**

C-1 已从源码层关闭：create、usage、end 的同步 Cloud 调用均显式切到默认 `Dispatchers.IO`，且可注入 dispatcher。I-5 的核心“上层清空 grant 后仍由 coordinator 自动重试”也已成立；重试次数有限，同一 session 无并发重复 end，usage 最多启动一次，state 只在 end 成功后移除。

剩余阻断是：终态失败虽然写入 coordinator 的 `SharedFlow`，但两个生产 ViewModel 都没有订阅或向 UI/诊断层暴露该 flow，因此真实应用中的重试耗尽仍然不可观察。

## Important

### I-6. retry 耗尽事件只存在于私有 coordinator 内部，生产路径没有观察者；终态失败仍对应用静默

**证据**

- `TranslationSessionCoordinator` 暴露 `endFailures: SharedFlow<EndFailure>`，每次 end 失败调用 `tryEmit`，最终一次为 `willRetry=false`。
- 但 `FaceToFaceViewModel` 与 `InterpretationViewModel` 的 `cloudSessions` 都是私有字段；两者没有 collect `endFailures`，也没有把 terminal failure 映射到自身 `StateFlow`、用户错误、诊断事件或其他可达 observer。
- 仓库中除 coordinator 本身和单元测试外没有 `endFailures` 使用者。
- 新测试仅直接读取 `coordinator.endFailures.replayCache`；这证明内部 flow 收到了事件，不证明任何生产调用者能够观察它。
- ViewModel 会在调用 `end()` 前立即清空 `cloudGrant`。自动重试本身不依赖该 ID，已修复 round1 I-5；但三次均失败后，上层既无 grant，也无失败通知，不能告知用户、触发显式后续动作或留下可消费的诊断状态。

**影响**

短暂故障可由 coordinator 自动恢复；持续网络、认证刷新或服务故障耗尽三次后，Cloud session state 虽仍保留在 coordinator 内存中，却对生产应用静默。用户可能看到 UI 已结束，而服务端 session 仍 active，并阻塞下一 session；这不满足“terminal failure observable”。

**敏感性边界**

`EndFailure` 不含 JWT、access/refresh token、PCM 或字幕，只含 `sessionId`、attempt count 与 retry 标志；当前没有日志输出。因此未发现 credential logging 回归。不过 `sessionId` 是业务关联标识，若后续接入日志/遥测，应避免原样写入外部日志，或采用受控脱敏/内部错误码。

**所需修复方向**

让两个生产 ViewModel（或应用级 lifecycle owner）实际 collect terminal `willRetry=false` 事件，并以不含 token、最好不外泄原始 session ID 的状态/诊断信号暴露；同时增加生产接线测试，证明 retry 耗尽后 observer 收到一次终态事件，而成功重试不会产生终态失败。

## C-1 / I-5 精确复核

### C-1：已关闭（代码层）

- `open()`：`withContext(NonCancellable + ioDispatcher) { createTranslationSession() }`。
- usage：`withContext(NonCancellable + ioDispatcher) { createUsageRecord(...) }`。
- end：每次 attempt 均在 `withContext(NonCancellable + ioDispatcher)` 中调用同步 `endTranslationSession()`。
- 生产默认 `ioDispatcher = Dispatchers.IO`；两个 ViewModel 未覆盖该默认值，因此不会把同步 OkHttp Cloud usage/end 运行在 Main。
- `operationScope` 仍继承 owner dispatcher 用于状态协调和 callback，但同步 transport 均显式切换 IO；这与 round1 的 Main-thread 缺陷不同。

测试 `usageAndEndRunOnTheInjectedIoDispatcher` 用具名单线程 dispatcher 记录 fake usage/end 的线程，能可信锁定 dispatcher 注入与切换接线。它不是 Android/真实 OkHttp 测试，但结合生产默认值和明确 `withContext`，足以代码层关闭 C-1；真机网络行为仍属延期风险。

### I-5：自动 retry 核心已关闭；终态可观察性被 I-6 阻断

- `end()` 同步将 `endInFlight=true` 后启动 coordinator 自有 `operationScope`；ViewModel 随即清空 grant 不影响后续 attempt。
- coordinator 最多尝试 `maxEndAttempts`（生产默认 3），默认退避 250 ms、500 ms；不是无限 retry。
- `operationScope` 去掉 owner `Job` 并使用独立 `SupervisorJob`，所以 `viewModelScope` 在 `onCleared()` 被取消后，已启动的 create/end cleanup 不会随之消失。
- 同一 state 的 `endInFlight` 在整个 retry loop 期间保持 true；重复 `end(sessionId)` 直接返回，不会产生并发重复 end。
- 所有尝试耗尽后才恢复 `endInFlight=false`，state 保留，未来若仍持有 ID 的显式调用可再开启一组有限尝试；正常 ViewModel 已清空 ID，但自动三次尝试不依赖它。
- 唯一剩余缺陷是终态 flow 无生产 observer，见 I-6。

## I-4 状态机性质

- **bounded retry：满足。** attempt loop 为 `1..maxEndAttempts`，构造器要求值大于 0。
- **无并发重复 end：满足。** `endInFlight` 在锁内先置位，直到成功移除或整组失败后复位。
- **usage at-most-once：满足。** `usageStarted` 在锁内、调用前置 true；即使 usage transport 失败，后续 end 不会再次发送。这里保证的是最多一次，而非至少一次。
- **session removal only success：满足。** `sessions.remove(sessionId)` 只位于 `endTranslationSession()` 无异常返回的分支；失败耗尽仅复位 `endInFlight`。
- **terminal failure observable：生产层不满足。** coordinator 内部可测试，应用层无观察者，构成 I-6。
- **non-sensitive：当前事件不含 token/JWT/正文，且未记录日志；原始 session ID 仍应视为受控业务标识。**

## 取消与生命周期影响

- `OpenHandle.cancel()`、operation generation 和 phase/mode guard 仍阻止 cancel/`onCleared()` 后迟到 grant 启动 socket 或 microphone。
- 迟到 grant 会先 register，再走统一 `end()`；该 end 在独立 operation scope 中执行有限 IO retry，因此 ViewModel 清除不会取消 cleanup。
- 已开始的每个同步 Cloud attempt 使用 `NonCancellable + ioDispatcher`，不会因 owner lifecycle 中断到一半；attempt count 有界。进程被系统终止仍会丢失纯内存 state/retry，这属于明确的设备/进程级延期风险，而非本提交可证明解决的持久化保证。
- coordinator 的独立 `SupervisorJob` 没有显式 close；在本实现中任务均为有限 create/usage/end/retry 工作，未发现会因 ViewModel clear 启动无限 loop。但真实网络阻塞时的时长仍取决于 OkHttp timeout/网络栈，应动态验证。

## 原 I-1..I-4 最终状态

- **I-1 auto stop drain：代码批准。** `stopAuto()` 不立即无条件 close；只在 entries、playback 排空且 phase 为 `IDLE` 后 close。此次 diff 未回退该逻辑。
- **I-2 manual empty：代码批准。** discard transition 标记 close，ViewModel 走统一 end；此次 diff 未回退。
- **I-3 cancel/clear/late grant：socket/mic 隔离与 coordinator cleanup 代码批准。** generation/guard 仍成立，迟到 grant 的 cleanup 现在还获得自动有限 retry。真实 lifecycle 时序仍需设备验证。
- **I-4：除终态生产可观察性外，代码状态机已解决。** dispatcher、自动 retry、usage once、串行 end、成功后删除均成立；因 I-6，整体尚不能完全关闭。

## 测试可信度

新增测试对以下性质有直接价值：

1. usage/end 使用注入 dispatcher；
2. 第一次 end 失败后无需第二个生产 caller 即自动成功；
3. retry 次数耗尽且 usage 不重复；
4. 终态 event 在 coordinator replay cache 中存在。

局限：

- dispatcher 测试使用 JVM fake，不触发 Android Main-thread/OkHttp 约束；静态 `Dispatchers.IO` 接线弥补代码层证据，但不能替代真机。
- `endAutomaticallyRetriesAndRemovesItsStateOnlyAfterSuccess` 在 fake 设置 `ended` 后又调用一次 `end()`；该时刻 coroutine 可能尚未完成 map removal，重复调用也可能仅因 `endInFlight` 返回，因此该测试名中“removes state”没有被强力独立证明。源码分支仍明确只在成功后 remove。
- 没有并发调用压力测试，也没有 ViewModel/`onCleared()` 接线测试。
- terminal test 直接检查私有生产对象可访问的 flow cache，反而暴露了缺少真实 consumer 的问题。

这些覆盖缺口不单独提升为新的 Important；I-6 有直接生产接线证据。

## PCM / JWT / token logging

`da87c23..a1874c1` 只修改 coordinator 与其测试，未修改音频、Agent handshake、Cloud transport/token store：

- PCM 仍为 16 kHz、mono PCM16、1,280 samples、2,560 bytes、80 ms；
- JWT 仍仅通过 `Sec-WebSocket-Protocol: translation.v1, translation.jwt.<JWT>`，start 绑定字段未变；
- 未新增 `Log.*`、`println`、HTTP body logging 或 token 输出；
- `EndFailure` 不含 JWT/access/refresh token。

源码静态未见回归；真机 logcat、代理、崩溃收集仍未验证。

## 代码批准与设备延期风险

### Code approval

**不批准 `a1874c1` 作为 Task 5 完成态。** C-1 与 retry 主体已修复，但 terminal failure 没有生产 observer，I-4 的可观察失败要求仍未完整满足。

### Deferred device/integration risk

即使修复 I-6 并通过代码复审，下列仍只能在受控真机/模拟器/集成环境验收：

1. 真实 OkHttp Cloud usage/end/retry、401 refresh 与 timeout 时序；
2. 后台、旋转、导航、权限弹窗及进程终止时的 cleanup；
3. AudioRecord/AudioTrack、TTS drain 与音频路由；
4. 服务端 session 最终 ended、usage 最多一条、下一 session slot 释放；
5. 真机 logcat、代理和崩溃采集的 token-free 审计。

## 短结论

**不批准。C-1 已关闭，coordinator 自动有限 retry 已不依赖 ViewModel 保留 grant，且串行 end、usage at-most-once、成功后删除均成立；但 retry 耗尽事件无人订阅，生产终态失败仍静默，需修复 I-6。设备与真实网络验收继续明确延期。**
