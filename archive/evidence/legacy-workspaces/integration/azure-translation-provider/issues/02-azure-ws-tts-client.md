> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/issues/02-azure-ws-tts-client.md`；原始 SHA-256 `8d1b3f7b88df19c5ef3a742bb0f2d9a83f1035903e962e12ff0ff1ec6b654abb`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 02: Azure WS+TTS 客户端

**What to build:** 完整的 Azure Speech Translation 上游客户端：WebSocket 拨号（language/to 查询参数、认证头）、连接后发送 speech.config、音频帧带 2 字节大端长度前缀、`translation.hypothesis/result` 映射为 source/translation partial/final 事件、turn 边界管理、speech.event 遥测应答、Finish 幂等且在话语终态（turn.end 或无活动话语）发出 finished。TTS 用 Azure REST 按句合成（目标语言声音、raw-16khz-16bit-mono-pcm），经 tts_audio 事件下发；文本、合成、finished 在单一通道严格保序；上游失败透传 AZURE_SESSION_FAILED / AZURE_TTS_FAILED 错误码。

**Blocked by:** 01（协议与引擎路由）

**Status:** ready-for-agent

- [ ] 假 Azure WS 上游单测：拨号 URL 参数、认证头、speech.config 先于音频
- [ ] 音频帧 2 字节大端前缀与载荷长度一致
- [ ] hypothesis/result 映射 source/translation partial/final，事件顺序正确
- [ ] translation_final 触发假 TTS，tts_audio 在文本事件后保序到达
- [ ] Finish 幂等；话语活跃时等 turn.end、空闲时立即 finished；finished 恰发一次且在 TTS 排空后
- [ ] TTS 失败 → AZURE_TTS_FAILED 并终止；WS 断连 → AZURE_SESSION_FAILED
- [ ] 零配置/不支持语言在 Start 即拒绝（fail closed）
- [ ] 01 的假 Azure 实现替换为本客户端，路由测试保持全绿
