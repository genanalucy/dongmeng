> **历史证据，非现行操作或验收结论。** 来源：`079108bff4b55d1b3783ca8e99e3376a35804ee0` 的 `docs/operations/qwen38-routing.md`。原文中的“当前/本次”仅指记录当时，未测项保持未测；本轮未连接生产或真机。现行入口：[当前说明](../../../../../docs/operations/README.md)；来源与内容处理见[批次索引](../../../../indexes/06-operations-and-test-materials.md)。

# Qwen 3.8 实时翻译适配与部署

2026-11-14完成候选实现，当时因模型未购买未部署。用户缴费并于2026-11-15明确授权切换，现已部署 `qwen3.8-livetranslate-flash-realtime`。以下旧阻塞记录为历史，当前状态以末尾部署记录为准。

## 协议与选择

`TRANSLATION_PROVIDER` 默认 `volcengine`，显式 `qwen` 才选择3.8；无自动fallback。Qwen配置需要同地域API key和业务空间端点，使用`output_modalities`、`audio.input.format` PCM16k及server_vad，不发送旧独立ASR配置，关闭声音复刻。3.8自动判断源语言，但translation.language仍是单一目标，不代表双向自动对话。

处理新source/translation delta事件，按item_id累加为现有App partial，并映射final；输出PCM24k跨chunk保持相位重采样为16k。response.done只结束单次响应，session.finished才结束session。错误映射固定码，不记录供应商文本、语音或密钥。Finish幂等，结束等待20秒，单会话硬上限15分钟，终止先断开上游再交付事件，避免下游阻塞导致收费连接保留。仍需真实语音/并发/会话重连验收。

## 2026-11-14阻塞与离线验证

- 使用现有受限配置备份进行了两次限时检查。首次四方向探针在第一方向配置阶段失败即退出，没有进入后续方向；第二次只进行一次无音频WebSocket配置诊断。
- 第二次收到session.created后返回顶层`AccessDenied.Unpurchased`。这是业务空间未购买/开通该模型的直接证据，不宣称欠费已解除或模型可用。两次均退出关闭连接，没有持续重试或发送用户声音。
- 没有恢复线上Qwen凭据、没有部署候选Agent或重启线上。原火山服务保持，不改App/OTA。
- 默认与officialast的test/vet、官方构建通过；本地TLS模拟上游覆盖3.8配置/delta/多句/session finish/异常断开/未完成音频/顶层购买错误/阻塞sink清理及分块重采样。
- `go test -race`未能运行：当前工具链CGO关闭且无gcc。不能把常规测试通过当作race验证通过。
- 工作树中先前的Android连续模式红色诊断测试不属本次更改，保留且不提交；连续模式故障未在此修复。

## 原继续切换条件（2026-11-15已满足）

用户在原API key所属的北京地域及业务空间开通/购买模型，并确认账户可用。限时重测实际session.updated模型及输入输出格式，验证中英/中越正常finish后，才备份火山二进制/环境、显式选qwen、部署并核对provider/model日志、运行环境和连接清理。不自动恢复3.5，也不因失败静默回退。

## 2026-11-15部署记录

- 源码`bceb6f2`，重跑默认test/vet、officialast test及构建通过。部署二进制SHA256：`fd7edda79f0db5e7d929b34c9ca7dc6288a3ff81e1b49347b8730115b4f61d8d`。
- 部署前中英、中越四方向各一帧80ms静音，使用候选实际客户端；均通过精确model/session格式验证及session.finished。未录用户声音，不代表真实翻译质量通过。
- 火山二进制和环境备份：`/opt/dngmeng/backups/qwen38-switch-20261115T124119Z/`（受限，含秘密）。当前环境显式`TRANSLATION_PROVIDER=qwen`，仅恢复Qwen API key/北京业务空间host，移除运行环境火山凭据，保留账户鉴权及治理配置。
- 首次部署脚本误用`/healthz`检查Agent，检查失败后停止服务；日志证明千问进程此前已启动。改用代码实际的`/api/health`检查，2026-11-15T12:42:04.359Z重启成功，无供应商回退。短暂服务中断已向用户说明。
- 运行进程环境provider=qwen、无VOLCENGINE变量，`/proc/PID/exe`哈希与候选一致，启动日志明确model=qwen3.8-livetranslate-flash-realtime。公网`https://yszn.nlplayer.org/api/health`及Cloud `/healthz`均200。
- 从实际运行进程环境再次进行四方向限时静音检查，全部配置/finished成功，检查进程与临时工具退出删除。不是只凭health推断模型。
- 没有改App/OTA/账户/历史；仍待用户真实语音、字幕、播放和长时间稳定性验收。Android连续模式已知问题及未提交红色测试保持不变。

官方参考：
- https://help.aliyun.com/en/model-studio/qwen3-8-livetranslate-flash-realtime
- https://help.aliyun.com/en/model-studio/live-translator-client-events
- https://help.aliyun.com/en/model-studio/live-translator-server-events
- https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime （当前页面同时介绍3.8，不由URL中的3.5推断内容）
