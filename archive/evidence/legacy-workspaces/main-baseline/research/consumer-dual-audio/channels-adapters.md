> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/consumer-dual-audio/channels-adapters.md`；原始 SHA-256 `fc3bfdd4aaab9d04f2ce3a2bbdd5ec750840d9a0c80ff5bd52a0334b98afa111`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 普通耳机双麦与接收器渠道核查

范围：仅官方公开资料；未接设备、改代码或生产。原目标按“两路独立麦克风输入，同时向两位使用者回放”判断；单副 TWS 与两副耳机分开讨论。HM60 无 SDK、已批购为需求前提，不代表其协议能力已确认。

## 1. USB 蓝牙适配器／双模 dongle

**结论：不能据此承诺拆出普通 TWS 左右麦。**[Apple 官方](https://support.apple.com/guide/airpods/change-settings-for-airpods-or-airpods-pro-dev57e5b7e58/web)的 AirPods 麦克风设置只有自动、固定左、固定右，是选择麦，而不是输出两条独立麦轨；这是实例，不是对所有未来 LE Audio 产品的断言。

须区分通用蓝牙控制器与呈现为 USB 声卡的音频 dongle。“双模”也不能视为双麦保证，必须看具体输入端点／声道规格。[Avantree DG80](https://out.avantree.com/knowledge-base/dg80-microphone-not-working-cant-talk/)允许普通耳机切换通话模式使用麦克风；音乐与通话同时工作的 FastStream 要求耳机也支持，不能单靠适配器增加。[双耳机 transmitter](https://avantree.com/blogs/knowledge/how-to-use-a-bluetooth-transmitter-for-headphones)描述的是向两副耳机播放；[multipoint](https://out.avantree.com/knowledge-base/general-connect-with-two-devices-at-the-same-time/)则是一副耳机连接两台音源，均不是两麦采集证据。

满足原目标：单副左右麦分离无已核实方案。HM60 迁移：最多改善已有标准音频连接；不能解锁 firmware、私有麦流或厂商未暴露的端点。

## 2. 两副普通 HFP 耳机

**Linux 电脑：有可搭建的技术路径，非已验证成品方案。**[BlueZ](https://bluez.readthedocs.io/en/latest/device-api/)按 hci0/hci1 管理设备，[bluetoothctl 官方说明](https://github.com/bluez/bluez/blob/master/doc/bluetoothctl.rst)支持选择控制器；可考虑每控制器配一副耳机。[WirePlumber](https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/bluetooth.html)创建各蓝牙设备与输入／输出节点，支持 native HFP；[PipeWire Combine Stream](https://docs.pipewire.org/page_module_combine_stream.html)可将多个源映射为独立声道，并向多个输出路由。由此推导：两副耳机分别 HFP 全双工，应用分别采集、回放；不是把一副拆成两副。

满足原目标：两副独立设备条件满足时可作为验证候选；多控制器不保证同时 SCO 稳定，仍有带宽、干扰、时钟漂移、延迟、回声及重连风险。HFP 音质低于 A2DP（[官方说明](https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/settings.html)）。HM60 迁移：仅当每副独立暴露标准 HFP 麦／扬声器；单副左右拆分仍不成立。未核实 Windows/macOS 的同等方案。

**Android：未找到普通应用即插即用的双 HFP 麦方案。**[setCommunicationDevice](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))选择一个通信用输出，平台自动选择匹配输入；[多设备路由](https://source.android.com/docs/core/audio/combined-audio-routing)主要描述输出与特权策略，不能据此证明双蓝牙麦并录。外接多个蓝牙控制器也无上述 API 的自动聚合保证。满足原目标：无已核实通用方案；HM60 不可据此直接迁移。

## 3. 专用 2.4GHz 双发射器：不是普通耳机接收器

- **[RØDE Wireless GO II](https://rode.com/en-de/user-guides/wireless-go-ii)**：两发一收，USB 可接电脑／Android；Split 将 TX1/TX2 分至 L/R，Merged 合并；安全轨仅在 Merged 下可用，是同一混音降低 20dB 的副本，不是第二人的独立麦。满足：独立双麦录音；不满足两位佩戴者各自收听回放。HM60：不能配作其通用接收器，只能更换采音链路。
- **[DJI Mic 2](https://www.dji.com/support/product/mic-2)**：两发一收；S 为两发分轨，M 合并，Ms 是混音的 -6dB 安全副本；[手册](https://dl.djicdn.com/downloads/DJI_Mic_2/20240426/UM/DJI_Mic_2_User_Manual_V1.2_EN.pdf)说明 USB 连接电脑及手机适配器。手机蓝牙直连官方只支持一个发射器，不能替代双发接收器。满足：专用双麦分轨，手机实际保轨须核验；不满足双人耳机回放。HM60：不能移植其专用无线链路。

以上“Stereo”是用双声道承载两路 mono；不必然生成两个文件。接收器耳机监听口也不等于向两只发射器返送音频。

## 4. Android USB 边界与决策

[AOSP USB audio](https://source.android.com/docs/core/audio/usb)承诺的基础子集为 Android 5+、USB host、UAC1、PCM、1／2 声道；USB-C 外形不保证 host、供电、线材、系统路由或应用保留 stereo。应用若降为 mono，两路会失去分离。更多声道／UAC2 不可据此保证。[DJI 兼容表](https://dl.djicdn.com/downloads/DJI_Mic_2/compatibility%20list/DJI%20Mic%202-%20Mobile%20Phone%20Compatibility%20List_EN.pdf)还列出 OPPO/OnePlus 外部录音设置及部分机型边充限制。

采购判断：保留 HM60 时，仅“两副标准 HFP＋Linux 分路”值得后续受控验证，不应先买 dongle 承诺成功；若改用专用双发麦，只解决输入，双人独立回放必须另行设计。未取得可对应批次的 HM60 官方协议／端点资料，不能宣称已验证兼容。
