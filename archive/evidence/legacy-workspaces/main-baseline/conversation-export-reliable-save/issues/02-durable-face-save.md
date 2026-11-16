> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/02-durable-face-save.md`；原始 SHA-256 `27f54f95668b80e2c20a669911b70a60ada83c20bca8f12a0a1866aff24606e4`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 02: 对话和面对面可靠自动保存

**What to build:** 已完成内容进入可重读的本机历史副本，离开工作台不取消已接纳写入。

**Blocked by:** 01: 明确已完成Turn归属与回放边界

**Status:** ready-for-agent

- [ ] 稳定身份保证重复提交不重复历史
- [ ] 失败可恢复且写入不依赖页面作用域
- [ ] 非破坏迁移、账号撤销和删除优先具有实际数据库测试

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
