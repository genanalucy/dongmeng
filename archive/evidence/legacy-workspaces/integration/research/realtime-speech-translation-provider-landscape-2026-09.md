> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/realtime-speech-translation-provider-landscape-2026-09.md`；原始 SHA-256 `e4565a56948ad38d2db79b8ba2fd5e565fe616b9f0f29f55249f6d76977dc3cb`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 实时语音翻译供应商调研（停止 Azure 路线后的候选）

- 调研日期：2026-09（只查阅厂商官方文档、官方 API reference、官方 SDK 示例或官方定价/试用页）。
- 范围：为当前应用语言 **中文、英文、越南语、法语** 选择可在 Debug 设置中逐项试验的语音翻译 provider；不读取密钥、不调用 API、不改代码。
- 术语：
  - **S2S 直连**：同一持续会话输入音频，直接返回译文文本和/或译音频。
  - **组合链路**：实时 STT → MT → TTS；每段由本服务编排。它仍可实现同传体验，但不是厂商原生 S2S。
  - **partial/interim** 可被后续消息改写；**final/committed** 不应再改写。partial 只显示字幕，只有 final（或厂商明确不可回滚的音频）才可进入播放队列。

## 先给决定

1. **停止 Azure 自动工作/不再把 Azure 作为下一轮开发或默认候选。** 现有 Agent 已有 `azure` 路由槽及 `AZURE_SPEECH_*` 配置字段，但 `config.Load` 仍以 Volcengine 身份配置作为成功加载前提；当前 `officialast` 实现仍直连 Volcengine AST。即使 Azure Speech Translation 的官方能力满足实时 S2S，也不应据此继续扩展 Azure，除非产品重新明确选择它。详见“仓库事实”。
2. **最值得先在 Debug 设置做顺序 PoC：`volcengine`、`google-gemini-live-preview`、`speechmatics+tts`。**
   - Volcengine 是当前协议与 16 kHz PCM 最接近的既有生产通路，官方 AST 覆盖 zh/en/vi/fr，但仅支持“中文或英文 ↔ 其余语言”，所以 **vi↔fr 不直连**。
   - Gemini Live Translate 是官方明确的真 S2S，覆盖四种语言并支持原生音频/双转写；但仍是 Preview，不能作为唯一生产依赖。
   - Speechmatics 提供成熟的实时 STT+MT（文本，不产译音频），四语对英语的双向翻译明确；接其 HTTP 流式 TTS 是清晰、可审计的组合方案，适合全球数据驻留需求。
3. **账户/密钥齐备后再测：DeepL Voice（真 S2S，需确认 Voice API 合同与四语声音覆盖）、Google Cloud Media Translation（旧 beta/gRPC surface 仍有 reference，但产品级可用性和语言矩阵未在当前官方产品页充分承诺）、AWS 组合（成熟、但越南语自动 LID 不支持）。**
4. **不建议新增到四语 Debug provider：腾讯一体实时翻译（官方接口无 vi/fr）、Deepgram Flux（Flux Multilingual 没有 zh/vi 且仅 STT）、OpenAI Realtime transcription（官方未将它定义为实时音频翻译）、阿里/百度（可作中国区专项 PoC，但全球供给、四语闭环或 TTS 要逐帐号验收）。**

> “可加入 Debug”只表示可放在**开发者专用、显式选择、后端持有凭据**的 provider 列表，并非可以把厂商 key 下发给 APK/Web。生产启用必须另外完成区域、合同、DPA、价格、配额和基准测试审批。

## 仓库事实与建议的测试接缝

通过代码图谱和源文件核对（未读取环境变量值）：

- `agent/internal/ast/client.go` 的 `StartRequest` 已有 `Provider`、源/目标语言、候选语言、目标音频格式/采样率；`NewProviderRoutingClient(volcengine, azure)` 仅对字符串 `azure` 分支，否则走 Volcengine。
- `agent/internal/config/config.go` 定义 `VOLCENGINE_*`、`DASHSCOPE_API_KEY`/`QWEN_API_HOST`、`AZURE_SPEECH_KEY`/`AZURE_SPEECH_REGION`；但加载成功仍要求 Volcengine API key/旧凭据，或 DashScope key+host，Azure 字段本身不构成可启动配置。
- `agent/internal/ast/official_client.go`（`officialast` build tag）以 `wss://openspeech.bytedance.com/api/v4/ast/v2/translate` 和 16 kHz/16-bit/mono raw 音频启动 AST，并接收持续事件。它最适合成为 provider 抽象的行为基线。
- 当前 `Event` 已有 `SegmentID`/`TargetLanguage` 与二进制音频字段；provider 测试不应更改 browser 协议，只应把上游的 partial、final、音频块归一化成既有事件语义。

后续若获准实现，Debug 设置应只选一个 `providerId`，每次新建会话，不在会话中热切 provider；每个 provider 需要独立的服务端 secret、endpoint/region、feature flag、健康检查和审计标签。用相同脚本顺序测量，而非同时启用多个厂商：zh→en、en→zh、en→vi、vi→en、en→fr、fr→en，并把 **vi↔fr** 单列为“不受英语枢纽时是否支持”的验收项。

## 候选总表

| Provider / 方案 | 分类与四语结论 | 传输、事件语义、服务端接入 | 自动 LID / 切换 | 商业与安全结论 | 分层 |
|---|---|---|---|---|---|
| **Volcengine AST** | 原生实时语音→文本翻译；AST 官方列 zh/en/vi/fr，规则是 zh 或 en 与其它 14 语种双向，不是任意四语两两互译。官方 AST 文档未在本次证据中承诺译音频，因此按“文本 S2S 的前半段”评估，需另接 TTS。 | 现有 WSS AST 端点；现有实现为 16 kHz PCM。以每个句/段的覆盖式 partial 和 final 建模，不能 append partial。Go：当前 Agent 已有 WS/protobuf 接入。 | 现有 AST 请求显式 source/target；本次官方 AST 证据未确认会话内自动 LID。 | 需火山账户、项目/API key 或 app/token/resource；仅服务端 header。中国/跨境网络、数据驻留和采购资格单独审查。 | **T1：现在可测**；保留为基线。 |
| **Google Gemini Live Translate** | **原生实时 S2S**；官方说 70+ 语言，列表包含 Chinese Simplified/Traditional、English、Vietnamese、French。 | Stateful WSS；输入 raw PCM s16le/16 kHz mono、建议 100 ms；输出 raw PCM s16le/24 kHz mono。`inputAudioTranscription`/`outputAudioTranscription` 可取文本；需处理 generation complete、interrupted、GoAway/重连。Go 没有本次查到的官方 Go SDK 要求，服务端可按官方 raw WebSocket schema 实现。 | 翻译设置是单一 `targetLanguageCode`；文档没有承诺“自动把未知源语言切到任意目标”的双向对话路由。用户换源语可被模型理解，但产品应按每个方向建立会话并实测。 | **Preview**、使用 `v1alpha`；要 Cloud/Gemini 项目、已启用计费/API。浏览器直连只能使用服务端签发的短期 ephemeral token；令牌约束应锁定 model 和 `translationConfig`。连接约约 10 分钟，使用 session resumption。 | **T1：现在申请后可测**；只作 Preview 对照，不设默认生产。 |
| **Speechmatics Realtime Translation + Speechmatics TTS** | **组合链路**；实时 STT+MT 有 partial/final translation；官方表明确 Mandarin、French、Vietnamese 与 English 间双向翻译。因此四语“经英语”可覆盖，**未承诺 cmn↔vi、cmn↔fr、vi↔fr 直连**。TTS 是另一个 HTTP streaming 调用。 | Realtime WSS：`wss://{region}.rt.speechmatics.com/v2/`；二进制 `AddAudio`、JSON 控制/结果。`AddPartialTranslation` 可改写，`AddTranslation` 为 final。TTS 为 HTTP stream、首音频通常 <200 ms，**不是双向 TTS WS**。官方 SDK 为 Python/JS；Go 用标准 WSS+HTTP。 | Realtime Enhanced/Standard 要预选语言或固定 language pack；Melia 1 自动多语仅 Batch。不能宣称同一 realtime 流四语自动 LID。 | 可选 EU/US/AUS 固定 region 或 global 路由。WS bearer key 仅后端；若浏览器直连，官方建议以服务端 API key 生成短期 JWT。需要账户/key/额度，试用金额与商业价格以控制台/报价为准。 | **T1：现在可加 debug 设计**；需新 key。 |
| **DeepL Voice API** | **原生实时 S2S**：一个 Voice session 有增量源 transcript、译文、译音频。四语文本翻译并不等于 Voice ASR/TTS 四语支持；本次官方页面未给可引用的完整 Voice 语言×voice 矩阵，故标为 **待合同/控制台验证**。 | REST 创建 session 后按其 WSS URL 发音频；返回 tentative/concluded transcript/translation，和 `target_media_chunk`。官方建议 50–250 ms chunk。无官方 Go SDK 也不构成障碍：Go WS client 即可。 | 可设源语言降低延迟；不得将“未设源语言”描述成官方承诺的实时 LID/会话内换语。 | Voice API 不是一般免费文本 API 的当然权限；先确认账户 entitlement、可售国家、数据处理、限额/定价。长期 auth 不下发；由 agent relay。 | **T2：账号/语种验收后**。 |
| **Google Cloud Media Translation** | 产品 proto 定义 `StreamingTranslateSpeech` 双向 gRPC，返回文本翻译、音频翻译、识别和检测源语字段；并可给最多 3 个备用源语，适合对话方向。但 API 名称为 `v1beta1`；当前官方产品导航/语言与价格页没有给出足够的公开现行 Voice 语言/region 承诺。 | gRPC streaming（非 REST）。首包仅 config，后续音频；不是浏览器 WSS。Go 可用生成的 protobuf/gRPC（须在 PoC 确认当前 Go package 和服务启用状态）。partial 由 `is_final` 区分。 | proto 可给 `alternative_source_language_codes`，并回 `detected_source_language_code`；只应称候选源检测，不能假定无限自动 LID。 | 需要 GCP project、Media Translation API 启用、OAuth/service account 与 billing；证据表面为 beta，先做 availability/ticket gate。服务账号只在服务端或工作负载身份中。 | **T2：先确认仍可开通**。 |
| **Google Cloud STT V2 + Cloud Translation + Cloud TTS** | **组合链路**；各服务公开支持 zh/en/vi/fr（必须逐个读取 STT/TTS 的区域语言表和 voice list）；Cloud Translation 文档明确其文本翻译语言表包含四语。没有“Cloud Translation streaming API”：文本翻译是每个稳定片段请求。 | STT V2 `StreamingRecognize` 是 gRPC-only，单消息上限 25 KB；`is_final`/stability 驱动提交。final 段请求 Translation，再用 Cloud TTS 的流式响应播放。官方 Go clients/gRPC 可用，浏览器经 Go relay。 | STT 的候选语言/自动检测须按 model/region 验收；STT translation config 不是 TTS。一条流中自动换语不可假定。 | GCP project、billing、service account/OAuth、API enablement；选 region 以满足驻留。不要在 Debug app 内放 JSON service-account key；使用后端 workload identity 或最小权限服务账号。 | **T2：成熟组合备选**。 |
| **AWS Transcribe Streaming + Translate + Polly** | **组合链路**；Transcribe streaming 表列 zh-CN、fr-FR、vi-VN、英语；Translate 处理文本；Polly voice 必须按 Region `DescribeVoices` 验收四语。**关键限制：AWS streaming automatic language identification 当前不支持 Vietnamese**，故四语自动 LID 不合格；显式源语时仍可做。 | Transcribe 可 HTTP/2 或 WSS；词/段 results 有 partial/final。final segment → Translate → Polly。Polly `StartSpeechSynthesisStream` 为 HTTP/2 双向，且官方限定 generative engine/受支持 region/voice；普通 TTS 也可按句请求。AWS SDK for Go v2 是官方路径。 | 固定源语可行；automatic/multi-language streaming LID 不支持 vi，不能打开“自动切语”作为四语功能。 | AWS account、IAM、region、service quotas 和按服务计费；遵循 IAM role/STS 短凭据，禁用长 Access Key 到客户端。检查所选 region 实际支持，语音/Transcribe region 不一定相同。 | **T2：已有 AWS 账户时**。 |
| **Azure Speech Translation** | 原生实时 speech-to-text / speech-to-speech，官方 `recognizing` interim、`recognized` final、`synthesizing` audio；官方 LID/多语翻译支持候选语言和连续 LID。**功能可行不代表本项目继续做它。** | 官方 Speech SDK（C#/C++/Java/JS/Python，不是官方 Go SDK）；服务端 Go 须 SDK sidecar、自己实现协议或改在支持语言中运行。REST 不支持 speech translation。 | 自动检测候选至多 10；连续 LID 支持会话中语言切换，但不支持同一句内换语。按 Speech Translation 语言表/voice 表对四语与 region 验收。 | Speech resource key/region 或 token；SDK/token 只能受控服务端。停止自动工作意味着不创建 provider、不开新 key、不作为 Debug 顺序测试。 | **排除（产品决定）**。 |
| **Alibaba Cloud Model Studio（Gummy / Qwen realtime）** | Gummy 是实时 ASR+译文文本；Qwen realtime 家族可能输出音频，但具体模型、四语、地区和商用状态变动快。本次没有找到可同时证明“公开 GA、四语任意互译、译音频”的单一官方条款；不能写成已满足。 | 文档以 WSS 事件/音频 buffer 为主；Go 可原生 WS。Gummy 组合需另接 Qwen TTS Realtime 或其他 TTS；其 partial/final 事件须逐协议验证。 | 不把模型的通用“自动语言识别”外推为实时翻译 session LID。 | 需要阿里云/DashScope 账号、地区 endpoint 和 API key；现有配置字段表明仓库曾预留 DashScope，但这不表示当前可用。key 只在 Agent。 | **T3：中国区研究/拿到准确 model ID 后**。 |
| **Baidu Real-time Speech Translation** | 原生实时音频→译文，文档可选 `return_target_tts=true` 返回 TTS 二进制；官方语言表需每对查验，过往资料表明 z/en/vi，但本次未取得能同时确认 fr 与所有方向的当前表，故四语闭环 **未证实**。 | `wss://aip.baidubce.com/ws/realtime_speech_trans`；binary PCM 上行，MID/FIN；可有 MP3 TTS binary，Go 须解析其二进制响应格式。 | 本次证据未确认自动 LID/切换；按显式 source/target。 | 百度智能云 app key/secret/token、产品开通/配额；中国区与跨境数据/连通性需要单独评审。 | **T3：仅中国区专项 PoC**。 |
| **Tencent Cloud Real-time Speech Translation** | 原生 WSS 文本+可选 TTS；官方当前参数列 source/target 仅 zh/en/zh_en/ja/ko/yue/id/th/ru，**没有 vi 或 fr**；TTS 仅 target `zh`/`en`/`zh_en`。 | `wss://asr.cloud.tencent.com/asr/speech_translate/`；16 kHz/16-bit mono，推荐 200 ms/6400 B、1:1 速率；`sentence_end` final，否则覆盖式更新。官方有 Go + WSS 同传示例。 | `zh_en` 是中英混合，不是四语 LID。 | SecretId/SecretKey 的签名只能后端；默认并发 5，购买可提高。 | **排除（四语不满足）**。 |
| **Deepgram Flux / Nova + 外部 MT/TTS** | **仅 STT 组件**，不存在官方 Flux “实时翻译+译音频”功能。Flux Multilingual 的 10 语言是 en/es/fr/de/hi/ru/pt/ja/it/nl，**不含 zh、vi**，所以不满足本应用全语种 ASR。 | Flux `/v2/listen` WSS，建议 80 ms，`TurnInfo`/EndOfTurn；可用 Configure 更新 hints。若另选 Nova multilingual/MT/TTS，则是另一套三服务组合。官方 Go SDK 非主路径，WS 可直连。 | Flux 以无 hint 自动检测/事件 `languages` 表示 detected languages，可中途更新 hint；但不补足 zh/vi。 | API key 仅后端，EU endpoint 可用；需账户/价格/数据条款核验。 | **排除作为四语 provider；可作法英 STT 研究**。 |
| **OpenAI Realtime / Audio** | 官方 Realtime 可实时转写/音频对话；Audio `translations` endpoint 是**文件 multipart → 英语文本**，不是持续任意语言 S2S 翻译。没有官方文档可证明 Realtime transcription session 本身提供可靠的翻译模式或译音频契约。 | Realtime 是 WSS/WebRTC，能产生音频/转写事件；若用 LLM prompt “翻译”，它是生成式对话而非专门 speech-translation API，需另做安全与一致性评估，不能列为已支持的同传。 | 不应把模型多语能力写成 LID/自动 source switching 的 API 保证。 | 使用服务端 API key 或短期 client secret；计费/速率/数据保留按账户。 | **排除（不能作官方承诺的实时翻译）**。 |
| **ElevenLabs STT + MT + TTS** | **组合组件**：Realtime STT WS 有 partial/committed，TTS WS 支持增量文本；其 speech-to-speech API 是变声，非翻译。四语 STT/voice 要按当前语言表验收。 | 两个独立 WSS/HTTP 流，由本服务只把稳定 MT 片段送 TTS；Go 直接 WSS。 | 此次未确认四语 session LID。 | API key 后端、试用和数据处理以控制台为准。 | **T3：若优先声音质量再评估**。 |

## 关键的“不要误报”结论

### Google：Media Translation 与普通 Cloud Translation 不是一回事

- **Cloud Translation** 是文本服务，官方称“real-time translation”是请求级文本翻译，不能把它写成对一个音频流提供 WebSocket/gRPC 翻译增量。
- **Media Translation** 的官方 protobuf 确有 bidirectional `StreamingTranslateSpeech` 和 audio translation result，因而是应单独调查的潜在 S2S 路线；但 namespace 为 `v1beta1`，本报告没有找到足以承诺 2026-09 新项目全球开通/四语 voice 的产品页。因此列 T2，先让采购或控制台实际创建/测试。
- **Gemini Live Translate** 则是官方 2026-07 明确发布的 Preview 语音到语音翻译功能；可试，但 Preview 风险不可隐藏。

### Deepgram 与 OpenAI：都不能凭“realtime”名称算同传 provider

- Deepgram Flux 是 turn-aware realtime ASR；其官方 Flux Multilingual 语言表没有中文和越南语，也没有内置翻译/TTS。
- OpenAI `/audio/translations` 官方定义为上传音频并译成英语文本；这与持续语音翻译不同。Realtime 模型的自由文本指令不等于有 final/partial/译音频协议保证。

### 中国云：要区分 ASR、实时“字幕翻译”、语音翻译和 TTS

- Volcengine 当前 AST 已是最贴合现有 Agent 的 WSS 协议，但四语全互译不是官方 AST 规则；任何 vi↔fr 需求须走英语枢纽（且会增加延迟/误差）或换 provider。
- 腾讯实时翻译的上游接口清楚限定语言和 TTS target；不能因为腾讯实时 ASR 独立支持 vi/fr 就推断语音翻译也支持。
- 百度/阿里可保留专项试验，但在取得**具体产品版本、region、source×target、TTS voice** 的官方表或控制台回显前，不能进入“全局可用”承诺。

## 上游协议归一化和安全验收清单

### 统一 provider 行为（建议，不是本次代码变更）

1. audio ingress 固定为本应用既有 PCM s16le、mono、16 kHz；provider 需要其它格式时在 Agent 转码，禁止让浏览器按 provider 差异携带凭据。
2. 每个 provider 用 `(sessionGeneration, providerSegmentID, targetLanguage)` 做幂等键；partial 为**覆盖**，不拼接；收到 final 后封存段。
3. 只将 final 的译文送组合 TTS。原生 S2S 返回的音频也要带 segment/generation，在 stop、切向、打断、重连时丢弃旧代音频，防止串音。
4. 背压：禁止将音频快于实时无限写入。Speechmatics 需看 `AudioAdded`/socket buffer，腾讯明确要求 1:1；所有 provider 建有界队列、超限丢弃策略和 cancel。
5. 指标只记 metadata：采集时间、首 partial/translation/audio、final、播放、重连/错误类、region/provider；不记录原始音频、原文、译文、authorization header 或 key。

### 生产安全门槛

- provider key、service-account JSON、AWS long-lived access key、腾讯/火山/百度 secret 均不能进入 Debug UI、Android 包、Web bundle、日志、错误消息或截图。Debug UI 只发送 provider ID；Agent/云端从部署 secret 取得该 provider 的凭据。
- 若厂商支持浏览器直连（如 Gemini / Speechmatics），后端必须先鉴别本用户，再签发**短期、单次、最小 scope、绑定目标模型/地区/翻译配置**的令牌；仍需限速和用量归属。
- provider allowlist、TLS、精确 region endpoint、请求超时、session 最长时长、重连次数、并发/预算上限均服务端固定；不可由 Debug UI 任意输入 host 或 key。
- 在选择“全球可用”前，法务/安全需单独确认：销售主体可开户、音频/转写/日志的处理地区、训练/保留选择、DPA、子处理者、跨境传输、制裁/出口控制以及当地网络可达性。本报告不作法律结论。

## 建议的顺序试验计划（不实施）

| 阶段 | provider | 开通/密钥步骤 | 最小验收及淘汰条件 |
|---|---|---|---|
| 0 | Volcengine | 使用现有受控部署配置；不读取或显示值。确认实际产品 entitlement。 | 以现有 WSS 对照：zh↔en、en↔vi、en↔fr 的 partial/final、重连、延迟；vi↔fr 标记为不支持，不用隐式 fallback。 |
| 1 | Gemini Live Translate | 创建 Google AI/Gemini 项目、启用计费/API；后端创建受约束 ephemeral token。 | 四语六个方向；检查 16→24 kHz 音频转换、transcript 是否可稳定显示、10 分钟 GoAway/resumption、Preview 配额/价格。若任何方向或目标 voice 不可用则退出。 |
| 2 | Speechmatics + TTS | 创建独立试用/付费账号与后端 secret，选择 EU/US/AUS region。 | 英语枢纽六向，验证 STT partial、MT partial/final 和 TTS 首包；明确记录非英语互译是否是产品需求，若是则淘汰或补第二 MT。 |
| 3 | DeepL Voice | 向 DeepL 确认 Voice API entitlement、可售地区、每个 source/target/voice，取得专用 key。 | 50–250 ms chunks；tentative/concluded 和 target_media_chunk；先验收 vi/fr ASR、翻译及语音，缺一个即不加入 provider 列表。 |
| 4 | AWS / Google Cloud 组合 | 仅已有合规云账户时：最小 IAM/service account、限额/预算、所有服务同区域或明确跨区。 | 固定源语言全四语；AWS 自动 LID + vi 不纳入验收。比较 final 断句到首音频、错误恢复、每分钟成本。 |
| 5 | 阿里/百度 | 只在中国区产品、帐号实体、数据路径获批后按最新具体 model/API 开通。 | 用同一语料证明四语 source×target×TTS；拿不到官方当前矩阵即停止。 |

**共同压测脚本**：每方向各 2–3 分钟，包含短句、连续 20–40 秒发言、噪声、停顿、数字/人名、说话中打断、网络 1–3 秒断连、会话切换。记录 p50/p95：capture→partial、capture→final translation、final→首 audio、capture→播放；同时记录错句、重复播报、旧 generation 串音、重连成功率、每分钟实价。绝不以厂商“低延迟”营销数字替代以上实测。

## 官方一手来源

### Volcengine / 中国云

- [Volcengine 音频理解：AST 语种与互译规则](https://docs.volcengine.com/docs/82379/2377589?lang=zh)
- [Volcengine 流式 ASR WebSocket 协议](https://docs.volcengine.com/docs/6561/80818?lang=zh)
- [Tencent Cloud 实时语音翻译（WebSocket）](https://cloud.tencent.com/document/product/1093/127565)
- [Tencent Cloud 官方 Go + WebSocket 同传示例](https://cloud.tencent.com/document/product/1093/130882)
- [Baidu 实时语音翻译 API](https://ai.baidu.com/ai-doc/MT/Sl9p2h5k9)
- [Alibaba Model Studio 实时语音翻译（Gummy）](https://help.aliyun.com/zh/model-studio/real-time-speech-translation)
- [Alibaba Qwen realtime WebSocket API](https://help.aliyun.com/zh/model-studio/real-time-websocket-api)

### Google

- [Gemini Live Translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate)
- [Gemini Live API WebSocket quickstart](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket)
- [Gemini Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management)
- [Gemini Live ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens)
- [Cloud Media Translation v1beta1 RPC reference](https://cloud.google.com/translate/media/docs/reference/rpc/google.cloud.mediatranslation.v1beta1)
- [Cloud Speech-to-Text V2 streaming](https://cloud.google.com/speech-to-text/v2/docs/streaming-recognize)
- [Cloud Translation supported languages](https://cloud.google.com/translate/docs/languages)

### Speechmatics / DeepL / Deepgram / ElevenLabs

- [Speechmatics realtime WebSocket reference](https://docs.speechmatics.com/api-ref/realtime-transcription-websocket)
- [Speechmatics realtime translation](https://docs.speechmatics.com/speech-to-text/features/translation)
- [Speechmatics language / translation pair table](https://docs.speechmatics.com/speech-to-text/languages)
- [Speechmatics streaming TTS quickstart](https://docs.speechmatics.com/text-to-speech/quickstart)
- [DeepL Voice overview](https://developers.deepl.com/docs/voice/overview)
- [DeepL Voice WebSocket streaming](https://developers.deepl.com/api-reference/voice/websocket-streaming)
- [Deepgram Flux language prompting and language list](https://developers.deepgram.com/docs/flux/language-prompting)
- [Deepgram Flux protocol reference](https://developers.deepgram.com/reference/speech-to-text/listen-flux)
- [ElevenLabs realtime STT WebSocket](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)
- [ElevenLabs TTS stream input WebSocket](https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input)
- [ElevenLabs speech-to-speech stream](https://elevenlabs.io/docs/api-reference/speech-to-speech/stream)

### AWS / Microsoft / OpenAI

- [Amazon Transcribe streaming](https://docs.aws.amazon.com/transcribe/latest/dg/streaming.html)
- [Amazon Transcribe streaming language identification](https://docs.aws.amazon.com/transcribe/latest/dg/lang-id-stream.html)
- [Amazon Transcribe supported languages](https://docs.aws.amazon.com/transcribe/latest/dg/supported-languages.html)
- [Amazon Polly bidirectional streaming](https://docs.aws.amazon.com/polly/latest/dg/bidirectional-streaming.html)
- [Amazon Translate API](https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html)
- [Azure Speech translation overview](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-translation)
- [Azure speech translation how-to](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-translate-speech)
- [Azure language identification](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-identification)
- [OpenAI audio API reference](https://platform.openai.com/docs/api-reference/audio)
- [OpenAI Realtime API overview](https://platform.openai.com/docs/guides/realtime)

## 尚未由公开文档关闭的风险

- “支持某语言”必须拆为：**实时 ASR input、MT source→target、目标 TTS voice、指定 region/account 的可用性**。任何一项缺失都不是四语 S2S 支持。
- 价格、free trial、限额和全球销售资格高度随帐户/地区变化；官方通用文档不能替代创建项目后的 pricing/quotas 页面或销售报价。采购前不做成本承诺。
- Preview/beta（Gemini Live Translate、Google Media Translation v1beta1 surface、某些阿里模型）可能变更协议、区域或价格，必须与 GA provider 并行保留 fallback。
- 当前报告在 2026-09 有效；实施前重新打开每个官方链接与目标控制台，记录页面更新日期、model version、region、voice 和价格快照。
