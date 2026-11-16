> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/auto-face-mode/issues/04-auto-ui-history-acceptance.md`；原始 SHA-256 `f776612be50a6e17f183fc92117086e489d491bff0736a1276c4d22460a2c8d7`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 04: 自动模式界面、历史与真机验收

**What to build:** Debug 面对面页面向用户开放完整自动模式入口、状态与暂停/继续/停止控制。自动模式只有原文和译文均最终完成的 Turn 才按既有加密、本机保存与云端同步规则留存；完成 A↔B 双耳机真机验收，确认说话者不听自身译文。

**Blocked by:** 02 自动模式单段双向路由; 03 自动模式安全拒绝与生命周期.

**Status:** implemented (deterministic coverage); real-device validation pending

- [x] Debug 用户可从面对面界面启用、暂停、继续和停止自动模式，并看到正在识别与已接受方向；malformed/provider failure/empty final 以可恢复错误终止，不自动无限重启。
- [x] 自动模式仅将已完成 Turn 写入本机历史副本和云端同步 outbox；空、部分、失败和恢复时丢弃的片段均不留存。
- [x] Release 不暴露 Debug 专属引擎、声音或诊断控制，且现有手动面对面与同传行为保持回归测试覆盖。
- [ ] 真机以配置的两种语言双向轮流说话，验证字幕、目标耳机独占播放、说话者耳机静音、停止与历史同步。重叠检测未实现，不能验收“重叠语音静默”；候选外语言可能被 Azure 映射为 A/B。
