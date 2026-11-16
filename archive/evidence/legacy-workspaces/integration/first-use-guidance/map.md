> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/first-use-guidance/map.md`；原始 SHA-256 `6663fac71efc2104dfa11820c8640925a1671747808a7b7aa12298a0b46b3fba`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 首次使用指南执行图

Status: ready-for-agent

## 任务顺序

1. `01-onboarding-state-and-persistence`：建立状态与本机持久化。
2. `02-highlight-overlay-and-accessibility`：依赖 01，实现高亮层与语义。
3. `03-guide-product-actions`：依赖 02，接入真实产品功能。
4. `04-replay-safety-and-verification`：依赖 03，提供重看入口和完整验收。

## 决策

- 不修改 Room schema、云端 API、OTA 合约或底部导航。
- 只教育真实操作，不触发录音、翻译、播放、权限、历史保存或账户变更。
- 测试版先启用，正式版由明确产品开关决定。
