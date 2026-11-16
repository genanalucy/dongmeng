> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/01-completion-boundary.md`；原始 SHA-256 `8528e0d1323b185c455330f7b0a6434b4ad9829710abe0933feac324f35b0ef1`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 01: 明确已完成Turn归属与回放边界

**What to build:** 离线验证完成事件归属，建立现有行为不变的可测试边界。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 乱序、多段与重复事件不按文本或数组下标猜配对
- [ ] 记录有证据的完成边界及不足，必要字段向后兼容
- [ ] 单测可控外部运行时，不发真实计费请求

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
