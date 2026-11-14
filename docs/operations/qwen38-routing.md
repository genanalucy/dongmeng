# Qwen 3.8 实时翻译适配（未部署）

2026-11-14，用户明确授权将线上切到 `qwen3.8-livetranslate-flash-realtime`。当前是候选实现，线上仍维持火山，不将该文档当作切换成功。

## 协议与选择

`TRANSLATION_PROVIDER` 默认 `volcengine`，显式 `qwen` 才选择3.8；无自动fallback。Qwen配置需要同地域API key和业务空间端点，使用`output_modalities`、`audio.input.format` PCM16k及server_vad，不发送旧独立ASR配置，关闭声音复刻。3.8自动判断源语言，但translation.language仍是单一目标，不代表双向自动对话。

处理新source/translation delta事件，按item_id累加为现有App partial，并映射final；输出PCM24k跨chunk保持相位重采样为16k。response.done只结束单次响应，session.finished才结束session。错误映射固定码，不记录供应商文本、语音或密钥。Finish幂等，结束等待20秒，单会话硬上限15分钟，终止先断开上游再交付事件，避免下游阻塞导致收费连接保留。仍需真实语音/并发/会话重连验收。

## 当前阻塞与验证

- 使用现有受限配置备份进行了两次限时检查。首次四方向探针在第一方向配置阶段失败即退出，没有进入后续方向；第二次只进行一次无音频WebSocket配置诊断。
- 第二次收到session.created后返回顶层`AccessDenied.Unpurchased`。这是业务空间未购买/开通该模型的直接证据，不宣称欠费已解除或模型可用。两次均退出关闭连接，没有持续重试或发送用户声音。
- 没有恢复线上Qwen凭据、没有部署候选Agent或重启线上。原火山服务保持，不改App/OTA。
- 默认与officialast的test/vet、官方构建通过；本地TLS模拟上游覆盖3.8配置/delta/多句/session finish/异常断开/未完成音频/顶层购买错误/阻塞sink清理及分块重采样。
- `go test -race`未能运行：当前工具链CGO关闭且无gcc。不能把常规测试通过当作race验证通过。
- 工作树中先前的Android连续模式红色诊断测试不属本次更改，保留且不提交；连续模式故障未在此修复。

## 继续切换条件

用户在原API key所属的北京地域及业务空间开通/购买模型，并确认账户可用。限时重测实际session.updated模型及输入输出格式，验证中英/中越正常finish后，才备份火山二进制/环境、显式选qwen、部署并核对provider/model日志、运行环境和连接清理。不自动恢复3.5，也不因失败静默回退。

官方参考：
- https://help.aliyun.com/en/model-studio/qwen3-8-livetranslate-flash-realtime
- https://help.aliyun.com/en/model-studio/live-translator-client-events
- https://help.aliyun.com/en/model-studio/live-translator-server-events
- https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime （当前页面同时介绍3.8，不由URL中的3.5推断内容）
