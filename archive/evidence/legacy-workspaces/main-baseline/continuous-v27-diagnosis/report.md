> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/continuous-v27-diagnosis/report.md`；原始 SHA-256 `de682288f9297693448aa6f4a1c59c9952f99c9db9f129200ffa36a10b8399b1`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Debug v27 连续对话诊断

2026-11-13。当前Android运行时代码与发布46fa5cf一致；未操作手机、生产或收费翻译。诊断对象为对话页AUTO连续模式，不是独立实时同传页。

现有Coordinator/ViewModel/ContinuousAuto/Guidance测试定向通过。新增两个诊断用例（仅测试文件未提交）运行后2/2失败：

```bash
cd android
./gradlew :app:testDebugUnitTest --tests 'com.verba.interpretation.ui.FaceToFaceViewModelTest.diagnostic*'
```

1. 连续模式一句原译文final、PCM、Finished、播放排空后预期继续LISTENING，实际IDLE。VM.startAuto创建普通每句transport，没有continuousSession，也未在完成后创建下一轮；coordinator.completeAutoSegmentAfterFinalPair封口停止采集。已用fake runtime/socket确定性复现应用状态行为，不代表真实AST每句事件时序已验证。
2. effectsExecutor延后执行开始任务，用户start→pause→resume后排队任务执行时仅查当前captureActive，旧start也满足新状态，fake runtime记录captureStarts=2而非1。它中间可能被stop分开，不应声称必定同一时刻两次录音或必定AlreadyRunning；已确认过期任务导致额外启停，真实症状待设备/线程时序验证。

两项静态入口问题：AUTO左麦release/cancel无条件清除待处理权限意图，首次授权可能不开始，而中间开始不走此清除；disabled右麦仍有可执行semantics OnClick。尚未新增执行验证，不将静态入口当确定真机根因。

协议边界：固定source/target；火山candidateLanguages拒绝，无双向自动检测。现有official测试先收Finish后发TTS，不能据此断定真实供应商必须Finish。当前映射未转发句级TTS边界；需fake上游两句不封口透传/正常关闭无finished测试及必要的官方协议核实。

建议最小修复先覆盖逐句结束后自动续接（仅AUTO且无用户pause/stop/cancel、保持默认方向）、effects启动任务所有权/代次验证，权限入口和禁用语义门槛；不要假设给continuousSession=true就兼容火山。新套接字成本/延迟与句边界/回声要验证，不混入双麦或自动识别。生产/OTA/正式版未动，因有意红色回归工作树只留测试差异，不提交或宣称修复完成。
