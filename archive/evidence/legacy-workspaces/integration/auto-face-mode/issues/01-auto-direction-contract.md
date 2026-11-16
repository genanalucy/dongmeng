> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/auto-face-mode/issues/01-auto-direction-contract.md`；原始 SHA-256 `b6fa80693688b915b983c06c1723818f080370810001c938c8c5b724216b8d63`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 01: 自动模式协议与方向契约

**What to build:** 面对面自动模式只由 Azure 以用户选定的恰好两种会话语言启动。Azure Unified Speech v2 将候选 locale 写入 `speech.context.languageId`，并返回最终检测源语言；缺失、重复、未知或非候选结果安全拒绝，不生成可播放结果。直连协议没有可用置信度，故不声称低置信过滤；第三语言可能被 Azure 映射为候选 A/B，这是接受的方案 1 风险。

**Blocked by:** None (can start immediately).

**Status:** implemented

- [x] 自动会话在端到端协议中表达准确的两个候选语言，并保持既有手动会话协议兼容。
- [x] 服务器拒绝非 Azure、非两个、重复和不支持的候选；Azure 最终 LID 在字幕/TTS 前透传 `detected_language`。
- [x] Azure 单元测试严查 v2 `speech.context` 使用未包裹的 context 对象、legacy `speech.config` 保留 wrapper、双翻译目标、最终 LID 和排序；不虚构协议未提供的低置信度结果。
