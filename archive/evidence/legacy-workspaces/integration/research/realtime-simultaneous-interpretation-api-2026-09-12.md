> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/realtime-simultaneous-interpretation-api-2026-09-12.md`；原始 SHA-256 `33a0551fdf849f3c1ec0bd6b0932badcac4e149ff5fd94d19cfab64e67403997`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 实时同声传译 API 调研

- 查询日期：2026-09-12
- 范围：只采用厂商官方文档、API reference 或官方 SDK/sample；未读取密钥、未调用任何付费 API，也未改产品代码。
- 判定标准：持续输入实时音频；返回可修正的 ASR/翻译增量；可在会话未结束前返回/播放译文语音。仅“音频文件上传后返回”不算。
- 目标语种：中文（zh）、英文（en）、越南语（vi）。表中“覆盖”表示官方文档明确的能力或语种；未明确处不作推定。

## 结论与推荐顺序

1. **Azure Speech Translation（首选，最小改造）— 真正接近火山。** 已在当前 Go agent 配置中存在 Azure Speech 凭据字段；官方 `TranslationRecognizer` 持续识别会在 `recognizing` 回调给出中间原文和译文，并能在 `synthesizing` 回调逐次返回合成音频。适合保留现有 browser↔agent 的 PCM WebSocket 契约，只把当前 Azure 的“最终译文才 TTS”策略改为消费 `Synthesizing` / 在稳定短句触发 TTS。必须先用实际订阅、区域和目标 voice 验证 zh/en/vi 语音翻译语种表和首音频延迟。
2. **DeepL Voice API（首选备选/基准测试）— 真正接近火山。** 一个会话同时提供增量 ASR、多个译文和目标音频 `target_media_chunk`；WebSocket 直接接入，Go 可用普通 WebSocket client。先核验所购 Voice API 的 zh/en/vi 输入、目标翻译和 TTS 语音覆盖及地区可售性。
3. **阿里云百炼 `qwen3.5-livetranslate-flash-realtime`（中国区优先备选）— 真正接近火山。** WebSocket 单模型输入音频、流式源转写、译文文本及 `response.audio.delta`，文档称 VAD 模式通常在说话过程中开始翻译。供应区为北京/新加坡、模型/文档仍需商用可用性压测；重点实测 zh/en/vi 的“29 种音频+文本输出”是否覆盖目标方向。
4. **百度实时语音翻译（中国区可落地）— 真正接近火山。** 单一 WSS 能接收 PCM 流、返回 MID/FIN ASR+译文，并在 `return_target_tts=true` 返回 MP3 TTS 帧；官方表明 zh/en/vi 都有 TTS。协议含二进制响应封装，Go 需实现解帧。
5. **腾讯云实时语音翻译（中国区；仅 zh↔en 时接近火山）— 真正接近火山但不满足三语。** WSS 的翻译结果为句内全量覆盖，原生 `enable_tts=1` 返回音频；但该接口仅在目标为 zh/en/zh_en 时启用 TTS，且列出的直接翻译语言对不含 vi。因此不作为三语主链路；可用于 zh↔en 专用路由。
6. **AWS 组合（成熟 Go 接入）— 组合可实现。** Transcribe Streaming（ASR）+ Translate（文本翻译）+ Polly `StartSpeechSynthesisStream`（增量文本、增量音频）；Go v2 官方支持 Polly 双向 TTS。需要自行管理 ASR partial/final、稳定窗口、翻译段、取消与音频队列；确认所选 region 中 vi voice/语种与服务可用性。
7. **Google Cloud 组合 / Gemini Live Translate — 组合可实现 / 预览候选。** Cloud Speech-to-Text V2 是 gRPC 双向流（非 WebSocket），官方 reference 有 `translation_config`，但该路线没有集成 TTS，需另接 Cloud TTS。另一个 `gemini-3.5-live-translate-preview` 是更接近一体化的持久 WebSocket 音频入/译音频出，并可给双向转写；但为 Preview/Pre-GA，不能取代 GA 主链路，且应先核验正式的 zh/en/vi 支持、区域、计费与模型访问资格。
8. **讯飞同声传译（不适合三语 Web 产品主链）— 不符合。** 文档明确一体化 WS、音频输出，但公开语种说明为“中文普通话同传为英文发音”，并明确“不支持跨域不适用于浏览器”；不能满足 vi 和当前浏览器直连诉求。若改为服务端代理且只做 zh→en 可评估。
9. **ElevenLabs / Sarvam（仅组件）— 不符合一体化。** ElevenLabs 提供实时 STT WebSocket 与可增量投喂的 TTS WebSocket，但没有官方实时语音翻译一体接口；其 speech-to-speech 是变声而非翻译。Sarvam 流式 STT 翻译输出仅英语，TTS 侧重 Indic 语言，不适合 zh/en/vi。

## 当前 Azure agent 的事实与最小改造

### 当前实现核对

- `agent/internal/config/config.go` 已读取 `AZURE_SPEECH_KEY` 与 `AZURE_SPEECH_REGION`（未读取值）。
- `agent/internal/ast/official_client.go` 已将上游 `SourceSubtitleResponse` / `TranslationSubtitleResponse` 映射为 `source_partial` / `translation_partial`，并把 `TTSResponse` 映射为持续的 `tts_audio` PCM。
- `agent/internal/server/server.go` 已将 `tts_audio` 原样二进制转给浏览器。因此浏览器侧已经具备连续音频通道；问题应限定为 Azure provider 的触发/回调组织，而非重建客户端协议。

### 推荐最小架构（Azure）

```text
浏览器 AudioWorklet(16 kHz mono PCM, 20–100 ms)
  ⇅ 现有 browser↔Go agent WebSocket（限长队列、背压）
Azure provider: PushAudioInputStream → TranslationRecognizer continuous
  ├─ recognizing: 覆盖式 source_partial / translation_partial（仅字幕，不投喂 TTS）
  ├─ recognized: 得到稳定短句；按 utterance ID 入 TTS 队列
  └─ synthesizing: 立即转发 PCM audio chunk
Go agent → 现有二进制 `tts_audio` → 浏览器 AudioWorklet 顺序播放
```

实施要点：

1. **音频按稳定段播报，不播报可回滚 partial。** `recognizing` 仅更新字幕；`recognized`（或 Azure synthesis 回调对应的已确认译文）才进播放队列，避免说到一半被翻译修正而重复/读错。
2. **以 utterance / `sentence_id` 做幂等键。** 建立 `pending / synthesizing / played / cancelled` 状态；连续识别终态不得重复合成。停话、取消、换向时同时取消 recognizer、清空本地未播队列，且音频帧携带/隐含会话 generation 防旧帧串音。
3. **保持一条有序 PCM 播放流，但保留段边界。** 当前 server 注释称浏览器无须验证 TTS 边界；内部仍保留 segment ID 与 `tts_start/tts_end`，便于抖动缓冲、统计“语音结束→首音频”、失败重试和断句排错。
4. **延迟预算先实测后承诺。** 20–100 ms 上传帧、50–150 ms 播放抖动 buffer；采集 `audio_capture_ts`、speech-end、ASR partial/final、translation partial/final、TTS first-byte、played timestamps。目标体验取决于 VAD 断句、网络及目标语言语速，不能仅以厂商“低延迟”文案承诺毫秒值。
5. **凭据不下发浏览器。** Go 服务端持有厂商凭据；客户端仅连现有 agent。若厂商必须浏览器 WSS，改为服务端签发短期、最小权限 token 或服务端 relay，不能暴露长期 key。

### 建议验证顺序

1. 给 Azure 现有 provider 增加事件级日志/指标（不记录音频/密钥），确认是否已经收到每句 `TTSResponse`；若能收到但未实时播放，优先修调度/播放。
2. 用同一套 2–3 分钟 zh↔en、en↔vi、vi↔zh 脚本，比较 Azure、DeepL、阿里一体化的首字幕、首译文、首音频、最终正确率、断句与重连。
3. 以目标销售地区、数据驻留、账户可购性和实际语种/voice 覆盖作为淘汰门槛；再决定 provider abstraction，避免先做大规模多厂商重构。

## 厂商逐项对比

| 厂商 / 方案 | 分类 | 实时链路与 TTS 组织 | zh/en/vi 与 Go/WS 可行性 | 延迟、商业与地区风险 |
|---|---|---|---|---|
| Azure Speech Translation | 真正接近火山 | `TranslationRecognizer.startContinuousRecognitionAsync` 持续运行；`recognizing` 是中间转写/翻译；`Synthesizing` 多次给翻译合成音频。 | Speech SDK 有 Go 不在官方列出的主 SDK；当前 Go agent 可经 provider/协议适配，浏览器可用 JS SDK 或由 Go 代理。官方语言页需逐项核验语音翻译和 voice。 | GA 云服务、集成成本最低；区域、语音能力/定价与 Azure 合规/可达性需按部署区确认。 |
| DeepL Voice API | 真正接近火山 | REST 创建会话后 WSS；源/目标 transcript 更新区分 tentative/concluded；配置 `target_media_*` 后增量 `target_media_chunk` 返回纯合成语音。 | 官方 SDK 尚未集成 Voice API，但任意 WS 客户端可接；Go 易实现。必须购买 Voice API access；文档允许固定源语以降低延迟。 | 50–250 ms 音频 chunk 为官方推荐；依赖 paid API、可用国家/数据处理条款及 zh/en/vi+TTS 需购前确认。 |
| 阿里百炼 `qwen3.5-livetranslate-flash-realtime` | 真正接近火山 | WSS，`input_audio_buffer.append`；输入 ASR 事件、`response.text`/`response.audio_transcript` 增量和 `response.audio.delta` 音频。VAD 下通常输入过程中启动翻译。 | 官方称 Java/Python SDK；其他语言直连 WSS，Go 可做。北京/新加坡 endpoint。目标“29 audio+text”语种中是否含 zh/en/vi 需控制台/实际接口确认。 | 模型/可用性变化快，账户地域和合规是风险；实时模型可能有 VAD/模型输出不稳定性，必须压测。 |
| 百度实时语音翻译 | 真正接近火山 | `wss://aip.baidubce.com/ws/realtime_speech_trans`；binary PCM 上行；`MID`/`FIN` 文本；`return_target_tts=true` 得到 MP3 TTS 二进制响应。 | 官方语言表列 zh/en/vie 均“支持 TTS”；直接 WebSocket，Go 需要解析专用二进制响应协议。 | 中国云账户/跨境网络与数据合规风险；首包/端到端时延未给 SLA；切句驱动，实测连续播报。 |
| 腾讯云实时语音翻译 | 真正接近火山（zh↔en） | 单 WSS，持续 audio binary 与实时全量句子结果；`enable_tts=1` 时接收二进制音频，等 `final=2` 后收尾。 | Go SDK / WS 都有官方资料；**目标 zh/en/zh_en 才支持该接口 TTS，翻译对未见 vi**，不满足三语。 | 官方建议 200 ms / 6400 B 且 1:1 实时率，默认并发 5；中国云与签名凭据只能后端持有。 |
| 讯飞同声传译 | 不符合（目标三语/浏览器） | 一条 WSS 传 PCM，返回文本与 base64 TTS chunk，属于一体化链路。 | 文档称任意 WS 语言可接，但明确不支持跨域浏览器；公开说明只列普通话→英文发音。 | 中国区商业/跨域限制；若 server relay、zh→en 单向可作为候选，不推荐主线。 |
| AWS：Transcribe + Translate + Polly | 组合可实现 | Transcribe 用 HTTP/2 或 WSS 双向流给实时 ASR；在 stable segment 调 Translate；Polly `StartSpeechSynthesisStream` 为 HTTP/2 双向：分段文本入、AudioEvent 出。 | 三个服务均有 AWS SDK for Go v2；浏览器经自身 Go relay。每个语言/voice 必须用 `DescribeVoices` 和地区表确认 vi。 | 组件多、跨服务计费/重试/幂等复杂；Polly 双向流仅 generative engine，须先核验其 voice/地区；服务地区、跨境与 IAM 运维成本。 |
| Google Cloud Speech-to-Text V2 + Translate + Cloud TTS | 组合可实现 | STT V2 `StreamingRecognize` 是 gRPC 双向流、实时 results；reference 含 speech translation config，但无一体 TTS；稳定句后接 Translation/TTS。 | Go 的 gRPC/官方 client 可接，Web 经 Go gateway；语言、voice 与区域另查官方支持表。 | STT streaming 仅 gRPC、单请求大小限制；三服务编排，区域/认证/账单复杂。 |
| Google Gemini Live Translate | 预览的一体化候选 | 持久双向 WebSocket；流入 16 kHz PCM、流出 24 kHz translated PCM，并可开输入/输出转写；推荐 100 ms chunks。 | 官方示例是 Gen AI SDK；Go 可按 WS 协议或等待官方支持。必须先验证模型对 zh/en/vi、账户区域和生产资格。 | 官方标为 Preview/Pre-GA/“as is”，故只能试验或备选，不是当前生产主链。 |
| ElevenLabs（STT + TTS） | 不符合一体化；可作组件 | Realtime STT WSS 有 partial/committed；TTS WSS 支持 partial text→audio，适合从翻译器稳定段投喂。speech-to-speech 是 voice conversion，不是翻译。 | 没有官方 Go SDK 作为主路径，但 HTTP/WS 都能接；需另选 MT，实际核验 zh/en/vi voice/ASR。 | 多服务拼装、用量和地区/数据驻留需审查；不要因产品名“speech-to-speech”误判为同传。 |
| Sarvam（Saaras + Bulbul） | 不符合目标语言/一体化 | STT WS 支持低延迟、Realtime beta 有 interim；`translate` 输出英语；TTS WSS 可累积文本、flush、逐块音频。 | 官方 SDK Python/JS；Go 可原生 WSS。翻译目标仅 English，且 TTS 定位 Indic，不能覆盖本产品三语。 | 部分 realtime/beta 能力及区域/语言限制，排除主链。 |

## 官方来源（查询日均为 2026-09-12）

### Azure

- [Speech translation overview](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-translation) — 明确实时 audio streams、interim 结果和 speech-to-speech。
- [How to recognize and translate speech](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-translate-speech) — continuous `recognizing`/`recognized` 与 `Synthesizing` 事件。
- [JavaScript TranslationRecognizer reference](https://learn.microsoft.com/en-us/javascript/api/microsoft-cognitiveservices-speech-sdk/translationrecognizer?view=azure-node-latest) — continuous API。
- [Language and voice support](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=speech-translation) — 上线前核验目标语言与 voice。

### DeepL

- [Voice API overview](https://developers.deepl.com/docs/voice/overview) — 同一实时会话的转写、翻译、translated speech。
- [Real-Time Voice Quickstart](https://developers.deepl.com/docs/voice/real-time-voice-quickstart) — 200 ms demo、tentative/concluded 语义及固定源语降延迟。
- [Request Session reference](https://developers.deepl.com/api-reference/voice/request-session) — `target_media_languages` / 格式 / voice。
- [WebSocket Streaming reference](https://developers.deepl.com/api-reference/voice/websocket-streaming) — 50–250 ms 推荐和 `target_media_chunk`。
- [Understanding Voice Sessions](https://developers.deepl.com/docs/voice/understanding-voice-sessions) — 重连与并行音频/文本流。

### 阿里云

- [Qwen3.5 LiveTranslate Flash Realtime](https://help.aliyun.com/zh/model-studio/qwen3-5-livetranslate-flash-realtime) — 一体化实时语音/视频翻译、文本/音频 delta、北京/新加坡 endpoint。
- [Gummy 实时语音识别、翻译 WebSocket API](https://help.aliyun.com/zh/model-studio/real-time-websocket-api) — 仅 ASR+译文的单 WSS 备选与语种对。
- [Gummy 产品说明](https://help.aliyun.com/zh/model-studio/real-time-speech-translation) — 长流、VAD、SDK/WS 与语种对。
- [Qwen-TTS Realtime WebSocket](https://help.aliyun.com/zh/model-studio/interactive-process-of-qwen-tts-realtime-synthesis) — 若采用 Gummy 组合时的增量 TTS。

### 腾讯云

- [实时语音翻译（WebSocket）](https://cloud.tencent.com/document/api/1093/127565) — 协议、结果、`enable_tts` 与语言限制。
- [通过实时语音翻译实现同声传译](https://cloud.tencent.com/document/product/1093/130882) — 官方 Go + WSS sample、句子覆盖语义。
- [实时语音合成](https://cloud.tencent.com/document/api/1073/94308) — 独立流式 TTS。

### 百度

- [实时语音翻译](https://ai.baidu.com/ai-doc/MT/Sl9p2h5k9) — WSS、MID/FIN、45 语种及可选 TTS binary。
- [流式文本在线合成](https://ai.baidu.com/ai-doc/SPEECH/lm5xd63rn) — 独立流式 TTS 备选。

### 讯飞

- [同声传译 API](https://www.xfyun.cn/doc/nlp/simultaneous-interpretation/API.html) — 一体化 WS、语言/浏览器限制、TTS chunk。
- [实时语音转写](https://www.xfyun.cn/doc/asr/rtasr/API.html) — 可选翻译与流式 ASR。
- [超拟人语音合成](https://www.xfyun.cn/doc/spark/super%20smart-tts.html) — 分段双向 TTS。

### AWS

- [Amazon Transcribe `StartStreamTranscription`](https://docs.aws.amazon.com/transcribe/latest/APIReference/API_streaming_StartStreamTranscription.html) — HTTP/2/WSS 双向音频/转写与 Go v2。
- [Transcribing streaming audio](https://docs.aws.amazon.com/transcribe/latest/dg/streaming.html) — SDK、HTTP/2、WebSocket。
- [Polly bidirectional streaming](https://docs.aws.amazon.com/polly/latest/dg/bidirectional-streaming.html) — 文本与音频并发。
- [Polly `StartSpeechSynthesisStream`](https://docs.aws.amazon.com/polly/latest/dg/API_StartSpeechSynthesisStream.html) — protocol、generative 限制。
- [Polly Go example](https://docs.aws.amazon.com/polly/latest/dg/bidirectional-streaming-examples.html) — 官方 Go v2 sample。
- [Amazon Translate API reference](https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html) — 组合中的文本翻译服务。

### Google Cloud

- [Cloud Speech-to-Text V2 streaming](https://cloud.google.com/speech-to-text/v2/docs/streaming-recognize) — gRPC 实时流和 chunk 限制。
- [Speech V2 RPC reference](https://cloud.google.com/speech-to-text/v2/docs/reference/rpc/google.cloud.speech.v2) — 双向 `StreamingRecognize` / `TranslationConfig`。
- [Gemini Live Translate](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-5-live-translate) — 译音频 WSS、格式、100 ms chunks。
- [Gemini Live API reference](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/models/multimodal-live) — WebSocket、VAD、Preview 条款。

### ElevenLabs / Sarvam

- [ElevenLabs Realtime STT WebSocket](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime) — partial/committed transcript。
- [ElevenLabs TTS WebSocket](https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input) — partial text 到音频。
- [ElevenLabs speech-to-speech stream](https://elevenlabs.io/docs/api-reference/speech-to-speech/stream) — 确认为 voice changer，而不是翻译。
- [Sarvam Streaming STT](https://docs.sarvam.ai/api/api-guides-tutorials/speech-to-text/streaming-api) — realtime/legacy、`translate`→英语的限制。
- [Sarvam Streaming TTS WebSocket](https://docs.sarvam.ai/api/api-guides-tutorials/text-to-speech/streaming-api/web-socket) — 文本累积/flush/渐进音频。

## 仍需在采购/PoC 前关闭的风险

- 厂商文档会更新；本报告只在上述查询日有效，且 Web 搜索结果中少数页面标记了未来发布日期，不能将其当作已在当前账户可用的承诺。
- “支持语言”必须拆成：源 ASR、目标 MT、目标 TTS voice、该 account/region 的 feature availability 四项验收；尤其 vi 与一体化 TTS。
- 供应商宣传的低延迟不等同实际端到端播放延迟。必须使用同一网络、同一分片和同一脚本压测，记录 p50/p95/故障重连。
- 在中国大陆、跨境或对数据驻留有要求的部署中，分别审查网络连通、账户主体、数据处理地点、日志保留、DPA/安全审查与出口合规；报告未对法律合规作结论。
