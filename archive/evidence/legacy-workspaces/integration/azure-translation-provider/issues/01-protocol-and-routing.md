> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/issues/01-protocol-and-routing.md`；原始 SHA-256 `53a9b3dd659bedd9139f50c844dc3552d0088942b8ed159458ec9bc9f4eeea27`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 01: 协议与引擎路由

**What to build:** start 消息支持按会话选择翻译引擎：新增可选字段 `provider`（volcengine|azure，缺省 volcengine）、`candidateLanguages`（仅解析，供阶段 2 语言自动检测使用）、`voice`（目标语言 TTS 声音名，服务端按每语言候选白名单校验）。provider=azure 且服务端未配置 Azure 密钥时，会话启动即拒绝并返回 `TRANSLATION_PROVIDER_UNAVAILABLE`；已配置时 agent 将会话路由到 Azure 客户端。老客户端不带新字段时行为与现状完全一致。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] start 消息解析 provider/candidateLanguages/voice 三个可选字段，未知值拒绝
- [ ] provider 缺省与 "volcengine" 均路由火山，存量行为零变化
- [ ] provider=azure 未配置 AZURE_SPEECH_KEY/REGION 时拒绝，错误码 TRANSLATION_PROVIDER_UNAVAILABLE，客户端可见
- [ ] provider=azure 已配置时路由 Azure 客户端（本票允许假 Azure 会话实现，02 替换为真实现）
- [ ] voice 字段按每语言候选白名单校验，非法值拒绝
- [ ] candidateLanguages 仅解析存储，不产生任何运行时行为
- [ ] Go 集成测试覆盖以上全部分支（含老客户端无字段回归）
