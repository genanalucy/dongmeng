> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/first-use-guidance/issues/01-onboarding-state-and-persistence.md`；原始 SHA-256 `e58a843cb1afef447bef9c5f1a2aafb6fac8f18c4ee5633e5995f28003afb109`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 建立首次使用引导状态

Type: task
Status: ready-for-agent

## Goal

建立单一的首次使用指南协调器与本机持久化策略，管理引导版本、当前步骤、前进、后退、跳过、完成和重新打开。

## Acceptance Criteria

- 首次启动且未完成时可以进入引导。
- 跳过或完成后，后续启动不自动重放。
- 引导版本可独立于应用版本管理，默认不因升级自动重放。
- 重新查看入口可以显式开启引导。
- 仅使用本机偏好存储；不修改 Room schema、历史、账户或云端接口。
- 单元测试覆盖状态转换和持久化行为。
