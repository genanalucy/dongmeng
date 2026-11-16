> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/continuous-v27-diagnosis/v28-fix-notes.md`；原始 SHA-256 `c7d9b402db0c7f8fbb9ae5f61514775ec429fdca2253cbf3a27b80171dc026e5`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# v28 输入/连续回归

源码 064b789。正常PTT松手不再依赖hasSourceText，保留未返回字幕的turn，stop→finish并等待最终结果；pointer cancel继续丢弃。Coordinator/VM实际公开接口复现过red，随后green。Ready前PCM缓存/Finish排序保留AgentSocket现有实现，本次未改Socket，未新增真实WebSocket端到端验证。

AUTO采用每句独立transport，final pair封口停止采集，Finished+AudioTrack drain完成后复用同一Cloud grant开启下一句；autoRunning意图在pause/stop/cancel/failure/切视图时清除。不改为continuousSession=true，不冒充双向检测。逆序final pair也可封口。无完整final pair而上游提前finish明确error，不循环重连。历史在Finished落盘完整final pair。

captureGeneration绑定录音启动任务、PCM/level/error回调和start返回值；动作锁内检查代次与投递，避免pause/resume TOCTOU。start/stop仍在effectsExecutor避免慢stop饿死TTS。

本地405单测通过，lint0error/48warning，Debug和AndroidTest包构建通过；当前无ADB设备，无真机录音，无收费模型测试。本次未验证首字幕延迟和真机声音效果。云端模拟器结果待跟进。Release APK继续复用正式v21，不从测试分支重建。
