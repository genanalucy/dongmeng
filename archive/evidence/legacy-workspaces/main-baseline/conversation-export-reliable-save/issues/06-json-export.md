> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/06-json-export.md`；原始 SHA-256 `925e10528d96d636ca171e644f8b7935ccc891940ea42037321f47632f4f6980`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 06: 共享快照JSON导出

**What to build:** JSON与TXT使用相同内容范围，包含版本及真实元数据。

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] Unicode/换行/引号/emoji可解析不丢字符
- [ ] 未知时间null，标明完成状态，不导出密钥或内部凭据
- [ ] 共享文件生命周期和账号保护

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
