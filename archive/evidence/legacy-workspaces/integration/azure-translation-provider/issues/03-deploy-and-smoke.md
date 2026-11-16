> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/issues/03-deploy-and-smoke.md`；原始 SHA-256 `57595a47df106bb1fdae3b3c9870d8c0abec26db908fee0052fd9aba2de38030`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 03: 部署与真实冒烟

**What to build:** 将双引擎 agent 部署到生产服务器（默认引擎仍为火山，存量会话零变化），随后用服务器上的会话测试 harness 对真实 Azure 跑三个语言对的冒烟：中→越、英→越、中→英，验证字幕事件流与 TTS PCM 输出正常。自动面对面另须验证每个 final pair 后 Android 停止 capture 并 Finish 当前 socket，收到 finished 后以同一 cloud grant 创建下一 Azure universal v2 AtStart-LID socket；切段窗口丢音，旧段 TTS 可继续播放。英越质量由用户在此阶段真机初验。

**Blocked by:** 02（Azure WS+TTS 客户端）

**Status:** ready-for-agent

- [ ] agent 二进制构建并部署到生产服务器，translator-agent.env 已含 AZURE_SPEECH_KEY/REGION（已就位）
- [ ] 部署后默认（不带 provider）会话走火山，回归正常
- [ ] harness 对真实 Azure 跑通中→越：source/translation 字幕 + tts_audio PCM
- [ ] harness 对真实 Azure 跑通英→越：同上
- [ ] harness 对真实 Azure 跑通中→英：同上
- [ ] 429 并发受限场景返回明确错误码（可用第二路并发会话触发）
- [ ] 服务日志能区分 provider（不输出密钥）
- [ ] 真实 Azure AUTO 连续两段交替说话：每段均为独立 `/speech/universal/v2` + `speech.context.languageId.mode=DetectAtAudioStart` session，检测和反向耳路由按段变化；不能复用一个 Continuous session
- [ ] 候选语言严格为当前面对面两语言；第三语言检测/候选必须被 agent 拒绝，避免错误路由
