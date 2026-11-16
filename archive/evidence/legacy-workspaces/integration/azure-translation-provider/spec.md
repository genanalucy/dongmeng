> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/spec.md`；原始 SHA-256 `e32837aec957d22e3206ced97f2c10d5aa582fe028aa601c9d60d650fc5fc148`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Azure 翻译引擎集成规格

Status: ready-for-agent
Labels: ready-for-agent
设计参考：同目录 design.md（grilling 定案 2026-09-10）

## Problem Statement

用户实测发现火山引擎的英越翻译质量不达标（中越、中英可接受）。当前 agent 只有火山 AST 一条上游，无法对比或替换引擎，也无法验证“换引擎能否解决英越质量”。同时用户希望未来支持免按键的自动语言检测面对面模式。

## Solution

Agent 支持双翻译引擎（火山 / Azure）按会话选择：测试版 App 在 Debug 设置中选择引擎与每语言 TTS 声音，新会话按选择路由到对应上游。默认保持火山，存量行为零变化。Azure 管道 = Azure Speech Translation WebSocket（ASR+MT 文本）+ Azure TTS REST 按句合成（规避 Azure 语音翻译内置合成不支持越南语的限制），客户端协议与音频消费路径零改动。Azure 自动面对面已实现为按 phrase 分段：每段独立 Azure universal v2 AtStart-LID session，完整 final pair 立即停采集+Finish，finished 后用同一 cloud grant 启动下一段；旧段 TTS 仍可排队播放，切段窗口音频丢弃以防跨方向混入。

## User Stories

1. As a 测试版用户, I want 在 Debug 设置中选择翻译引擎（火山/Azure）, so that 我能对比英越质量并验证新引擎。
2. As a 测试版用户, I want 每种语言选择 TTS 声音, so that 合成语音符合偏好。
3. As a 测试版用户, I want 不选择时一切与现状一致, so that 默认体验零风险。
4. As a 测试版用户, I want 设置只影响新会话, so that 进行中的翻译不被打断或切换。
5. As a 测试版用户, I want 切到 Azure 后对话/同传/面对面全流程正常出字幕和语音, so that 我能真实评估英越质量。
6. As a 测试版用户, I want 面对面右上角三点菜单有“自动模式”入口, so that 我知道免按键功能即将到来（点击提示开发中）。
7. As a 用户, I want Azure 免费层并发占满时得到明确错误提示, so that 我知道该重试还是切回默认引擎。
8. As a 用户, I want Azure 会话失败时立即看到明确报错, so that 我知情并可手动切回火山。
9. As a 正式版用户, I want 看不到任何引擎/声音设置和自动模式入口, so that 正式版界面与行为保持稳定。
10. As a 产品维护者, I want provider 作为会话级参数贯通协议, so that 引擎选择可审计、可回退。
11. As a 产品维护者, I want start 消息解析层预留 candidateLanguages 字段, so that 阶段 2 语言自动检测不用改协议。
12. As a 产品维护者, I want provider=azure 未配置密钥时启动即拒绝, so that 缺配置不会半途失败。
13. As a 产品维护者, I want 引擎路由在 agent 内部实现且火山路径不动, so that 回归风险最小。
14. As a 产品维护者, I want Azure 上游错误透传明确错误码, so that 客户端能展示可理解的提示。
15. As a 产品维护者, I want TTS 按句合成严格保序, so that 多句语音不会乱序播放。
16. As a 产品维护者, I want Finish 语义与火山一致（幂等、终态 finished）, so that 客户端松开按键逻辑复用。
17. As a 产品维护者, I want 全链路单测覆盖协议映射与状态机, so that 无真实 Azure 也能回归。

## Implementation Decisions

- **协议**：start 消息新增可选 `provider`（`volcengine`|`azure`，缺省=volcengine，老客户端兼容）与可选 `candidateLanguages`。仅 Azure AUTO 可携带严格两个、不同且受支持的当前面对面语言；服务端和 Azure client 都拒绝第三语言/重复/不支持候选，避免第三语言 LID 导致错误路由。provider=azure 但服务端无 `AZURE_SPEECH_KEY/REGION` 时 start 拒绝，错误码 `TRANSLATION_PROVIDER_UNAVAILABLE`。
- **引擎粒度（定案）**：全局开关——App 设置选择后所有新会话走所选引擎，不做按语言对路由。
- **agent 路由**：新增组合 Client 实现 `ast.Client` 接口，按 `StartRequest.Provider` 分发火山 `officialClient` 或 Azure 客户端；server.go 只依赖接口，注入点不变。
- **Azure 管道拆分（关键决策）**：Azure Speech Translation 的内置合成不支持越南语，故 ASR+MT 走 WS，普通会话使用 `/speech/translation/cognitiveservices/v1`；AUTO 使用 `/speech/universal/v2` 与 `speech.context.languageId.mode=DetectAtAudioStart`，每个 logical segment 均新建连接，绝不使用单一 Continuous session。Azure final 带 candidate 中的 `detected_language` 后才输出 finals；TTS 由 agent 收到 translation_final 后调 Azure TTS REST（`raw-16khz-16bit-mono-pcm`，与客户端 PcmPacketizer 对齐）经 `tts_audio` 事件下发。
- **语言映射**：zh→zh-CN、en→en-US、fr→fr-FR、vi→vi-VN；无映射即拒绝。
- **WS 协议要点**：连接后发 speech.config；音频帧带 2 字节大端长度前缀；`translation.hypothesis/result` 的 text 与 translations.{to} 分别映射 source/translation partial/final；turn.start/end 管理话语边界；speech.event(telemetry) 回最小遥测；错误→ `AZURE_SESSION_FAILED`。
- **Finish 语义**：幂等；若当前话语活跃则等下个 turn.end，否则立即 finished；finished 在 TTS 队列排空后发出。
- **TTS 保序**：文本事件、按句合成、finished 在单一 pump 通道严格串行；TTS 失败 emit `AZURE_TTS_FAILED` error 并终止会话。
- **失败策略（定案）**：Azure 中途失败直接报错，不自动回退火山；429 明确提示并发受限，不排队。
- **服务端门槛（定案）**：不限制 provider=azure 的使用面（将来正式版上线给真实用户）。
- **声音（定案）**：App 设置里每语言下拉选声音（固定候选：每语言 2 个常用神经声）；未选时用代码内默认声（如 vi-VN-HoaiMyNeural）。仅 Debug 显示。
- **App 存储**：引擎 `translation_provider`、声音按语言 key 存 SharedPreferences；`AgentSocket.start` 组装时读取带上；仅 `BuildConfig.DEBUG` 显示设置 UI。
- **自动面对面（定案）**：Azure AUTO 的 coordinator 显式执行“final pair 完结”转移：停 capture 后 Finish 当前 socket；只有该 socket `finished`，且仍处于 AUTO/PROCESSING、未 pause/stop/cancel/错误时，ViewModel 才以既有 cloud grant 创建下一 socket。旧 finished entry 留在 TTS 队列，new active entry 可同时存在。每段的 detected language 确定 source/target 与相反耳路由，因此说话人绝不听到自己的翻译。非 Azure 保留既有手动接管兼容行为。
- **配置**：`AZURE_SPEECH_KEY/AZURE_SPEECH_REGION`（已就位于 translator-agent.env，600 权限）。
- **部署（定案）**：直接部署生产 agent（默认火山零变化）→ session-test-harness 真实 Azure 冒烟 → 测试版 OTA → 用户真机验收英越质量 → 通过后再发正式版。
- **成本基线**：上线切 S0，默认 100 并发，$2.50/音频小时 + TTS ≈$15/1M 字符；50 并发满载 ≈$131/小时，按用量计费。

## Testing Decisions

- 测试验证外部行为：事件序列与顺序、Finish 幂等与终态、失败错误码、协议帧格式（长度前缀、speech.config、URL 参数）、配置缺失拒绝；不测内部 goroutine 结构。
- **Go 单测（假上游）**：httptest+websocket 假 Azure 服务器：验证 dial URL（language/to 参数）、认证头、speech.config 先发、音频 2 字节前缀与载荷长度一致、hypothesis/result→四类事件映射与顺序、translation_final 触发假 TTS 后 tts_audio 保序、Finish 幂等且终态 finished 恰一次、TTS 失败→AZURE_TTS_FAILED、零配置/不支持语言拒绝。
- **Go 路由测试**：provider 缺省→火山；azure 未配置→拒绝；azure 已配置→路由 Azure；复用 server_test 假 session 模式验证 start 字段解析（含 candidateLanguages）。
- **Android 测试**：start 消息带/不带 provider 的序列化契约；设置读写默认值；Debug 可见性（正式构建不显示）；Azure AUTO A→B finished 后 B→A 新段可在 A TTS drain 时创建且耳路由正确；pause/stop/cancel 在 finished 前不续转；history 只接收 final pair+finished。
- **冒烟（真实环境）**：服务器上 harness 对真实 Azure 跑中→越、英→越、中→英各一轮，校验字幕与 PCM 输出；英越质量由用户真机主观验收（用户明确：语料对照方案后续再议，先交付代码）。
- 参考既有测试：ast/official_client_test.go 的会话状态断言、server_test.go 的 fakeSession 与 start 校验、Android CloudApi 契约测试。

## Out of Scope

- Azure 候选语言以外的第三语言自动翻译或路由（严格拒绝以避免错误路由）。
- 按语言对自动路由引擎。
- TTS 声音正式版 UI、流式合成优化。
- 质量语料对照验收脚本（用户后续再定）。
- 多账号/配额管理、S0 升级与正式版上线流程（验收通过后另立变更）。
- 火山路径的任何行为变更。

## Further Notes

- Azure WS 协议细节（长度前缀、telemetry 内容）以实现时官方协议文档为准，冒烟校准；单测锁住我们自己的映射与状态机。
- TTS 按句合成延迟约 0.5–1s/句，与火山同量级。
- 发布顺序硬约束：生产 agent 部署 → 冒烟 → 测试版 OTA → 用户验收 → 正式版。
