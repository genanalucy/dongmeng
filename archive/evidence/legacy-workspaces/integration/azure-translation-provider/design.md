> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/design.md`；原始 SHA-256 `596905e6bb8063a1f8d5669a06240408e8b59fc3403df154e04416cb6571eb4b`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Azure 翻译引擎集成设计

Status: confirmed（grilling 定案 2026-09-10）
日期：2026-09-10

## 背景与目标

当前 agent 仅支持火山 AST 一条上游，英越翻译质量不达标。已开通 Azure Speech（japaneast，F0 免费层，密钥已配置到 `/etc/dngmeng/translator-agent.env`）。

目标：

1. 保留火山引擎为默认上游，行为与现状完全一致。
2. 测试版（Debug 包）可在设置中选择翻译引擎（火山 / Azure），新会话按选择走对应上游。
3. 先跑通固定源语言的全流程（字幕 + TTS 语音），验收英越质量。
4. 为第二阶段"自动识别源语言、免按键面对面"预留协议位置。

非目标（本阶段）：

- 不改正式版默认行为与 UI。
- 不做多账号/配额管理。
- 不做 LID（语言自动检测）——列为阶段 2。
- 不改计费/授权模型（provider 不影响会话授权与用量上报）。

## 关键决策

### D1 会话级 provider 参数（start 消息）

客户端 start 消息新增可选字段 `provider`：

- `"volcengine"`（默认，省略字段等同此值，老客户端完全兼容）
- `"azure"`

服务端 `parseStart` 同步扩展（现有 `DisallowUnknownFields` 严格校验）。`provider=azure` 但服务端未配置 `AZURE_SPEECH_KEY` 时，start 直接拒绝，错误码 `TRANSLATION_PROVIDER_UNAVAILABLE`。

### D2 agent 内 Provider 路由 Client

`ast.Client` 接口不变（`Start(ctx, StartRequest, EventSink) (Session, error)`）。新增组合实现 `providerRouter`：按 `StartRequest.Provider` 分发到火山 `officialClient` 或新的 Azure 客户端。server.go 只依赖接口，注入点不变。

### D3 Azure 管道拆分：ASR+MT 与 TTS 分离

Azure Speech Translation 的内置语音合成（translation synthesis）**不支持越南语**（仅约 30 种语言，vi 缺席；已用 voices/list 证实 TTS 服务本身有 57 个 vi 声音）。因此：

- **ASR + MT**：Azure Speech Translation WebSocket（流式，输出 source/translation 的 hypothesis/final 文本）。
- **TTS**：agent 收到 `translation_final`（整句）后调用 Azure TTS REST（SSML → `raw-16khz-16bit-mono-pcm`），合成的 PCM 走现有 `tts_audio` 事件发给客户端。

客户端协议与音频消费路径**零改动**。句子级合成延迟约 0.5–1s/句，与火山当前行为同量级。

### D4 Go 自研 Azure Speech WebSocket 最小客户端

Azure 无官方 Go SDK。按公开的 Speech Service WebSocket 协议实现最小子集：

- `speech.config`（输出格式 detailed、翻译语音设置）
- 连接 URL：`wss://japaneast.stt.speech.microsoft.com/speech/translation/cognitiveservices/v1?...&from=zh-CN&to=en&to=vi`（语言映射 zh/en → BCP-47）
- 二进制音频帧（16k mono PCM16 直传，与客户端现有 2560B/80ms 帧兼容）
- 文本消息解析：`speech.hypothesis/speech.phrase` → `source_partial/source_final`；`translation.hypothesis/translation.result` → `translation_partial/translation_final`
- `turn.end` → 内部按句完成；客户端 `Finish()` → 停发音频，等待最终 `turn.end` 后 emit `finished`（幂等，与火山语义对齐）
- telemetry 消息按协议要求发送最小合规内容

### D5 事件映射与现有 sink 复用

Azure Session 实现现有 `ast.Session`（SendAudio/Finish/Close）与 `EventSink` 事件类型，`server.go` 的事件转发、PCM 校验、日志、`finished` 处理全部复用。

### D6 App 侧：Debug 设置“翻译引擎”+ 声音选择

- 引擎：`SharedPreferences` key `translation_provider`（`volcengine` | `azure`，默认 `volcengine`）；**全局开关**，仅影响新会话。仅 `BuildConfig.DEBUG` 显示。
- 声音（定案：App 可配）：引擎选择旁每语言一个声音下拉，固定候选列表（每语言 2-3 个常用神经声，从 voices/list 挑选）；未选时用默认声。仅 Debug 显示，阶段 2 再评估正式化。
- 生效：`AgentSocket.start` 组装 start 消息时读设置带 `provider`；设置修改只影响新会话。

### D7 配置与运维（定案）

- `AZURE_SPEECH_KEY` / `AZURE_SPEECH_REGION`（已就位，权限 600）。
- F0 并发 1 路：**接受，429 明确报错**（“Azure 免费额度并发受限，请稍后重试或切回默认引擎”），不排队。
- Azure 会话中途失败：**直接报错，不自动回退火山**（不偷换引擎）。
- 服务端不限制 provider=azure 的使用面（将来正式版上线给真实用户）。
- 上线切 S0：默认 100 并发，语音翻译 $2.50/小时 + TTS ≈$15/1M 字符；50 并发按用量计费（满载 ~$131/小时，月度高负载估计 ~$7.5K）。

### D8 阶段 2：免按键自动模式（定案：入口先行，功能后补）

- 本轮仅在面对面右上角三点菜单加“自动模式”入口，点击提示“功能开发中”；不做 LID。
- start 消息解析层仍预留 `candidateLanguages` 字段（零成本，阶段 2 不改协议）。
- 阶段 2 内容：Azure LID（限定候选集）+ subtitle 携带检测语言 + 客户端按检测结果路由播放（免按键）。

## 数据流（provider=azure）

```
App (Debug, 引擎=Azure)
 └─ start {provider:"azure", from:"zh", to:"vi", 会话授权}
agent server
 ├─ 授权/governance 校验（不变）
 ├─ providerRouter → azureClient.Start
 │   ├─ WebSocket wss://japaneast.stt.../speech/translation/v1?from=zh-CN&to=vi-VN
 │   └─ 事件映射 → sink（source_partial/final, translation_partial/final）
 ├─ translation_final(整句) → Azure TTS REST (vi-VN 声音, raw-16khz-16bit-mono-pcm)
 │   └─ PCM → tts_audio（复用现有校验/转发）
 └─ Finish → 停发音频 → 等最终 turn.end → finished
```

## 语言代码映射

| 内部 | 火山 | Azure |
|------|------|-------|
| zh | zh | zh-CN |
| en | en | en-US |
| vi | — | vi-VN |

（vi 火山侧现状如何不在本设计范围；App 端语言列表当前支持集由服务端 `isSupportedLanguage` 控制不变。）

## 测试计划

Go（agent）：

- `azurespeech` 协议客户端单测：httptest WebSocket 假上游，覆盖 speech.config 帧、音频帧、hypothesis/final 映射、turn.end、Finish 幂等、上游错误、断连。
- TTS REST 客户端单测：SSML 组装、PCM 输出校验、429/401 错误映射。
- providerRouter 单测：默认 volcengine、azure 未配置拒绝、azure 配置后路由正确。
- server 集成测试：start 带 provider 字段解析、老客户端省略字段回归。

Android：

- start 消息序列化契约测试（带/不带 provider）。
- 设置存储读写测试、Debug 可见性测试。

真机/线上验收：

- 服务器侧真实 Azure 会话冒烟（中→越、英→越、中→英各一轮）。
- Debug 包选择 Azure：对话/同传/面对面字幕+语音全流程；英越质量主观对比。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| Azure WS 协议自研踩坑（telemetry/分片细节） | 最小子集实现 + 假上游全覆盖单测；线上冒烟先行 |
| vi 无内置 synthesis | 已规避（独立 TTS 按句合成） |
| F0 并发 1 / 429 | 明确错误透传；测试期可接受；正式化时升 S0 |
| TTS 按句合成延迟 | 与火山同量级；阶段 2 可评估流式合成 |
| 学生订阅区域受限（japaneast） | 已验证可用 |
| 密钥泄露 | 已 600 权限；可随时 Portal 轮换 |

## 阶段拆分（建议任务）

1. `azurespeech` WS 协议客户端 + TTS REST 客户端（Go，含单测）
2. providerRouter + start 消息 provider 字段 + 错误码（Go server，含单测/集成测试）
3. 服务器部署 + 真实 Azure 冒烟（中越/英越/中英）
4. App：引擎设置项（Debug）+ start 消息贯通（含契约测试）
5. 测试版 OTA 发布 + 真机验收（英越质量对比）
6.（阶段 2，另立）LID 免按键自动面对面

## 验收标准

- 不选择引擎时，全链路行为与当前版本一致（回归）。
- Debug 包选择 Azure 后，三种语言对字幕与语音输出正常，英越质量明显改善。
- Go/Android 全部单测与 lint 通过；正式版构建无设置项暴露。
