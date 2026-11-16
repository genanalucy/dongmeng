> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/phone-headset-dual-input-summary-2026-11-05.md`；原始 SHA-256 `3ecc65abcdb375f294e46f7e0879f359ddf3bbc5138267f4cd9e79bf5f11a07f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 手机＋耳机麦克风双路输入：产品可行性结论

研究日期：2026-11-05；目标：Android 手机＋其他蓝牙耳机。仅文献与项目静态研究，未改应用/生产、未录音或调用翻译；资料按当次官方网页核实，未完成机型实测。

## 结论

有实现基础，但不是任意 Android＋蓝牙耳机的通用保证。建议先做可离线运行、带真实路由诊断的 Debug 能力探针，证明两路物理来源不同且不影响译音输出后，再设计自动对话。双麦采集、说话人区分、自动判停/翻译方向和正确输出是独立验收项。

## Android 官方依据

AOSP concurrent capture 文档明确：多条流可以有不同设备选择；技术兼容时推荐允许 Bluetooth headset 与 built-in mic 同时采集，硬件不兼容时由 HAL 仲裁。原文："each stream can have a different device selection"、"such as a Bluetooth headset and built-in mic"。这是条件支持，不是所有厂商的保证。

https://source.android.com/docs/core/audio/concurrent

同 App 的两个 AudioRecord 不能等同于两个普通 App；后者的录音竞争规则不能直接推出前者必定不支持。即使两个AudioRecord都启动、读到PCM，也可能共用一个物理输入或一路静音。需要启动后检查实际 getRoutedDevice、路由变化、静音与测试声源，不以 setPreferredDevice 返回成功作结论。

https://developer.android.com/media/platform/sharing-audio-input
https://developer.android.com/reference/android/media/AudioRecord

普通经典蓝牙耳机启用麦克风通常涉及 HFP/SCO 通信路由；旧 startBluetoothSco 文档对输出明确要求 mono。这意味着当前 App 的 LEFT/RIGHT PCM 分流不保证在蓝牙麦启用后仍保留物理左右耳隔离。不能把旧接口采样率限制扩展到所有现代耳机，但必须检查单声道下混/输出改道风险。

https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco()

LE Audio（Android13起框架内建）可改善麦克风工作时的播放质量、支持某些耳机立体声麦克风；这不等于手机内置麦与耳机麦双输入保证，手机/耳机必须实际支持对应传输模式。

https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview

更多Android引用和实验矩阵见同目录 dual-microphone-android-feasibility-2026-11-05.md。

## Apple 的比较必须更新到新API

Apple官方支持文档说明，嘈杂环境可将iPhone移近对方，使用iPhone麦克风 "in addition to those on AirPods"；启动后自动翻译，但向没有兼容AirPods的对方播放自己的译文仍可需要点Play。因此用户观察有官方依据，不可简化为始终固定耳机只收自己/手机只收对方，也不能由UX描述推断其内部ADC实现。

https://support.apple.com/en-us/123185

重要：第三方iOS并非永远只能单输入。Apple当前公开 AVAudioSession.Mode.dualRoute（官方元数据标记 iOS/iPadOS26.2起），明确支持内置麦/扬声器与第二个双向音频设备 simultaneous use，需multiRoute和allowBluetoothHFP。官方multidevice指南直接给出内置前置麦＋AirPods麦映射到两个录音声道的例子。主线程读取developer原始JSON复核。它证明公开API具备该方向，但不证明Apple翻译内部就是此实现，也不是Android的能力保证。

https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/dualroute
https://developer.apple.com/tutorials/data/documentation/avfaudio/avaudiosession/mode-swift.struct/dualroute.json
https://developer.apple.com/documentation/avfaudio/routing-audio-to-specific-devices-in-multidevice-sessions
https://developer.apple.com/tutorials/data/documentation/avfaudio/routing-audio-to-specific-devices-in-multidevice-sessions.json

## 项目现状（已读源码）

- android/.../audio/MicrophoneCapture.kt：只有一个AudioRecord，VOICE_RECOGNITION、16kHz mono PCM16；没有分别绑定手机/蓝牙输入。
- android/.../ui/FaceToFaceRuntime.kt：只有一个MicrophoneCapture实例；协调器按单采集生命周期处理。
- android/.../audio/TtsPlayer.kt、StereoRouter.kt：将mono译音扩为stereo左/右/双耳，没校验蓝牙实际输出路线。
- agent/internal/ast/official_client.go：每会话固定源/目标语言、单路音频。两路同时翻译需要保留来源身份、方向/会话绑定；不能把两路直接混音发送到现有单路。
- agent/internal/server/registry.go允许同认证会话下合法重叠连接，但不代表已经支持双麦常驻会话。须另核对配额/会话治理与供应商并发成本。

## 先证明能力，再设计产品

阶段A离线探针：输入设备枚举；分别单录基线；两AudioRecord偏好不同设备并反转启动顺序；检查真实路由、静音/能量与声源；断连/重连；最后加本地测试音，核对蓝牙麦工作时左右耳和手机扬声器。全程使用非私密测试短句，默认仅内存分析、不上传原始录音，用户明确启动和随时停止。

通过条件：两路来源可证不同且持续有效、串音可控、输出去向正确。不得以蓝牙版本号或两个start成功作能力判断。Pixel3等旧设备不预设支持。尚缺目标手机型号/Android及耳机型号。

阶段B免切换对话：用户一次开始，两路VAD判停、固定输入身份对应A→B/B→A、回声/串音抑制与同一句重复识别去重、翻译输出排队/目标路由、设备丢失安全退出。双人同时说话不是保证同时可听懂，也不能承诺已有双路就无需这些逻辑。

降级备选：单手机麦自动判停＋可靠语言识别（不依赖双硬件但多人重叠/同语言方向受限，现主线未稳定实现）；双终端会话；一个支持独立双声道的USB音频接口。后两者是不同产品/硬件方案，不是假装任意蓝牙都支持。

建议：先确认设备组合后做诊断实验，不直接承诺自动双麦上线，不影响现有按住/连续翻译主线。此研究资料保存在忽略的.scratch/research内，未Git提交。
