> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/auto-face-mode/continuous-azure-state-model.md`；原始 SHA-256 `e037b538ac0955453ad4043b7ff7e3d71a7edc9fbb0ad77dd2840104dab5fc36`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Azure 连续自动模式最小状态模型

- 一个 AUTO Azure 会话拥有一个长生命周期 transport socket；`start` 时建立一次，只有 pause/stop/cancel/故障时 `finish`/`close`，不会在 phrase final 后重连。
- transport 可产生顺序递增的 logical segment。每个 final segment 必须携带 `segmentId`、检测到的 source language 与相反 `targetLanguage`；字幕、TTS 与 history 都按该标识原子归属，不从文本推断语言。
- `translation.hypothesis` 只发送 source/translation partial，绝不触发 TTS；只有完整、有效且候选内的 final source+opposite-target translation 才合成并入对应耳机队列。
- 对每个 final：检测语言先于 source final、translation final、TTS；其 target voice 从该 final 的 target language 选择。第三语言可能被 Azure 约束映射到 A/B；没有可用置信度，重叠说话不支持。空/无效 final 或无法关联的结果 fail closed，不生成音频或 history。
- Android 以 transport socket 维持 capture，以 logical segments 创建/完成可保存 turn；停止时 transport 结束，已完成 turn 保留。pause 会 finish 当前 socket；随后 resume 安全地建立一个新 socket（一个 pause/resume 周期一次），但正常 final 绝不重连。stop/cancel 不创建 replacement socket。
