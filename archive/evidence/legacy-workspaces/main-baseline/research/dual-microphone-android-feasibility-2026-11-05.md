> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/dual-microphone-android-feasibility-2026-11-05.md`；原始 SHA-256 `4615aba236d8a31c9e6d34d266976d6f530c7e9750cbbb34d791296844f7e3ce`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Android 内置麦克风＋蓝牙麦克风双路采集可行性

日期：2026-11-05。仅文献研究；未连接设备、未改应用/环境、未运行实验、不提交。以 Exa 检索并读取 Android Developers、AOSP 原文；Context7 交叉核对 AudioRecord/AudioManager。Apple 与项目调查由主线程负责。

## 结论

**设备依赖：值得做限定机型实验，不能承诺普通 Android＋任意蓝牙耳机通用实现。** 不能因“两普通 App 不可同时录音”，断言“同 App 两个 AudioRecord 绝对不行”；也不能因对象都启动成功，就认定拿到了两个独立麦克风。双路采集成功与“双人声音可靠分离、译音只到目标耳”是三项独立验收条件。

## 已证实：边界与依据

1. **多 App ≠ 同 App。** 官方 sharing-input 针对“between multiple apps”，并规定“Two ordinary apps can never capture audio at the same time”[1]。Android 10 起通常让输家收到静音；此前可能启动失败。同 UID 的 AOSP Android 12 `updateUidStates_l()` 以 `current->attributionSource.uid == topActive->attributionSource.uid` 判断普通活跃客户端[2]：没有“第二个 AudioRecord 必然静音”的通用结论；但仍受权限、前后台、通话、隐私与硬件约束。

2. **框架明确容许不同输入设备，但仅条件性推荐。** AOSP：“each stream can have a different device selection”；并推荐兼容时允许“a Bluetooth headset and built-in mic”[3]。同 HAL 输入流上的客户端按优先级选设备；若共用数字接口/后端不兼容，HAL 决定谁控制路由。因此两个对象可能共源、被重路由或一路无有效声音。并发数量由 `audio_policy_configuration.xml`、input profiles、HAL/驱动/OEM 实现共同决定，不是 Android 版本号保证。

3. **偏好不是锁定。** AudioRecord API23 起可 `setPreferredDevice()`；`getPreferredDevice()` 原文：“not guarenteed to correspond to the actual device”[4，原文拼写]。返回 true 不证明独立双路成立。必须启动后查每路 `getRoutedDevice()`；原文“only valid if ... currently recording”。同时监听路由、recording callback 与 `isClientSilenced()`，并检查 PCM/实物声源；重采样会使 App 的16k格式不等于链路原生16k[1]。

4. **通信选择是系统仲裁，不是两个录音器各自的开关。** `setCommunicationDevice()`（API31）仅选输出端：“The matching source device is selected automatically”；多 App 请求由控制 audio mode 的 App 优先[5]。旧 SCO 建链是异步、电话优先；API34 起弃用 `startBluetoothSco()`[6]。这解释 HFP/SCO 常牵动整条通信输入/输出路径，但不证明所有 OEM 都必然关闭内置麦克风。

5. **HFP/SCO 下不能承诺左右目标耳。** 官方旧 SCO API 对可路由输出明确要求“the format must be mono”，并列输出8/16k、输入单声道8k限制[6]。这些是旧 API 契约，不应推广成所有现代蓝牙采样率上限；但**走单声道 SCO 无法保留独立 L/R 内容**。左右 PCM 可能被下混、改道或不播放，不能视作物理左/右耳保证。LE Audio 官方也指出“Bluetooth classic lowers audio quality when using Bluetooth microphones”[7]。耳机麦＋A2DP立体声能否同时保持，需整链实测，不能凭 StereoRouter 判定。

6. **LE Audio 不自动解决双设备输入。** Android13 内建 LEA；官方列“Stereo microphone”及麦克风工作时高质量播放[7]，但没有保证与手机麦并行。通信仍用 `setCommunicationDevice()`；官方迁移说明“backward-compatible with ... HFP”[8]。LEA 耳机内立体声、广播/多耳机输出、内置＋耳机两个独立输入不是一回事。[9] 的 combined routing 主要讨论 output streams/特权策略 API，不能拿来证明普通 App 双输入必成或必禁。

## 设备边界（仍待实验）

| 组合 | 判断 |
|---|---|
| 普通 Android＋经典蓝牙耳机 | 可尝试同 UID 两路偏好；无跨 OEM 保证；另验 SCO 启用后的目标耳播放。 |
| Pixel 3 | 按实际 ROM/build 验证。Android12 设备树列 `primary input`、`fast input`、SCO mic 等候选路线[10]，不代表两条能同时绑定不同设备；不能把 Android13 LEA 能力倒推给它。 |
| AirPods＋Android | 按实测枚举的 SCO/LEA 输入与具体代际、固件判断；品牌不绕过上述约束。此处未核实各代 LEA Android 兼容性，不作支持承诺。 |
| 内置＋USB麦 | 避开 SCO 传输条件，但仍需双输入策略/HAL验证，不能保证。 |
| 单个USB接口接两只麦 | 若暴露两个独立声道，可用一个立体声输入再分路；不是手机麦＋蓝牙麦。AOSP USB：“each direction may have ... two channels ... or more”[11]；应优先核实双声道，更多声道另验。两只USB设备也不等于一个多声道设备。 |

项目事实由主线程提供：目前单 AudioRecord、VOICE_RECOGNITION/16k mono、无设备偏好；TtsPlayer/StereoRouter 做左右 PCM 分流。**现状既未实现双采集，也未证明 HFP 下物理目标耳隔离。** 多麦阵列、`getActiveMicrophones()` 的通道映射同样不是自动合并两物理设备[4]。

## 最小实验矩阵（仅计划，无实现代码）

| 设备 | 输入组合 | 对照 |
|---|---|---|
| Pixel3，记录原厂/自定义ROM与build | 内置＋实际AirPods HFP；内置＋USB | 各单路基线；同UID双路；反转启动顺序 |
| 一台当前Pixel＋一台其他OEM | 内置＋同一HFP耳机 | 同上；跨厂比较 |
| 支持LEA的手机＋确认LEA耳机 | 内置＋LEA | 与经典HFP区别记录；不假定AirPods属于LEA |
| 上述手机＋双输入USB接口 | 一个设备两声道 | 两麦单独发声，确认通道非复制 |

每组先测 MODE_NORMAL/VOICE_RECOGNITION，再测通信模式与SCO/LEA激活；先无TTS，再连续采集＋TTS。记录枚举ID、偏好结果、实际输入/输出路由、静音状态、实际格式、丢帧与时间戳；双人轮流近讲、同时讲话，比较每路能量/串音。重复断连重连、重启与来电恢复。另以“只左/只右”不同提示音在激活蓝牙麦前后实听两耳，检查下混、静音、扬声器误播。

通过门槛：两路持续非静音且物理来源不同、串音与延迟可接受、译音只到目标耳。未通过就不得宣传免切换双麦；可另评估单麦自动分段/说话人识别、单USB双声道或双终端，不能当作本方案已实现。

## 主源（均已读取原文，正文含短引）

[1] https://developer.android.com/media/platform/sharing-audio-input

[2] https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android12-release/services/audiopolicy/service/AudioPolicyService.cpp

[3] https://source.android.com/docs/core/audio/concurrent

[4] https://developer.android.com/reference/android/media/AudioRecord

[5] https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)

[6] https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco()

[7] https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview

[8] https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager

[9] https://source.android.com/docs/core/audio/combined-audio-routing

[10] https://android.googlesource.com/device/google/crosshatch/+/refs/heads/android12-release/audio_policy_configuration.xml （XML正文另以 `?format=TEXT` 核对）

[11] https://source.android.com/docs/core/audio/usb
