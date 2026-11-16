> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/consumer-dual-audio/channels-phone-headset.md`；原始 SHA-256 `e8eac08c578a0aea9bcf41d12462f5f993987202cec4a710b1737cba4dc57ebd`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 普通耳机与手机双路输入的公开渠道

2026-11-11。网上主源研究，不改设备或App，不做实际录音。目标区分：TWS左右麦、手机＋耳机麦、两副耳机。当前HM60只证实经典BR/EDR、A2DP AAC stereo与Headset通话通路，未探测LEA或私有服务；不能从商家型号推断兼容性。

## 1. 公开标准正例：LE Audio stereo input

Android官方明确描述BLE Audio双向高质量音频(例如32kHz立体声)，提供TYPE_BLE_HEADSET、AudioRecord CHANNEL_IN_STEREO、setPreferredDevice途径。平台支持从Android13内建起，还需手机蓝牙硬件/策略、耳机实际支持并向系统暴露2通道输入。声道枚举只作候选能力，最终要查真实路由、启动成功与两路内容独立；文档示例不能当成通道数量数组的可靠完整检查，也不能把空间录音当双人身份分轨保证。

https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording
https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview

Bluetooth SIG的LE Audio拓扑说明包含左右独立输出以及两侧麦可选回传，指出超出经典A2DP/HFP原有拓扑。它证明标准有基础，不强制所有标注LE Audio的产品开放双麦。

https://www.bluetooth.com/blog/introducing-le-audio-the-use-cases/

当前Pixel3 Android12＋HM60不能默认直接采用，购买普通蓝牙6.0接收器也不能令耳机未开放的第二路麦出现。

## 2. 厂商系统正例：Samsung BT Mix

Samsung官方Pro Video说明：BT Mix可以同时采集蓝牙输入与手机声音；支持部分Note20、Fold/Flip、S21—S25等机型（各地区名单不同）。这证明实际有手机＋普通蓝牙双声源方案。但官方描述为combined/layered sound，不能当两条可供第三方App实时读取的独立轨。未找到对应公开第三方双原始输入SDK；不把未找到写成绝对不存在。

https://www.samsung.com/us/support/answer/ANS10003428/
https://www.samsung.com/ae/support/mobile-devices/microphone-options-for-pro-videos-on-your-galaxy-phone/

Samsung的360 Audio recording还明确phone与buds同时capture，限定Galaxy Buds和Galaxy手机/OneUI；仍需与可编程实时分轨区分，不可直接移植HM60/Pixel3。

https://www.samsung.com/us/support/answer/ANS10003232/

## 3. 通用Android API：有条件手机＋耳机双输入

AOSP concurrent文档：“If the technology is compatible ... such as a Bluetooth headset and built-in mic.” 多流可选不同设备，冲突由HAL仲裁；不是普通App普遍保证。AudioRecord.setPreferredDevice是偏好，不保证实际来源，应监听并验证getRoutedDevice、静音与真实声音。

https://source.android.com/docs/core/audio/concurrent
https://developer.android.com/reference/android/media/AudioRecord

该方向无需HM60私有SDK，但必须离线探针验证，且激活HFP可能损失A2DP立体声或改输出路线；“两个AudioRecord都started”不算通过。

## 本轮结论

有标准或厂商条件方案，不能笼统称普通消费耳机绝不可能。尚无证据证明现有HM60仅凭通用SDK就能开放左右耳独立音频。最有复用价值的实验仍是手机麦＋耳机麦实际路由探针；替代选型应要求可证明的双通道录音与独立输出，而非双麦降噪/双主机/LE Audio宣传词。
