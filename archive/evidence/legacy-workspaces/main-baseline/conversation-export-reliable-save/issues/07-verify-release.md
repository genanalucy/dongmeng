> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/07-verify-release.md`；原始 SHA-256 `f8c20fd189bb89ff91fb6edb640f94390d77b5cde3e6347316a20ca3a1cab36f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 07: 跨功能验收与Debug发布

**What to build:** 验证翻译、保存、历史重读和导出并发布Debug更新。

**Blocked by:** 04, 06

**Status:** ready-for-agent

- [ ] 单测lint构建及相关数据库/设备回归通过
- [ ] 列出真实音频/跨设备未验事项
- [ ] 官网和OTA统一发布，正式APK保持不变

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
