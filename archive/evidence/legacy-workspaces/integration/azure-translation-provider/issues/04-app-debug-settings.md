> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/issues/04-app-debug-settings.md`；原始 SHA-256 `313c5221d2de2fb59063d2cb0f14b3f3bd05f1e84ab7610678d8925fca68258f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 04: App Debug 设置与自动模式入口

**What to build:** 测试版 App 在 Debug 设置中提供翻译引擎选择（火山/Azure，默认火山）与每语言 TTS 声音选择（固定候选，每语言 2 个神经声）；新会话的 start 消息携带所选 provider 与 voice，进行中会话不受影响。Azure 自动面对面使用候选语言对启动 universal v2 AtStart-LID：完整 source_final + translation_final 后立即停采集并 Finish；finished 后以已有 cloud grant 建立下一独立 socket，旧段 TTS 可排队播放，切段期间丢音。正式版（非 Debug 构建）不显示引擎/声音调试设置。

**Blocked by:** None (can start immediately，与 01-03 并行开发；端到端验收依赖 03）

**Status:** ready-for-agent

- [ ] 设置存储：引擎与声音持久化于 SharedPreferences，默认火山、默认声音
- [ ] start 消息序列化：选中 Azure/声音时携带 provider/voice；未选时与现消息完全一致（老格式）
- [ ] 设置修改仅影响新会话，进行中会话不中断不切换
- [ ] 每语言声音下拉仅列候选白名单
- [ ] Azure 自动面对面每段独立重启 AtStart-LID，并按 detected language 动态确定说话侧与相反耳路由；不以手动接管改变 Azure speaker
- [ ] pause/stop/cancel/恢复失败在 finished 前取消待续转；失败不得无限创建 socket
- [ ] 完整 final pair 且 finished 才保存历史，partial 或未配对 final 不保存
- [ ] 正式版构建不显示引擎/声音设置与自动模式入口
- [ ] Android 契约测试：start 消息带/不带新字段的序列化、设置默认值、Debug 可见性
