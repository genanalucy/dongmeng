> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/issues/05-txt-export.md`；原始 SHA-256 `8a93dc2c0cfe19bce8f39818eb84650cce14f2e65138161aa62de4eb1bb8d487`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 05: 三个工作台TXT文件导出

**What to build:** 当前内容冻结快照，通过系统文件选择器输出TXT。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 空白过滤，重复/顺序/语言及未完成标记保留
- [ ] 取消/写入关闭失败/账号切换反馈准确
- [ ] 不申请全存储权限，不依赖历史保存成功

## Implementation result

Implemented in cd920a1; release record in docs/deployment/download-center.md (Debug v30). Local441 tests/lint/build and Room+Compose cloud regression passed. Pending/in-memory writes are NOT promised durable before DB commit; external document provider and real user audio/cross-device acceptance remain unverified.
