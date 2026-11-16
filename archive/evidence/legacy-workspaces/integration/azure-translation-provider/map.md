> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/map.md`；原始 SHA-256 `caca09e3480769fcc180f8429bb2b98fc80b0e55fdcce80b02099624f33cbda1`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Azure 翻译引擎集成执行图

Status: in-progress
规格：spec.md ｜ 设计：design.md

## 工单状态（2026-09-11）

1. `01-protocol-and-routing` ✅ 提交 6319fa5
2. `02-azure-ws-tts-client` ✅ 提交 0ef2ccd + 8f8bd93（协议以真实端点校准：from 参数、Path 头帧、RIFF 首帧、大写字段）
3. `03-deploy-and-smoke` 🟡 agent 已部署生产（默认火山回归正常）；Azure F0 在 japaneast 持续 429（冷却 9h+ 无缓解，TTS REST 正常、无并发占用、密钥有效）——判定为学生订阅 F0 Speech Translation 区域性问题，需 Portal 侧处理（查配额/换区域/升 S0）
4. `04-app-debug-settings` ✅ 提交 ba0d398
5. `05-beta-ota` ✅ versionCode=15（20260911.0502）已发布，OTA 元数据已切测试轨道；提交 eda3876

## code-review 结论（2026-09-11）

- Standards：无硬违规；2 判断题（debug 可见性策略三方法同构；probe 与生产帧组装重复）
- Spec：无缺失；1 scope creep（azure-probe/smoke 工具随 8f8bd93 提交——保留至工单 03 完成后处置）

## 待用户决策

- Azure F0 限流：Portal 查 japaneast Speech 资源用量/配额；或在允许区域（koreacentral 等）新建 F0 资源换 key；或用学生额度升 S0
- 英越质量验收（用户真机，待 Azure 可用后）
