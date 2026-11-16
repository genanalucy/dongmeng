> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/research/iflytek-hci-2026-11-09/findings.md`；原始 SHA-256 `3860ac4e03e7559781ed6cabfeb17dd837cb16a0568fe7c5be453971a5cb2c28`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# HCI 首轮连接/启动样本（非有效说话实验）

2026-11-09。用户授权完整HCI、本地bugreport及必要蓝牙重连；未root、未改固件/解绑、未提取密钥、未上传原始材料。错误报告约19.7MB，蓝牙日志约14.8MB，仅打开蓝牙日志条目，未检索无关应用内容。原始文件仅本机受限目录。

## 实验有效性与状态恢复

两轮自动计时与用户准备不同步。用户明确反馈未及讲话，不能视为完成左/右语音测试。完整抓取因等待超出预定短窗；需如实说明，不将整个日志当授权测试语料。后续分析仅连接/协议元数据，不解码音频载荷。下一轮等用户明确准备好再启用，由用户报告各侧完成。

HCI UI已恢复“已停用”；2026-11-09约15:26-15:27 UTC再切蓝牙开关以使停用生效，最终bluetooth_on=1，iFLYTEK耳机恢复连接，SCO_STATE_INACTIVE。未动其他开发者选项。

手机UTC比主机UTC约快266秒，未校时；HCI记录时间约14:46:36至15:24:18（设备时钟），不可与主机events直接对齐。文件中早于正式说话准备的连接/待机流量不能归因语音。manual-events.json窗口主机15:16:06起，约15:17:51完成关闭开关，用户无有效说话。

## 第二轮：有效的左右顺序说话测试

用户确认已戴好后重启抓取与连接，开始面对面。以手机UTC标记（相对主机约+266s）：15:38:45开始等待左侧讲话，15:40:21记录用户“左侧完成、右边出现声音”，15:42:10记录“右侧完成、左边出现声音”。这是用户听音结果，不是由包量自行推测；没有同时讲话或单耳断连对照。

会话停止请求约主机15:37:48，HCI UI停用约15:37:58。第二份bugreport仅提取btsnoop条目：约2.20MB，设备时间15:36:47—15:44:15。未查看无关应用内容或解码语音。约主机15:42—15:43再次切蓝牙生效并恢复连接，bluetooth_on=1，iFLYTEK耳机重连，SCO inactive。

第二轮协议元数据：
- HCI命令169、事件499、ACL6162，无SCO/ISO记录；仅观察1个手机侧ACL handle。
- 成功建立PSM0x001F（ATT over BR/EDR）及PSM0x0003 RFCOMM等通道。左右讲话期间主要数据在同一个ATT通知attribute，另一个低频attribute用于少量通知。
- 左窗口：PSM1F收2520 PDU、发57；右窗口：收2905、发86。主要通知有效值343字节；不是采样率/codec证明，也不能根据近似包量判两路并行。
- 主要attribute在左窗口2519条、右窗口2904条通知，两侧没有拆成两个不同ATT通知特征。可能在同一载荷复用L/R/类型，或按活动讲话侧切换，现未解析、不认定。
- 少量RFCOMM同时存在，但两侧持续数据主要不是经该RFCOMM通道。不能称其为SPP音频；HFP控制也可使用RFCOMM。

本轮可以确认的产品行为是“分别讲话、无逐句点按、对侧播报”；可以初步确认主要承载链路为经典蓝牙上的ATT。仍不能确认左右独立PCM/同时两轨、codec、是否主耳转发。手机侧一条ACL不能排除耳间汇聚，也不能据此证明汇聚。下一步需要带阶段标记的同时讲话和单耳对照，以及对私有帧头的最小必要分析；不绕过加密或提取密钥。

## 单耳对照：左耳入盒后的状态

用户报告左耳入盒后，未开启新一轮HCI（入盒事件本身未被抓取）。手机仍连接[耳机设备名称已脱敏]，首页仅显示一组77%电量。进入面对面准备页明确显示“左耳已断连（请确保两只耳机均已连接）”，未点击开始、未重新录音。可确认App分别识别左右耳在线状态；不能确认主耳切换或链路转移，也不能据准备页提示认定固件技术上不支持单耳。需恢复左耳后再对照右耳入盒。

## 用户追加观察：一个人双耳佩戴

用户已取出左耳，并自行测试：分别靠近左右耳说中文均能识别，显示中文原文并生成英文译文。该观察未同步抓取HCI，手机距离与另一侧声学隔离未受控。证实的是两个近讲位置都能触发正确方向翻译；不能单独证实两侧分别独立上传、同时双轨，也不能确定自动语言识别（同一讲话可能被另一侧/手机收到）。这对“L英语/R中文必然是硬性收音限制”的假设提出疑问，但因可能跨侧拾音，尚不足以推翻固定方向模型；目前只能确认界面语言角色配置，具体执行机制待证。用户尚未报告本次译音实际输出耳侧或是否生成重复记录；不补造。

## 首轮已观察的协议元数据

- btsnoop v1、HCI UART type1002；命令286、事件990、ACL39856，未见type3 SCO或type5 ISO记录。
- 一个ACL connection handle，有BR/EDR connection complete事件。仅是该捕获覆盖的手机侧链路数量，不能排除耳机间转发或未捕获链路。
- 重组ACL分片并通过L2CAP Connection Request/Response双向匹配PSM/CID：PSM0x001F通道有手机发起write request/command(0x12/0x52)，外设方向notification(0x1B)，响应0x13，结构吻合ATT-over-BR/EDR。
- 主要通道：PSM0x001F接收39135 PDUs，发送496；其中notify39134，绝大部分payload346字节，集中于同一个attribute handle（只记录匿名计数，不暴露UUID/地址/载荷）。不凭高流量直接断言音频或左右复用格式。
- PSM0x0003 RFCOMM同时存在：约103个PDU，主要短消息；不证明此RFCOMM会话是SPP，更不能仅凭RFCOMM存在认定承载主要音频（HFP控制也可用RFCOMM）。
- 还观察PSM0x0001/0x0017/0x0019相关控制/媒体通道。无需据这些存在推断当时真实播放内容。

## 结论边界

已经比系统GATT注册信息更强：存在ATT通知/写入在BR/EDR ACL/L2CAP上传输，以及少量RFCOMM并行。ATT/GATT不等同BLE；本捕获不支持把主要通信直接称为BLE音频或SPP音频。

尚不能证明通知payload一定是音频、L/R同时独立轨、codec、加密/压缩格式、主耳转发/动态切换。需要有效左右近讲、手机远离、单耳断开等标记实验；手机侧HCI本身看不到耳间空口内部拓扑。

分析脚本analyze_transport.py只输出元数据，未解码payload。结果transport-metadata.json不含MAC/SN/原始音频。实现为本地研究脚本，未写App代码或提交Git；需独立解析器复核后再将协议判定作为最终报告。

官方参考：
https://source.android.com/docs/core/connect/bluetooth/verifying_debugging
https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/attribute-protocol--att-.html
https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/logical-link-control-and-adaptation-protocol-specification.html
