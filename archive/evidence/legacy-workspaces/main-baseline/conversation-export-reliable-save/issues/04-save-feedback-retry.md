> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/04-save-feedback-retry.md`；原始 SHA-256 `699030aac3ddee4b050d99508c775087752eba873d4c311dab4eb06034a37a6a`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 04: 主动保存与准确状态反馈

**What to build:** 三个工作台提供保存到历史、准确状态及失败重试。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 重复点击不重复记录，失败项可重试
- [ ] 保存中/本机成功/失败独立于云同步
- [ ] 空内容有说明，旧会话状态不覆盖新会话

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
