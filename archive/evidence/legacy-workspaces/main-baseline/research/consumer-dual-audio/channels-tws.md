> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/consumer-dual-audio/channels-tws.md`；原始 SHA-256 `78138559b784edda2eddfac1d0c092f4b719fcf93c08b39fb35f83ea44c1bcb7`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 消费 TWS：双耳独立采集与定向播放

调研：2026-11-11。仅公开资料，无实现、设备连接或生产操作。

## 结论

**本轮未找到满足全部条件的可验证量产正例：普通消费 TWS＋公开 SDK/协议/系统 API＋第三方 Android 实时取得左右耳独立音轨＋采集同时左右定向播放。** 这不是“不可能”的证明。已有明确的消费产品双耳空间录音正例，也有第三方 Android 立体声录音官方指南；缺的是把二者与具体商品、每耳通道映射、并行播放串成完整证据。

## 已核实的正例及边界

### 1. Samsung Galaxy Buds2 Pro／Buds3／Buds3 Pro

官方说明 Buds2 Pro 可经“built-in microphones in each bud”录制 360 音频，要求支持 LE Audio、One UI 5.0 以上的 Galaxy 手机。[1] 美国支持页列上述三款耳机，以及 S23/S24/S25 系列、Z Fold4–6、Z Flip4–6；操作入口是 **Samsung Camera → Advanced video options → 360 audio recording**。[2]

这是“双耳麦参与空间录音”的量产正例，不是六个麦各自原始 PCM 的承诺，也没有在所读文档中规定左声道恒等于左耳、无混音处理。美国页还写手机与耳机同时采音，不能把视频声轨直接认作两只耳麦原轨。**确认的流程限厂商相机；未取得第三方 SDK/公开分轨接口证据，不等于证明第三方绝对不能录。** 未证明该模式下同步分耳播放。

迁移 HM60：不能。三星指定手机、耳机、固件及功能链不能套用到未知耳机。

[1] https://www.samsung.com/nz/members/whats-new/record-immersive-videos-with-galaxy-buds2-pro-360-audio-recording-update/

[2] https://www.samsung.com/us/support/answer/ANS10003232/

### 2. 第三方 Android 的公开系统路径：LE Audio

官方录音指南明确“record stereo audio from BLE Audio hearables”，使用普通 `AudioRecord.Builder`、`MIC`、`CHANNEL_IN_STEREO`、`setPreferredDevice()`；选择具有输入能力的 BLE 耳机，按其声道能力决定 mono/stereo。[3] 因而**立体声耳机录音并非原则上只开放给厂商相机**。

但指南不指定已验证零售型号，也不保证两个声道分别来自两耳或输出未经过 DSP；不能把它当作 Buds/Pixel/Sony 的兼容认证。示例 targetSdk 31 不是 Android 12 手机必然可用的保证；平台概览说明 Android 13/API33 内建 LEA，并列“Stereo microphone”与麦克风工作时的高质量播放能力。[4]

迁移 HM60：只有实物支持 LEA 输入、系统暴露双声道且确认每耳映射才有讨论基础；商家不提供资料，当前没有这些证据。Pixel 3/Android 12 不能由该指南推定支持；也不能只靠蓝牙版本号判断。

[3] https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording

[4] https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview

### 3. Xiaomi Buds 5：耳机本地录音，不是公开实时双流

官方：“Recordings are independently stored on the earbuds by default”，经 Xiaomi Earbuds App 导出；总计三小时指两耳合计，建议固件 3.0.9.2 以上。[5] 这是本地录音产品正例；措辞不足以证明双耳同步分轨，更未提供第三方实时 PCM SDK/协议或并行分耳输出保证。不能迁移 HM60。

[5] https://www.mi.com/global/product/xiaomi-buds-5/

## Google／Sony 与输出边界

Google 官方规格列 Pixel Buds Pro 2 每耳三个麦克风及 LE Audio；这证明硬件规格，不证明第三方可取双耳分轨。未找到 Google 对具体 App/API 的每耳映射承诺。[6] 多麦波束成形、降噪或高质量通话都不能替代该证据。

[6] https://support.google.com/googlepixelbuds/answer/7544332?hl=en

Sony 官方支持站及开发者站直读出现 TLS 错误，本轮没有足够材料核实 WF-1000XM5/LinkBuds 的独立录音或消费耳机 SDK；**保留未知，不列为正例或确定不支持**。

Android `AudioTrack` 公开支持立体声 PCM，左右内容可分别写入；但这只是逻辑声道，不是物理每耳路由认证。[7] 必须另证麦克风激活时仍保持立体声、无下混及无改道。LEA 的双向高质量能力也不是任意产品双声道全双工保证。

[7] https://developer.android.com/reference/android/media/AudioTrack

## 证据缺口与检索限制

采购可验收证据应包含：零售型号/固件、手机/OS、第三方调用示例、每耳到 PCM 的映射、连续采集同时左右不同内容播放的保证。现有资料未满足整套条件；不应据此继续批购替代型号，也不猜 HM60 芯片、SDK 或私有指令。

已用 Exa 搜得 Samsung/Google 官方来源，后续 Exa 搜索及抓取触发免费限额；改为直读已知官方 URL。Context7 实际调用返回“Monthly quota exceeded”。因此不是穷尽全市场；未核实的 SDK 与 Sony 路径仍是缺口。
