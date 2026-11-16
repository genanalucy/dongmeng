> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-final-review.md`；原始 SHA-256 `d6b8d3a39c3131b215fe013d43eb158216893401e7863801857ce232c11ff20b`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 Android 最终独立高风险审查

## 范围与限制

只读复核 `[旧临时路径]` 当前 Task 5 收口状态，并对照：

- `task-5-review.md`；
- `task-5-rereview-1.md`、`task-5-rereview-2.md`；
- `task-5-report.md`、`task-5-fix-round-1-report.md`、`task-5-fix-round-2-report.md`；
- 最终变更包 `review-a1874c1..2ccf115.diff`。

遵照约束，本次未修改实现代码、未构建、未运行测试、未运行设备或模拟器、未派发代理。实现报告中的既往 Gradle 成功记录仅作为交付背景，不作为本次独立执行证据。

## 结论

**未发现新的 Critical 或 Important；代码层批准 Task 5。**

原始 I-1..I-4、后续 C-1/I-5/I-6 均已在最终源码/变更链中形成相互一致的收口。最终提交没有改动 PCM、JWT handshake 或 logging 路径。真实设备、真实网络、服务端最终状态及动态凭据泄漏审计仍明确延期，不能由本次静态批准替代。

## 缺陷逐项最终状态

### I-1：automatic stop 等待 turn/TTS drain — 已关闭

- `stopAuto()` 不再立即结束 Cloud session。
- `FaceToFaceCoordinator.canCloseCloudSession()` 仅在 phase 回到 `IDLE`、turn entries 为空且 playback 已排空时允许关闭。
- turn `Finished`/failure 与 playback/drain completion 均会重新检查关闭资格。
- 已有确定性 coordinator 测试覆盖多 turn terminal 与多次 drain 完成前不得关闭。

静态控制流未发现 drain 前提前调用 Cloud end 的回归。

### I-2：manual empty 关闭已创建 grant — 已关闭

空输入 discard transition 明确携带 close 信号，ViewModel 经统一幂等 `endCloudSession()` 路径关闭 grant；socket cancel 与 Cloud close 不再混为同一动作。重复触发因 grant 已清空而 no-op，不会产生重复 end。

### I-3：cancel/clear 后迟到 grant — 已关闭（代码层）

- pending `OpenHandle.cancel()`、`operationGeneration` 以及 mode/phase guard 阻止迟到 grant 进入 socket/microphone 创建分支。
- cancel、`onCleared()` 或操作代次变化后返回的 grant 仅登记并进入 Cloud cleanup。
- Face-to-Face stale socket/TTS callback 仍受 coordinator entry 与 playback generation 隔离。
- cleanup 使用 coordinator 自有的有限 operation scope，不依赖已被取消的 ViewModel owner job。

未发现迟到 callback 重新开启录音、污染新 grant 或错误恢复旧生命周期状态的直接路径。进程被系统终止时纯内存 cleanup 丢失仍属于设备/进程级延期风险。

### C-1：同步 Cloud 网络调用运行于 IO — 已关闭

`createTranslationSession`、`createUsageRecord` 与 `endTranslationSession` 的同步 transport 均显式切换到可注入 dispatcher；生产默认 `Dispatchers.IO`。状态协调/callback 可保留 owner dispatcher，但同步 OkHttp 调用不再落在 ViewModel Main dispatcher。

### I-4 / I-5：bounded retry、usage once、无重复 end — 已关闭

- end retry 由 coordinator 自行拥有，不依赖 ViewModel 在调用后继续保留 session ID。
- retry 次数有界；生产默认最多三次并带有限退避，不存在无限循环。
- `endInFlight` 在整个 retry group 内保持占用，阻止同一 session 并发或重复 end group。
- `usageStarted` 在锁内先置位，usage 最多发起一次；end retry 不重复 usage。
- session state 仅在远端 end 成功后删除；失败耗尽保留状态与后续资格。
- usage/end 同步网络调用均在 IO dispatcher，修复后的 retry 是生产可达路径，而非仅测试人工二次调用。

这里的承诺是 usage **at-most-once**，不是进程崩溃条件下的至少一次持久交付；后者不在本任务的内存协调器保证范围。

### I-6：终态失败有真实 ViewModel consumer，且安全映射 — 已关闭

最终提交 `2ccf115` 增加了生产接线：

- `FaceToFaceViewModel` 与 `InterpretationViewModel` 均在 `init` 中使用各自 `viewModelScope` collect `cloudSessions.endFailures`；不再只有 coordinator 测试读取 replay cache。
- 仅 `willRetry=false` 的 retry-exhausted 事件更新 ViewModel 暴露的只读 `StateFlow`；可重试事件不污染 UI 状态。
- 映射结果固定为 `CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED`。
- ViewModel 状态不包含 `sessionId`、JWT、access/refresh token、transport exception、response body 或字幕/音频内容。
- observer 随 ViewModel scope 取消，不改变 retry、socket 或 microphone 生命周期。

相关测试分别覆盖通用 observer、Face-to-Face/Interpretation 生产 binding、retryable failure 忽略，以及一次失败后重试成功不产生 terminal state。

## 新增高风险检查

### Flow loss / collector race

未发现可提升为 Critical/Important 的事件丢失或 collector 启动竞态：collector 在两个 ViewModel 的 `init` 中建立，生命周期绑定明确；coordinator 的 failure flow 具有此前复审已验证的 replay 语义，终态事件不会仅依赖“emit 时 collector 恰好已运行”。测试在 emit 前显式 `runCurrent()` 主要消除测试调度不确定性，并不构成生产端必须手动启动 collector 的前置条件。

### Stale UI state

`cloudSessionCloseFailure` 是 sticky `StateFlow`，终态失败后保持固定错误码，当前变更没有 clear/ack API。此行为会保留最后一次不可恢复关闭失败，作为诊断状态是合理的 fail-visible 语义；它不携带旧 session identity，也不会驱动 socket、mic、grant 或 retry 状态机，因此本次不判为 Important。若产品后续将其直接呈现为一次性 snackbar，应在 UI 层采用明确的消费/清除机制，避免下一次页面交互重复展示；这是 UI 产品接线要求，不是当前会话闭环阻断。

### Lifecycle correctness

- collectors 由 `viewModelScope` 管理，ViewModel clear 后停止观察；
- 已经启动的 Cloud close retry 由 coordinator 的有限独立 scope 收口，不因 observer 取消而中断；
- observer 只写安全诊断状态，不回调 session 操作，不会引入重入、重复 end 或恢复麦克风；
- 未发现最终 diff 改变原 generation、phase/mode、drain 或 playback lifecycle guard。

## PCM / JWT / logging 不变

最终 diff 仅增加 Cloud close failure observer/bindings、两个 ViewModel 的安全状态接线及测试，未修改相关协议与传输实现：

- PCM 仍为 16 kHz、mono PCM16、1,280 samples、2,560 bytes、80 ms；
- JWT 仍通过 `Sec-WebSocket-Protocol` 的 `translation.v1, translation.jwt.<JWT>` 传递，start payload 继续使用同一 grant 的绑定字段；
- 未新增 `Log.*`、`println`、HTTP body logging 或 token/exception/session payload 输出；
- 新公开状态只有固定 enum，不泄露 session ID、token 或 raw exception。

以上仅为静态源码结论；系统网络栈、代理、logcat 与崩溃收集的动态行为未验证。

## 代码批准与延期风险边界

### Code approval

**批准当前 Task 5 代码状态。Critical=0，Important=0。**

批准含义限于静态实现与既往确定性测试覆盖所证明的代码契约：auto drain、manual empty close、late-grant cancel/cleanup、IO transport、bounded coordinator retry、usage at-most-once/no concurrent duplicate end，以及安全且真实可达的 ViewModel terminal-failure consumer。

### Deferred real-device / integration risks

以下仍必须在受控设备、模拟器或集成环境验证，不包含在本次代码批准中：

1. `AudioRecord` 16 kHz mono PCM16 支持、停止/join 与麦克风实际释放；
2. `AudioTrack` playback-head、TTS drain、扬声器/蓝牙切换及永久停留 `STOPPING` 的可能性；
3. 后台、旋转、导航、权限弹窗、快速返回与系统进程终止下的真实 callback 时序；
4. 真实 OkHttp timeout、401 refresh、Cloud end/usage retry、Agent `Finished` 与 TTS 尾包时序；
5. 服务端 session 最终进入 ended、usage 不重复，以及下一 session slot 确实释放；
6. 真机 logcat、网络代理、系统网络栈和崩溃采集的 token-free 动态审计；
7. retry 尚未完成时进程被杀会丢失纯内存 state；当前没有持久队列，此限制需在产品风险模型中接受或另立任务。

## 短结论

**代码批准：原 I-1..I-4 与 C-1/I-5/I-6 均已关闭，未发现新的 Critical/Important；真实音频、生命周期、网络、服务端最终状态和动态 token 审计继续延期到受控设备/集成验证。**
