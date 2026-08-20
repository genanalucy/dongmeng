# Web 实时翻译 App（macOS 测试版）完整实现规格

**版本：** V1.0  
**目标环境：** macOS + Chrome  
**前端：** React + TypeScript + Vite  
**本地代理：** Go Local Agent  
**翻译服务：** 火山引擎同声传译 2.0 / AST 2.0  

## 核心模式

1. 单人同声传译
2. 面对面双人翻译（核心特色）

---

## 1. 文档目的

本文档直接提供给 Coding Agent。

Agent 应按照本文档完成一个可以在 Mac 上实际运行和测试的 Web MVP。

第一阶段不追求：

- Safari 兼容
- Windows 兼容
- Android
- 用户系统
- 云端 Backend
- 数据库存储
- 历史记录
- AI 总结
- 支付
- 多设备蓝牙输出

第一阶段只验证三个最关键技术链路：

```text
1. Mac 内置麦克风
   ↓
   Web Audio
   ↓
   火山同声传译
   ↓
   Web 实时字幕 + 翻译语音

2. 一副 TWS 蓝牙耳机
   ↓
   左右声道独立输出

3. 面对面 PTT
   ↓
   A → B
   B → A
   ↓
   翻译只播放给对方耳机
```

如果这三个链路跑通，则 Web MVP 核心技术验证完成。

---

## 2. 产品定义

Web App 提供两个入口：

```text
┌────────────────────────────┐
│                            │
│        实时 AI 翻译         │
│                            │
│  ┌──────────────────────┐  │
│  │ 🎧 单人同声传译      │  │
│  │                      │  │
│  │ 会议 / 演讲 / 沟通   │  │
│  └──────────────────────┘  │
│                            │
│  ┌──────────────────────┐  │
│  │ 🎧🎧 面对面翻译      │  │
│  │                      │  │
│  │ 两人各戴一只耳机     │  │
│  └──────────────────────┘  │
│                            │
└────────────────────────────┘
```

---

## 3. 功能一：单人同声传译

### 3.1 使用场景

用户自己戴一副耳机。

例如用户希望：

```text
English → 中文
```

用户点击：

```text
开始同传
```

之后持续使用 Mac 麦克风采集环境声音。

完整数据流：

```text
讲话 / 会议 / 视频外放
        ↓
Mac 内置麦克风
        ↓
Browser getUserMedia
        ↓
AudioWorklet
        ↓
16kHz / PCM16 / Mono
        ↓
Local Agent
        ↓
Volcengine AST S2S
        ↓
原文字幕
        ↓
翻译字幕
        ↓
翻译语音 PCM
        ↓
Web Audio
        ↓
Left + Right
        ↓
双耳播放
```

单人模式：

```text
Output Left  = Translation
Output Right = Translation
```

即左右耳都播放翻译。

---

## 4. 功能二：面对面翻译

这是产品 V1 的核心功能。

假设：

```text
A = 中文用户
B = English 用户
```

使用：

```text
一副 TWS 蓝牙耳机
```

两人各戴一边：

```text
A → 左耳机
B → 右耳机
```

页面显示：

```text
┌────────────────────────────────┐
│          面对面翻译            │
│                                │
│      左耳             右耳     │
│                                │
│      中文      ⇄      English │
│                                │
│   ┌─────────┐     ┌─────────┐ │
│   │ 按住说话 │     │ Hold    │ │
│   │  中文   │     │ English │ │
│   └─────────┘     └─────────┘ │
│                                │
│           ● 已就绪             │
│                                │
│      测试左耳    测试右耳      │
│                                │
│          结束翻译              │
└────────────────────────────────┘
```

---

## 5. 面对面模式基本规则

左耳和右耳分别代表两个参与者。

定义：

```text
LEFT Participant
language = leftLanguage

RIGHT Participant
language = rightLanguage
```

例如：

```text
leftLanguage  = zh
rightLanguage = en
```

则自动得到两个翻译方向：

```text
LEFT → RIGHT

zh → en

输出目标：
RIGHT
```

以及：

```text
RIGHT → LEFT

en → zh

输出目标：
LEFT
```

用户点击：

```text
⇄
```

之后：

```text
leftLanguage  = en
rightLanguage = zh
```

两个翻译方向同时自动反转。

---

## 6. A 讲话流程

假设：

```text
LEFT  = 中文
RIGHT = English
```

左侧用户 A 按住：

```text
按住说中文
```

立即开始：

```text
pointerdown
    ↓
start microphone capture
    ↓
创建 Translation Turn
    ↓
source = zh
target = en
targetEar = RIGHT
```

用户说：

```text
你好，请问你叫什么名字？
```

音频流程：

```text
Mac 内置麦克风
      ↓
AudioWorklet
      ↓
PCM16 / 16k / Mono
      ↓
Local Agent
      ↓
Volcengine AST

source_language = zh
target_language = en
mode = s2s
```

火山返回英文翻译与对应 TTS PCM。

浏览器将单声道 TTS 转成：

```text
LEFT  = 0
RIGHT = Translation PCM
```

最终：

```text
A 左耳：
静音

B 右耳：
Hello, what's your name?
```

---

## 7. B 讲话流程

B 按住右侧按钮。

配置：

```text
source = en
target = zh
targetEar = LEFT
```

B：

```text
My name is Jack.
```

火山返回：

```text
我叫杰克。
```

浏览器输出：

```text
LEFT  = Translation PCM
RIGHT = 0
```

最终：

```text
A 左耳：
我叫杰克。

B 右耳：
静音
```

---

## 8. V1 必须采用半双工模式

面对面翻译第一版禁止双方同时讲话。

状态始终只能是：

```text
READY
LEFT_SPEAKING
LEFT_TRANSLATING
RIGHT_SPEAKING
RIGHT_TRANSLATING
```

绝不能出现：

```text
LEFT_SPEAKING + RIGHT_SPEAKING
```

当左边按下时：

```text
右侧按钮 disabled
```

当右边按下时：

```text
左侧按钮 disabled
```

翻译语音尚未播放完成之前，另一边按钮同样保持 disabled。

以上约束适用于默认的“手动 PTT”模式。产品另提供用户显式开启的“自动交替”模式：点击开始后左侧默认持续录音；右侧按住抢话时立即结束左侧输入并开始右侧输入，松开后立即恢复左侧。自动模式仍禁止左右麦克风同时采集，但允许已结束 Turn 的翻译和 TTS 与下一 Turn 录音并行。多个后台 Turn 必须保持独立 Session、字幕气泡和 PCM 路由，TTS 按 Turn 创建顺序播放，禁止跨 Turn 音频交错。点击停止只停止新录音和自动恢复，已结束输入的 Turn继续完成；离页、设备断开或全局错误取消全部 Turn。

流程必须严格为：

```text
A 讲话
 ↓
翻译
 ↓
B 听完
 ↓
READY
 ↓
B 讲话
 ↓
翻译
 ↓
A 听完
 ↓
READY
```

---

## 9. 为什么需要 Local Agent

Web 页面不能直接安全地连接火山 AST。

火山 AST 接口：

```text
wss://openspeech.bytedance.com/api/v4/ast/v2/translate
```

WebSocket 建连时需要 HTTP Header：

```text
X-Api-Key
X-Api-Resource-Id
```

Resource ID：

```text
volc.service_type.10053
```

浏览器标准 WebSocket API 无法自由附加这些自定义 Header，而且不能把长期 API Key 放进浏览器代码。

因此采用：

```text
Browser
 ↓
localhost WebSocket
 ↓
Go Local Agent
 ↓
Volcengine WebSocket
```

---

## 10. 总体架构

```text
┌─────────────────────────────────────────┐
│                Browser                  │
│                                         │
│ React                                   │
│                                         │
│ ┌───────────────┐  ┌─────────────────┐ │
│ │ Microphone    │  │ Stereo Player   │ │
│ │ AudioWorklet  │  │ Web Audio       │ │
│ └───────┬───────┘  └────────▲────────┘ │
│         │                    │           │
│         │ PCM16              │ PCM16     │
│         ▼                    │           │
│        TranslationClient ────┘           │
│                  │                       │
└──────────────────┼───────────────────────┘
                   │
             localhost WS
                   │
                   ▼
┌─────────────────────────────────────────┐
│              Go Local Agent             │
│                                         │
│ WebSocket Server                        │
│ TranslationSession                      │
│ VolcengineClient                        │
│ Protobuf Encoder / Decoder              │
└──────────────────┬───────────────────────┘
                   │
         WSS + Protobuf + API Key
                   │
                   ▼
        ┌─────────────────────┐
        │ Volcengine AST 2.0  │
        └─────────────────────┘
```

---

## 11. 技术栈

### Web

使用：

```text
React
TypeScript
Vite
Zustand
Web Audio API
AudioWorklet
WebSocket
```

不要使用：

```text
Next.js
Electron
WebRTC
Redux
复杂 UI 框架
```

MVP 不需要这些依赖。

CSS 可以使用：

```text
普通 CSS Modules
```

或者：

```text
Tailwind CSS
```

任选其一。

### Local Agent

使用 Go。

职责只有：

```text
WebSocket localhost server
+
Volcengine WebSocket client
+
Protobuf 编解码
+
API Key 管理
```

Local Agent 不处理麦克风。  
Local Agent 不处理声道。  
Local Agent 不播放声音。  

所有音频设备相关逻辑都放 Browser。

---

## 12. 项目目录

```text
translator/
│
├── web/
│   │
│   ├── src/
│   │   ├── app/
│   │   ├── pages/
│   │   │   ├── HomePage.tsx
│   │   │   ├── SinglePage.tsx
│   │   │   └── FaceToFacePage.tsx
│   │   │
│   │   ├── components/
│   │   │   ├── LanguageSelector.tsx
│   │   │   ├── DeviceSelector.tsx
│   │   │   ├── PushToTalkButton.tsx
│   │   │   ├── SubtitlePanel.tsx
│   │   │   ├── ConnectionStatus.tsx
│   │   │   └── EarTestPanel.tsx
│   │   │
│   │   ├── audio/
│   │   │   ├── MicrophoneService.ts
│   │   │   ├── PcmCaptureService.ts
│   │   │   ├── StereoAudioPlayer.ts
│   │   │   ├── AudioDeviceService.ts
│   │   │   └── pcm-worklet.ts
│   │   │
│   │   ├── translation/
│   │   │   ├── TranslationClient.ts
│   │   │   ├── TranslationTypes.ts
│   │   │   └── TranslationController.ts
│   │   │
│   │   ├── face/
│   │   │   ├── FaceToFaceController.ts
│   │   │   └── FaceToFaceState.ts
│   │   │
│   │   ├── single/
│   │   │   └── SingleTranslationController.ts
│   │   │
│   │   └── store/
│   │
│   ├── public/
│   ├── package.json
│   └── vite.config.ts
│
├── agent/
│   │
│   ├── cmd/
│   │   └── translator-agent/
│   │       └── main.go
│   │
│   ├── internal/
│   │   ├── server/
│   │   │   ├── http.go
│   │   │   └── websocket.go
│   │   │
│   │   ├── ast/
│   │   │   ├── client.go
│   │   │   ├── session.go
│   │   │   ├── encoder.go
│   │   │   └── decoder.go
│   │   │
│   │   └── config/
│   │       └── config.go
│   │
│   └── go.mod
│
├── third_party/
│   └── volcengine/
│       └── ast/
│           └── protobuf/
│
├── scripts/
├── Makefile
└── README.md
```

---

## 13. 火山 Protobuf

不要自行猜测或重新设计火山 Protobuf。

必须使用火山官方提供的 AST 2.0 Protobuf 文件或者官方 Go Demo 中的实现。

将官方代码整理进：

```text
third_party/volcengine/ast/
```

业务代码再封装：

```text
VolcengineClient
```

禁止 UI 或 WebSocket server 直接操作 protobuf。

---

## 14. 火山 AST 配置

连接：

```text
wss://openspeech.bytedance.com/api/v4/ast/v2/translate
```

Header：

```text
X-Api-Key: ${VOLCENGINE_API_KEY}
X-Api-Resource-Id: volc.service_type.10053
```

Agent 应记录握手返回的 `X-Tt-Logid` 用于错误排查，但禁止记录 API Key。

---

## 15. AST 请求模式

V1 两个功能全部使用：

```text
mode = s2s
```

因为我们同时需要：

```text
字幕
+
翻译语音
```

主要事件：

```text
StartSession = 100
TaskRequest = 200
FinishSession = 102

SessionStarted = 150
SourceSubtitleResponse = 651
TranslationSubtitleResponse = 654
TTSResponse = 352
SessionFinished = 152
SessionFailed = 153
```

---

## 16. 输入音频规格

无论 Mac 麦克风实际是多少采样率，发给 AST 的数据统一为：

```text
PCM
16kHz
16 bit
Mono
```

标准参数：

```text
rate    = 16000
bits    = 16
channel = 1
codec   = raw
```

每包采用：

```text
80 ms
```

80ms × 16000：

```text
1280 samples
```

PCM16：

```text
1280 × 2 = 2560 bytes
```

因此标准浏览器发送包：

```text
2560 bytes
```

---

## 17. 输出音频规格

StartSession 中设置：

```text
target_audio.format = pcm
target_audio.rate = 16000
```

V1 统一选择 16kHz PCM16，减少浏览器解码复杂度。

因此：

```text
TTSResponse.data
```

统一按：

```text
PCM16 mono / 16000Hz
```

处理，再交给 `StereoAudioPlayer`。

---

## 18. Mac 麦克风采集

Browser 使用：

```javascript
navigator.mediaDevices.getUserMedia()
```

不要依赖默认麦克风。

页面必须提供输入设备选择框。

用户应选择 Mac 内置麦克风。

不能假设设备名称固定，必须基于 `deviceId` 处理。

---

## 19. getUserMedia 建议参数

```typescript
{
  audio: {
    deviceId: {
      exact: selectedInputDeviceId
    },
    channelCount: 1,
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true
  }
}
```

不要假设请求：

```text
sampleRate: 16000
```

就能真正拿到 16kHz 音频。

必须自行 Resample。

---

## 20. AudioWorklet

禁止使用：

```text
ScriptProcessorNode
```

使用：

```text
AudioWorklet
```

链路：

```text
MediaStream
    ↓
MediaStreamAudioSourceNode
    ↓
AudioWorkletNode
    ↓
pcm-worklet
```

AudioWorklet 负责：

```text
读取 Float32 音频
↓
Downmix Mono
↓
Resample → 16000
↓
Float32 → Int16
↓
Buffer
↓
每 1280 sample 发送一次
```

---

## 21. PCM 转换

```typescript
sample = Math.max(-1, Math.min(1, sample))
```

然后：

```typescript
sample < 0
    ? sample * 32768
    : sample * 32767
```

保存：

```text
Int16 little-endian
```

最终输出：

```text
ArrayBuffer
```

长度通常：

```text
2560 bytes
```

---

## 22. AudioWorklet → Main Thread

第一版不要使用：

```text
SharedArrayBuffer
```

使用：

```text
AudioWorkletProcessor
      ↓
port.postMessage()
      ↓
Transferable ArrayBuffer
```

Main Thread 收到 PCM packet 后：

```typescript
websocket.send(arrayBuffer)
```

---

## 23. Local Agent 地址

Agent：

```text
127.0.0.1:18765
```

健康检测：

```text
GET /api/health
```

返回：

```json
{
  "status": "ok",
  "service": "translator-agent"
}
```

翻译：

```text
WS /ws/translate
```

---

## 24. Browser ↔ Agent WebSocket 设计

每一个 AST Session 使用一个独立 localhost WebSocket。

不要在一个 WS 中 multiplex 多个翻译 session。

---

## 25. TranslationClient 生命周期

创建：

```typescript
new TranslationClient(config)
```

内部连接：

```text
ws://127.0.0.1:18765/ws/translate
```

第一包发送 JSON：

```json
{
  "type": "start",
  "sessionId": "uuid",
  "mode": "s2s",
  "sourceLanguage": "zh",
  "targetLanguage": "en",
  "targetAudioFormat": "pcm",
  "targetAudioRate": 16000
}
```

Agent：

```text
建立 Volcengine WebSocket
↓
添加 API Header
↓
发送 StartSession
```

收到 `SessionStarted` 后给 Browser：

```json
{
  "type": "ready",
  "sessionId": "uuid"
}
```

---

## 26. 浏览器发送 Audio

收到 `ready` 后发送 binary WebSocket Frame：

```text
PCM16 / 16k / mono
```

每 frame：

```text
约 80ms
约 2560 bytes
```

Agent 收到 binary frame 后：

```text
构建 TaskRequest
↓
event = 200
↓
source_audio.data = binary PCM
↓
send Volcengine
```

---

## 27. 首句不能丢失

面对面 PTT 的关键问题：

用户 `pointerdown` 后会马上说话，但 AST Session 尚未 ready。

不能：

```text
等待 ready
↓
再开始录音
```

必须：

```text
pointerdown
       │
       ├── 立即录音
       │
       └── 同时建立 TranslationClient
```

录音产生的 PCM 暂存在：

```text
preReadyQueue
```

最大：

```text
3 秒
```

收到 `ready` 后：

```text
按顺序 flush preReadyQueue
```

随后进入实时发送。

---

## 28. pointerup 早于 ready

如果用户说了一句很短的话：

```text
按下
↓
说话
↓
松开
↓
AST 尚未 ready
```

则：

```text
finishPending = true
```

收到 `ready` 后：

```text
flush queued audio
↓
send finish
```

不能直接取消 Session。

---

## 29. 结束 Session

浏览器：

```json
{
  "type": "finish"
}
```

Agent：

```text
确保所有音频已发送
↓
FinishSession
```

之后继续等待：

```text
TTS
Subtitle
Usage
SessionFinished
```

不能 Browser 一发送 `finish` 就立即关闭 WebSocket。

必须等：

```text
SessionFinished
```

或者：

```text
SessionFailed
```

---

## 30. Agent → Browser 事件

### Session Ready

```json
{
  "type": "ready"
}
```

### 原文 Partial

```json
{
  "type": "source_partial",
  "text": "你好请问"
}
```

### 原文 Final

```json
{
  "type": "source_final",
  "text": "你好，请问你叫什么名字？"
}
```

### 译文 Partial

```json
{
  "type": "translation_partial",
  "text": "Hello, what's"
}
```

### 译文 Final

```json
{
  "type": "translation_final",
  "text": "Hello, what's your name?"
}
```

### TTS 音频

Binary WebSocket Frame：

```text
PCM16 mono / 16000
```

火山上游的 `TTSSentenceStart` / `TTSSentenceEnd` 属于供应商内部句子边界，必须由 Agent 消化，不得转发给 Browser。Browser 将 `ready` 到 `finished` 之间的所有二进制帧视为一个连续、保序的译文 PCM 流。合法会话可以没有任何 TTS 二进制帧，也可以包含任意多个分句的音频帧。

### Finish

```json
{
  "type": "finished"
}
```

`finished` 只表示 Agent 不会再产生新的字幕或 PCM，不要求此前出现过 TTS。Browser 收到后仍须等待所有本地 PCM 排程 Promise 和实际播放队列清空，才能解除半双工锁定。

### Error

```json
{
  "type": "error",
  "code": "VOLCENGINE_SESSION_FAILED",
  "message": "..."
}
```

---

## 31. 字幕映射

火山事件映射：

```text
SourceSubtitleResponse
→ source_partial

SourceSubtitleEnd
→ source_final

TranslationSubtitleResponse
→ translation_partial

TranslationSubtitleEnd
→ translation_final
```

---

## 32. StereoAudioPlayer

面对面模式核心模块。

接口：

```typescript
play(
  pcm: ArrayBuffer,
  target: "left" | "right" | "both"
): void
```

以及：

```typescript
clear(): void
isIdle(): boolean
```

---

## 33. TTS PCM 解码

输入：

```text
Int16 PCM
```

转换成：

```text
Float32
```

公式：

```typescript
floatSample =
  int16Sample < 0
    ? int16Sample / 32768
    : int16Sample / 32767
```

---

## 34. 左耳播放

创建：

```text
AudioBuffer
numberOfChannels = 2
sampleRate = 16000
```

填充：

```text
channel[0] = PCM
channel[1] = 0
```

即：

```text
LEFT  = Translation
RIGHT = Silence
```

---

## 35. 右耳播放

```text
channel[0] = 0
channel[1] = PCM
```

即：

```text
LEFT  = Silence
RIGHT = Translation
```

---

## 36. 双耳播放

单人同传：

```text
channel[0] = PCM
channel[1] = PCM
```

---

## 37. TTS Stream 播放队列

不要每收到一个 chunk 就立即 `source.start()`。

维护：

```typescript
nextPlaybackTime
```

算法：

```text
如果 queue 为空：

nextPlaybackTime =
max(
  audioContext.currentTime + 0.03,
  nextPlaybackTime
)
```

创建 BufferSource：

```text
start(nextPlaybackTime)
```

然后：

```text
nextPlaybackTime += buffer.duration
```

所有 TTS chunk 连续排队。

---

## 38. Output Device

Web 页面必须提供输出设备下拉框。

例如：

```text
AirPods
MacBook Speakers
...
```

支持时使用：

```javascript
audioContext.setSinkId(deviceId)
```

Mac 测试要求：

```text
Input：
MacBook Microphone

Output：
AirPods / TWS Headphones
```

两者必须能够分别选择。

---

## 39. 获取 Output Device

首先获得麦克风权限。

之后：

```typescript
navigator.mediaDevices.enumerateDevices()
```

过滤：

```text
audiooutput
```

用户选择耳机后：

```typescript
await audioContext.setSinkId(
  selectedOutputDeviceId
)
```

---

## 40. setSinkId Fallback

Feature Detection：

```typescript
if ("setSinkId" in AudioContext.prototype) {
   // use it
}
```

如果不存在：

```text
浏览器无法直接选择音频输出设备。

请在 macOS 系统设置中将蓝牙耳机设置为默认音频输出。
```

MVP 测试环境固定 Chrome。

---

## 41. Localhost

开发：

```text
http://127.0.0.1:5173
```

Agent：

```text
http://127.0.0.1:18765
```

第一版不要使用：

```text
http://192.168.x.x
```

测试麦克风。

---

## 42. AudioDeviceService

```typescript
interface AudioDeviceService {

  requestPermission(): Promise<void>

  listInputDevices(): Promise<AudioDevice[]>

  listOutputDevices(): Promise<AudioDevice[]>

  selectInput(deviceId: string): Promise<void>

  selectOutput(deviceId: string): Promise<void>
}
```

维护：

```text
selectedInputDeviceId
selectedOutputDeviceId
```

---

## 43. Device Change

监听：

```typescript
navigator.mediaDevices.addEventListener(
  "devicechange",
  ...
)
```

如果当前 Output Device 消失：

```text
停止当前 Session
停止声音
显示：
“耳机已断开”
```

不能自动播放到 MacBook Speaker。

---

## 44. 耳机测试功能

面对面页面必须有：

```text
测试左耳
测试右耳
```

这是 P0 功能，不是调试工具。

### 测试左耳

输出：

```text
LEFT  = test audio
RIGHT = silence
```

### 测试右耳

输出：

```text
LEFT  = silence
RIGHT = test audio
```

测试通过以后：

```text
✓ 左耳正常
✓ 右耳正常
```

用户再进入：

```text
READY
```

---

## 45. 面对面页面进入条件

必须全部满足：

```text
Local Agent Online

API Key Available

Microphone Permission Granted

Input Device Selected

Output Device Selected

Left Language Selected

Right Language Selected

Left Language != Right Language
```

才允许：

```text
开始面对面翻译
```

---

## 46. FaceToFaceController

```typescript
type FaceToFaceState =
  | "idle"
  | "preparing"
  | "ready"
  | "left_speaking"
  | "left_translating"
  | "right_speaking"
  | "right_translating"
  | "error";
```

字段：

```text
leftLanguage
rightLanguage
activeSide
currentTranslationClient
```

---

## 47. 左侧 PTT

使用：

```text
pointerdown
pointerup
pointercancel
```

不要只使用：

```text
mousedown
mouseup
```

---

## 48. pointerdown LEFT

执行顺序：

```text
assert state == READY

↓

state = LEFT_SPEAKING

↓

disable RIGHT button

↓

create TranslationClient

source = leftLanguage
target = rightLanguage
output = RIGHT

↓

start microphone capture

↓

connect translation session

↓

开始缓存 PCM
```

---

## 49. pointerup LEFT

```text
停止 microphone capture

↓

TranslationClient.finish()

↓

state = LEFT_TRANSLATING

↓

等待：
AST Finished
+
Audio Playback Queue Empty

↓

close TranslationClient

↓

state = READY
```

---

## 50. pointercancel

以下情况必须等价于 `pointerup`：

```text
鼠标离开按钮
页面失焦
pointercancel
tab hidden
```

监听：

```text
window.blur
document.visibilitychange
```

防止用户松开按钮但事件丢失，导致录音无限继续。

---

## 51. RIGHT PTT

完全镜像 LEFT。

配置：

```text
source = rightLanguage
target = leftLanguage
output = LEFT
```

---

## 52. 交换语言

点击：

```text
⇄
```

执行：

```typescript
[
  leftLanguage,
  rightLanguage
] = [
  rightLanguage,
  leftLanguage
]
```

耳机本身不交换。

---

## 53. 单人模式状态

```typescript
type SingleState =
  | "idle"
  | "connecting"
  | "translating"
  | "stopping"
  | "error";
```

点击开始后建立一个长期 TranslationClient：

```text
source = selected source
target = selected target
output = BOTH
```

收到 ready 后持续发送 microphone PCM。

点击停止后发送 FinishSession。

---

## 54. 字幕 UI

单人模式：

```text
┌────────────────────────────┐
│ 原文                       │
│                            │
│ How are you doing today?   │
├────────────────────────────┤
│ 中文                       │
│                            │
│ 你今天过得怎么样？        │
└────────────────────────────┘
```

Partial：

```text
实时覆盖当前句
```

Final：

```text
追加为完成句
```

---

## 55. 面对面字幕

建议显示聊天形式：

```text
A 中文

你好，请问你叫什么名字？

→

Hello, what's your name?


                         B English

                  My name is Jack.

                         →

                       我叫杰克。
```

MVP 不保存数据库。

只保存在 React memory state。

刷新页面后可以清空。

---

## 56. 语言范围

V1 先开放：

```text
中文 zh
英文 en
日语 ja
西班牙语 es
德语 de
法语 fr
葡萄牙语 pt
印尼语 id
```

第一轮 Mac MVP 建议先只测试：

```text
中文 ↔ English
```

确认整个技术链路后再开放更多语言。

---

## 57. API Key 管理

第一阶段 Mac 本地开发：

```bash
export VOLCENGINE_API_KEY="your-api-key"
```

启动：

```bash
go run ./cmd/translator-agent
```

Agent 获取：

```text
os.Getenv("VOLCENGINE_API_KEY")
```

禁止：

```text
API Key → Browser
VITE_VOLCENGINE_API_KEY
localStorage 保存 Key
React source code 保存 Key
```

---

## 58. Agent 安全规则

必须：

```text
bind 127.0.0.1
```

不能：

```text
0.0.0.0
```

WebSocket Server 必须检查 `Origin`。

开发环境只允许：

```text
http://127.0.0.1:5173
http://localhost:5173
```

正式 localhost 只允许：

```text
http://127.0.0.1:18765
http://localhost:18765
```

其他 Origin：

```text
403
```

---

## 59. Agent 日志

允许：

```text
session_id
source_language
target_language
event
latency
X-Tt-Logid
error code
```

禁止：

```text
API Key
完整 PCM 音频
用户语音文件
```

V1 不持久化音频。

---

## 60. Vite Dev Proxy

开发模式：

```text
Web:
127.0.0.1:5173

Agent:
127.0.0.1:18765
```

Vite：

```text
/api
→ 18765

/ws
→ 18765
```

并开启：

```text
ws: true
```

前端统一连接：

```text
/ws/translate
```

不要把端口写死散落在业务代码中。

---

## 61. Error 类型

```typescript
type TranslationErrorCode =
  | "AGENT_OFFLINE"
  | "MIC_PERMISSION_DENIED"
  | "MIC_DEVICE_LOST"
  | "OUTPUT_DEVICE_LOST"
  | "VOLCENGINE_CONNECT_FAILED"
  | "VOLCENGINE_SESSION_FAILED"
  | "AUDIO_CAPTURE_FAILED"
  | "AUDIO_PLAYBACK_FAILED"
  | "INVALID_LANGUAGE_PAIR";
```

---

## 62. 用户错误提示

不要把：

```text
WebSocket closed 1006
```

直接展示给普通用户。

例如：

```text
VOLCENGINE_CONNECT_FAILED
```

映射为：

```text
翻译服务连接失败，请检查网络或 API 配置。
```

Debug 面板可以显示：

```text
errorCode
logId
rawMessage
```

---

## 63. Debug Panel

开发版本增加 Debug 面板：

```text
Agent         ONLINE
Input         MacBook Microphone
Output        AirPods
Input Rate    48000
AST Rate      16000
Audio Packet  80ms
Session       xxxxx
AST Status    READY
Direction     zh → en
Output Ear    RIGHT
PCM Queue     0
Playback      120ms
LogId         xxxxx
```

---

## 64. 延迟指标

记录：

```text
PTT_DOWN
AST_READY
FIRST_SOURCE_SUBTITLE
FIRST_TRANSLATION_SUBTITLE
FIRST_TTS_AUDIO
PLAYBACK_START
```

使用：

```typescript
performance.now()
```

计算：

```text
session startup latency
subtitle latency
translation latency
tts latency
```

第一版先记录，不设严格 SLA。

---

## 65. 浏览器 Backpressure

监控：

```typescript
websocket.bufferedAmount
```

如果超过：

```text
1MB
```

记录：

```text
LOCAL_WS_BACKPRESSURE
```

并停止继续无限积压。

---

## 66. preReadyQueue

最多：

```text
3 秒
```

即约：

```text
38 packets
```

超过后终止 Session，并提示：

```text
翻译服务启动过慢，请重试。
```

不要无限缓存。

---

## 67. Agent Audio Queue

Agent 内部必须 bounded queue。

建议最多：

```text
50 × 80ms
=
4 秒
```

超过即 Session Error。

---

## 68. Web Audio Context

整个页面只创建一个：

```text
AudioContext
```

创建：

```typescript
const audioContext =
  new AudioContext({
    latencyHint: "interactive"
  })
```

不要每个 TTS chunk 创建 Context。

---

## 69. AudioContext Resume

Chrome 有 autoplay 约束。

用户首次点击：

```text
开始
```

或者：

```text
测试左耳
```

时执行：

```typescript
await audioContext.resume()
```

所有音频初始化都应由明确用户操作触发。

---

## 70. 面对面翻译声音原则

永远：

```text
speaker ear = silence
listener ear = translated audio
```

### Speaker LEFT

```text
output LEFT  = 0
output RIGHT = TTS
```

### Speaker RIGHT

```text
output LEFT  = TTS
output RIGHT = 0
```

---

## 71. 单人模式声音原则

```text
output LEFT  = TTS
output RIGHT = TTS
```

---

## 72. 不允许的实现

Agent 不得采用两个独立 Bluetooth Device。

产品模型是：

```text
一个 Stereo Bluetooth Device
↓
LEFT + RIGHT
```

不要尝试分别向两副独立耳机输出。

---

## 73. 第一版硬件要求

Mac 测试：

```text
MacBook
+
Chrome
+
一副 Stereo TWS 耳机
```

例如：

```text
AirPods
Galaxy Buds
其他标准 Stereo Bluetooth Earphones
```

两个人：

```text
一个戴 Left
一个戴 Right
```

---

## 74. Mac 测试前准备

macOS：

1. 连接 TWS。
2. 确认左右耳均连接。
3. 打开 Web。
4. 允许 Microphone。
5. Web Input 选择 MacBook 内置麦克风。
6. Web Output 选择 TWS / AirPods。

---

## 75. 测试 1：Agent

执行：

```bash
curl http://127.0.0.1:18765/api/health
```

必须：

```json
{
  "status": "ok",
  "service": "translator-agent"
}
```

---

## 76. 测试 2：麦克风

页面 Debug：

```text
Input Device
```

必须显示 MacBook 内置麦克风。

讲话时：

```text
Audio Level
```

必须变化。

AudioWorklet 应稳定输出：

```text
2560 byte
```

PCM Frame。

---

## 77. 测试 3：左耳

点击：

```text
测试左耳
```

期望：

```text
左耳：有声音
右耳：无声音
```

这是必须通过的硬性测试。

---

## 78. 测试 4：右耳

期望：

```text
左耳：无声音
右耳：有声音
```

如果耳机把左右合并成 mono：

```text
面对面模式判定失败
```

不能继续测试。

---

## 79. 测试 5：单人同传

配置：

```text
English → 中文
```

点击：

```text
开始
```

对 Mac 麦克风说英语。

必须出现：

```text
英文原文
中文译文
```

耳机：

```text
左耳中文
右耳中文
```

---

## 80. 测试 6：面对面 A → B

配置：

```text
LEFT 中文
RIGHT English
```

左边按住：

```text
你好，我叫李明。
```

期望字幕：

```text
你好，我叫李明。
Hello, my name is Li Ming.
```

耳机：

```text
LEFT：
无翻译语音

RIGHT：
Hello, my name is Li Ming.
```

---

## 81. 测试 7：面对面 B → A

右边按住：

```text
Nice to meet you.
```

期望：

```text
RIGHT：
无中文声音

LEFT：
很高兴认识你。
```

---

## 82. 测试 8：交换语言

点击：

```text
⇄
```

变成：

```text
LEFT English
RIGHT 中文
```

LEFT 用户讲话：

```text
English → 中文 → RIGHT
```

RIGHT 用户讲话：

```text
中文 → English → LEFT
```

必须全部自动反转。

---

## 83. 测试 9：快速短句

按住约：

```text
500ms
```

快速说：

```text
Hello
```

立即松开。

即使 AST 尚未 ready，也不能丢失 Hello。

这是 `preReadyQueue` 验收测试。

---

## 84. 测试 10：按钮安全

LEFT 按住期间：

```text
RIGHT disabled
```

LEFT 翻译声音尚未结束：

```text
RIGHT disabled
```

翻译声音结束：

```text
READY
```

两个按钮恢复。

---

## 85. 测试 11：耳机断开

翻译过程中断开 AirPods。

必须：

```text
停止当前翻译
清空 playback
状态 = error
```

显示：

```text
耳机已断开，请重新连接。
```

绝不能突然通过 MacBook Speaker 播放翻译。

---

## 86. 测试 12：Agent 关闭

关闭 Go Agent。

页面：

```text
Agent Offline
```

开始按钮 disabled。

页面不得崩溃。

---

## 87. 测试 13：API Key 错误

使用错误 API Key。

页面显示：

```text
翻译服务认证失败
```

Debug：

```text
实际错误码
X-Tt-Logid（如果可获得）
```

---

## 88. 单元测试

至少覆盖：

```text
PCM Float32 → Int16
Resample
PCM → LEFT Stereo
PCM → RIGHT Stereo
PCM → BOTH Stereo
Language Swap
FaceToFace State Machine
preReadyQueue
Translation Event Mapper
```

---

## 89. Agent 测试

至少覆盖：

```text
Start JSON parsing
AST StartSession config
PCM → TaskRequest
Finish → FinishSession
AST event → Browser event
Invalid API key
WebSocket disconnect
Origin validation
```

---

## 90. UI 验收

V1 不追求复杂视觉。

要求：

```text
电脑宽度自适应
按钮足够大
两个 PTT 按钮明显区分
当前说话人明显
当前语言明显
当前耳朵明显
状态明显
```

面对面模式 PTT 按钮应该占页面主要面积。

---

## 91. README 必须包含

Agent 最终必须写 README，包括：

```text
项目简介
环境要求
API Key 设置
Agent 启动
Web 启动
Mac 麦克风权限
蓝牙耳机连接方法
测试左耳
测试右耳
单人同传测试
面对面测试
常见错误
```

---

## 92. 推荐启动命令

根目录：

```bash
make dev
```

期望自动启动：

```text
Go Agent
+
Vite
```

如果不做 Make：

Terminal 1：

```bash
cd agent

export VOLCENGINE_API_KEY="xxx"

go run ./cmd/translator-agent
```

Terminal 2：

```bash
cd web

npm install

npm run dev
```

然后访问：

```text
http://127.0.0.1:5173
```

---

## 93. 开发顺序

Coding Agent 不要一次完成全部 UI。

### Phase 1

只做：

```text
Go Agent
↓
成功连接 Volcengine
↓
本地 WAV / PCM 文件发送
↓
收到 Subtitle + TTS PCM
```

确认 AST 跑通。

### Phase 2

浏览器：

```text
Mac Microphone
↓
AudioWorklet
↓
16k PCM16
↓
Agent
```

只验证字幕。

### Phase 3

实现：

```text
TTS PCM
↓
StereoAudioPlayer
↓
双耳播放
```

### Phase 4

实现：

```text
测试左耳
测试右耳
```

这是进入面对面模式开发之前的 Gate。

### Phase 5

实现：

```text
单人同传
```

### Phase 6

实现：

```text
FaceToFaceController
PTT LEFT
PTT RIGHT
```

### Phase 7

实现：

```text
Language Swap
设备断开
错误处理
Debug Panel
```

---

## 94. Definition of Done

### AST

- [ ] Go Agent 成功连接火山 AST
- [ ] API Key 不进入 Browser
- [ ] StartSession 正常
- [ ] TaskRequest 正常
- [ ] FinishSession 正常
- [ ] Source Subtitle 正常
- [ ] Translation Subtitle 正常
- [ ] TTS PCM 正常
- [ ] X-Tt-Logid 有日志

### Audio Input

- [ ] Web 可以选择 Mac 内置麦克风
- [ ] AudioWorklet 正常
- [ ] 输入转为 16kHz
- [ ] 输入为 PCM16
- [ ] 输入为 Mono
- [ ] 80ms packet 稳定
- [ ] 短句开始部分不会丢失

### Audio Output

- [ ] 可以选择 AirPods / TWS
- [ ] TTS 可连续流式播放
- [ ] 无明显 chunk 重叠
- [ ] 无明显 chunk 大间隙
- [ ] LEFT Test 只有左耳
- [ ] RIGHT Test 只有右耳
- [ ] BOTH 两耳均有声音

### Single

- [ ] 可以选择语言
- [ ] 可以交换语言
- [ ] 开始/停止
- [ ] 原文字幕
- [ ] 翻译字幕
- [ ] 双耳翻译语音

### Face to Face

- [ ] 左右语言设置
- [ ] 左右语言交换
- [ ] LEFT PTT
- [ ] RIGHT PTT
- [ ] 半双工
- [ ] LEFT 讲话 → RIGHT 听译文
- [ ] RIGHT 讲话 → LEFT 听译文
- [ ] 讲话人自己耳机静音
- [ ] 对方耳机正常播放
- [ ] 字幕方向正确
- [ ] 翻译播放完成后回到 READY

### Stability

- [ ] 运行 30 分钟不崩溃
- [ ] 多次 PTT 不出现残留 Session
- [ ] 多次 PTT 不出现 AudioContext 泄漏
- [ ] 多次 PTT 不出现 WebSocket 泄漏
- [ ] 耳机断开正确处理
- [ ] Agent 断开正确处理
- [ ] AST 出错正确处理

---

## 95. 第一版明确不做

Coding Agent 不要自行扩大范围。

第一版不实现：

```text
登录
云端服务器
历史数据库
录音保存
会议总结
术语库 UI
支付
用户管理
Safari 优化
Firefox 优化
Android
iOS
两个独立蓝牙耳机
多人模式
全双工同时讲话
系统音频采集
```

---

## 96. 最终核心架构

```text
                           Volcengine AST
                                 ▲
                                 │
                          WSS + Protobuf
                                 │
                         X-Api-Key only here
                                 │
                                 ▼
                         ┌──────────────┐
                         │  Go Agent    │
                         │  localhost   │
                         └──────▲───────┘
                                │
                           WebSocket
                                │
                         ┌──────┴───────┐
                         │   Browser    │
                         │ React / TS   │
                         └──────┬───────┘
                                │
               ┌────────────────┴────────────────┐
               │                                 │
               ▼                                 ▼
        Mac Built-in Mic                  Web Audio Output
               │                                 │
        AudioWorklet                            Stereo
               │                                 │
       PCM16 / 16k                       ┌────────┴────────┐
                                        │                 │
                                      LEFT              RIGHT
                                        │                 │
                                        ▼                 ▼
                                       A                   B
```

单人模式：

```text
Mic
 ↓
AST
 ↓
Translation
 ↓
LEFT + RIGHT
```

面对面 LEFT：

```text
LEFT user speaks
 ↓
Mac Mic
 ↓
leftLanguage → rightLanguage
 ↓
AST S2S
 ↓
Translation PCM
 ↓
[0, PCM]
 ↓
RIGHT ear
```

面对面 RIGHT：

```text
RIGHT user speaks
 ↓
Mac Mic
 ↓
rightLanguage → leftLanguage
 ↓
AST S2S
 ↓
Translation PCM
 ↓
[PCM, 0]
 ↓
LEFT ear
```

---

## 97. Coding Agent 最重要的实现原则

整个项目始终遵循：

```text
Browser 负责：

UI
麦克风
Resample
PCM
PTT
左右声道
声音播放


Local Agent 负责：

API Key
Volcengine WebSocket
Protobuf
协议转换


Volcengine 负责：

ASR
翻译
TTS
```

禁止把这些职责混在一起。

尤其禁止：

```text
React
↓
直接解析 Volcengine Protobuf
```

以及：

```text
Go Agent
↓
操作 Mac Audio Device
```

---

## 98. Agent 首个里程碑

Coding Agent 开始编码之后，第一个必须交付的可运行结果不是完整页面，而应该是：

```text
Mac
 ↓
Chrome
 ↓
MacBook Microphone
 ↓
Go Agent
 ↓
Volcengine
 ↓
翻译 PCM
 ↓
Chrome
 ↓
RIGHT CHANNEL ONLY
 ↓
TWS 右耳播放
```

同时：

```text
左耳完全静音
```

然后反过来：

```text
LEFT CHANNEL ONLY
```

也必须成功。

只要这个 Demo 跑通，面对面翻译最核心的技术风险即完成验证。

之后再继续做：

```text
UI
PTT
双向翻译
状态机
字幕
异常处理
```

而不是反过来。
