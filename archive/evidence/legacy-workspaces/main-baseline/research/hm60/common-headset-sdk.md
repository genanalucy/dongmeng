> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/hm60/common-headset-sdk.md`；原始 SHA-256 `4481e56fc2090a1a88c203d9f2ac0911eabd0e7101845f4366f73cbd02ffebe8`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 普通蓝牙耳机是否有通用 SDK

**结论：没有能对任意耳机保证全部能力的通用 SDK，尤其不能凭空读取出厂 SN。** HM-60 包装名不能确定芯片、固件或协议；本次不操作 App、生产环境或设备。

- **厂家／型号／SN**：Android 的设备名只是友好名称，不保证是真实型号。`BluetoothGatt` 只能发现、读取设备实际开放的服务。SIG 的 Device Information Service（0x180A）内厂家、型号、Serial Number String（0x2A25）全部可选；有服务也可能没有 SN。包装有无 SN 不证明无线端可读，MAC、应用自建 ID 均不等于出厂 SN。[1][2][3]
- **电量**：若开放 Battery Service（0x180F），可读 Battery Level；否则取决于 HFP 或私有协议及系统开放能力。系统显示电量不保证普通 Android 12 App 能通用取得，更不保证左右耳／充电盒分别上报。[4]
- **绑定**：`BluetoothDevice.createBond()` 是系统配对，系统处理必要用户确认；已连接不等于账户绑定。业务绑定需要应用／后台记录身份，不能凭此证明唯一 SN 或所有权。[1]
- **收麦**：`BluetoothHeadset` 是 HSP/HFP 控制代理，不是录音器；`BluetoothA2dp` 管媒体播放 profile，不提供耳机麦克风上行。录音走 `AudioRecord`（需 RECORD_AUDIO），路由走 `AudioManager`：Android 12/API31 有 `setCommunicationDevice()`，经典耳机通常走 HFP/SCO，受单声道及通话音质限制。BLE 控制 SDK ≠ 音频采集 SDK。[5][6][7]
- **左右独立音轨**：左右不同内容的立体声播放可由系统音频输出承载；同时读取左右耳两支独立麦克风则无普通 HFP 通用保证。不能把双耳包装或双麦降噪当作双路原始录音。LE Audio 的立体声收麦也要求兼容硬件／固件；Android 内建支持始于 13，不能套用于 Pixel3 Android12。[8]
- **厂商 SDK**：杰理之家基于 RCSP；Airoha 官方提供特定芯片 SDK／参考 App，官方公告亦明确 RACE 协议与对应 SDK／芯片系列。均须确认芯片、固件实际实现及协议版本，不是跨品牌万能 SDK；同芯片也不保证全部功能。[9][10]

核查限制：context7 已尝试，但返回 Monthly quota exceeded；因此以上由 exa 核对官方资料，不能声称已完成 context7 交叉验证。具体 HM-60 能力仍待主线程本地证据。

## 官方来源
[1] https://developer.android.com/reference/android/bluetooth/BluetoothDevice
[2] https://developer.android.com/reference/android/bluetooth/BluetoothGatt
[3] https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/DIS_v1.2/out/en/index-en.html
[4] https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BAS_v1.1/out/en/index-en.html
[5] https://developer.android.com/reference/android/bluetooth/BluetoothHeadset
https://developer.android.com/reference/android/bluetooth/BluetoothA2dp
[6] https://developer.android.com/reference/android/media/AudioRecord
[7] https://developer.android.com/reference/android/media/AudioManager
[8] https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview
[9] https://doc.zh-jieli.com/Apps/Android/jielihome/zh-cn/master/framework/framework.html
[10] https://www.airoha.com/products/p/VIqdFSQFjSivD2iX
https://www.airoha.com/product-security-bulletin/2025
