> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/face-to-face-regressions/issues/02-manual-release-playback-stall.md`；原始 SHA-256 `641466d87d4e87ea269f05d2feb4a9d45ff745566b4495666c96fc79bd375649`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 修复手动松开时播放卡顿与片段重播

Type: bug
Status: ready-for-agent
Priority: blocker

## 现象

面对面手动模式中，松开说话键时正在播放的翻译语音中途停顿约一秒，例如 `going to school` 听为 `goi… going to school`。

## 诊断结论（2026-09-10）

**主因（客户端，已修复）**：`manualRelease()` 在 `actionLock` 内执行 `applyTransition → runtime.stopCapture()`，而 `MicrophoneCapture.stop()` 含 `worker.join(1_000)`，最多阻塞 1 秒。播放线程 `verba-face-tts` 每写完一块 PCM 都要进入 `synchronized(actionLock)`（`playbackWorkFinished`），被 join 阻塞后 AudioTrack 缓冲（约 160ms）耗尽，词中间断。

**辅因（上游行为，待真机复验）**：服务端日志显示 8/9 个会话在 `finish_sent` 之后上游仍追加一批 `tts_audio`（补齐未稳定尾部）。松开瞬间的停顿 + 尾部续播在听感上接近"整句重播"。客户端无法对 PCM 去重；若修复停顿后仍听到字面重复，需在 agent 侧增加 TTS 字节数日志进一步定位。

**已排除**：客户端自身不会重复投递已消费 PCM（`claimPlaybackLocked` 逐块出队，无重入路径）；麦克风与听筒硬件冲突假设无证据。

## 修复

`applyTransition` 的 I/O 副作用（socket finish/cancel、stopCapture/startCapture）移出 `actionLock`，进入专用单线程 `verba-face-effects` 执行器，按决策顺序串行执行；`endCloudSession`、定时器与状态发布保留在锁内。播放泵不再被采集 teardown 阻塞。

## 验证

- 回归测试 `manualReleaseDoesNotStarveOngoingTtsPlayback`：先红（stopCapture 阻塞期间第二块 TTS 250ms 内无法播放），修复后绿（第二块即时播放、finish 最终送达）。
- `FaceToFaceViewModelTest` 全部通过；全量 `testDebugUnitTest` + `lintDebug` 通过。
- 待真机验收：松开瞬间不再有约 1 秒卡顿；若仍听到字面重复句，转上游 TTS 字节日志排查。

## 验收

- 新增的回归测试先失败、修复后通过。（已满足）
- 原始触发序列在真机上反复执行不再产生卡顿、重复或缺字。（待真机）
- 无调试日志遗留；不改变左右物理播放路由。（已满足）
- 手动、连续、取消和会话结束路径仍通过既有 coordinator/播放测试。（已满足）
