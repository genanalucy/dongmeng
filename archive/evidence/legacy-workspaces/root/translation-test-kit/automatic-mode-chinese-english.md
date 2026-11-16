> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `translation-test-kit/automatic-mode-chinese-english.md`；原始 SHA-256 `68109224ea42b922cf5f7f24ae070d55d1933be83d882b9f02d264c67f908952`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 自动模式测试文案：中英对话

> **模式说明**：不手动选择输入语言。App 应识别每条“原文”的实际语言，并自动翻译为另一种语言。
> **表格规则**：`原文语言` 表示该句真实输入语言；`参考译文` 必须是对应的目标语言，不能把原文或译文放反。
> 每个场景 8 轮（双方各发言 8 次）。

---

## 场景 1：共享办公空间访客登记

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 访客 | 中文 | 你好，我约了下午两点和 Alex Chen 开会，不过我早到了二十分钟。 | English | Hi, I have a 2 p.m. meeting with Alex Chen, but I arrived twenty minutes early. |
| 前台 | English | Welcome. Could I see a photo ID and the company name you are visiting? | 中文 | 欢迎。请给我看一下带照片的身份证件，并告诉我您要拜访的公司名称。 |
| 访客 | 中文 | 可以，这是我的护照。我去的是 BrightPath，不是楼上的 BluePath。 | English | Sure, here is my passport. I’m visiting BrightPath, not BluePath upstairs. |
| 前台 | English | Thanks for clarifying. BrightPath is on the ninth floor, and Alex has been notified. | 中文 | 感谢您说明。BrightPath 在九楼，Alex 已经收到通知。 |
| 访客 | 中文 | 嗯，我需要先领访客卡吗？还有洗手间在哪边？ | English | Um, do I need a visitor badge first? Also, which way is the restroom? |
| 前台 | English | Yes, please wear this badge visibly. The restroom is behind the elevators, on the left. | 中文 | 是的，请把这张访客卡佩戴在明显位置。洗手间在电梯后面左侧。 |
| 访客 | 中文 | 明白了。会议室有空调吗？我带了外套，但不确定要不要拿上去。 | English | Got it. Is the meeting room air-conditioned? I brought a jacket, but I’m not sure whether to take it upstairs. |
| 前台 | English | It can be quite cool in the afternoon, so I would take it with you. | 中文 | 下午可能会比较冷，所以我建议您带上去。 |
| 访客 | 中文 | 好的。对了，Wi-Fi 是访客网络吗，还是 Alex 会给我密码？ | English | Okay. By the way, is there a guest Wi-Fi network, or will Alex give me the password? |
| 前台 | English | Please use the network called Guest-9F. The password is printed on the back of your badge. | 中文 | 请使用名为 Guest-9F 的网络。密码印在访客卡背面。 |
| 访客 | 中文 | 我看到了，谢谢。电梯需要刷卡才能到九楼吗？ | English | I see it, thank you. Do I need to tap the badge in the elevator to reach the ninth floor? |
| 前台 | English | Yes. Tap it on the panel, then press 9. If it does not work, come back and I’ll help. | 中文 | 是的。请在面板上刷卡，然后按 9。如果无法使用，请回来找我帮忙。 |
| 访客 | 中文 | 好，我先上去。如果 Alex 还没到，我可以在等候区坐一会儿吗？ | English | Okay, I’ll go up now. If Alex hasn’t arrived yet, may I sit in the waiting area for a while? |
| 前台 | English | Of course. The waiting area is directly across from the elevators. | 中文 | 当然可以。等候区就在电梯正对面。 |
| 访客 | 中文 | 太好了。呃，访客卡离开时要还到这里，对吧？ | English | Great. Um, I need to return the visitor badge here when I leave, right? |
| 前台 | English | That’s right. Please return it before 6 p.m. Have a good meeting. | 中文 | 对。请在下午六点前归还。祝您会议顺利。 |

**自动模式检查点**
- 正确识别每条中英文输入并翻译到另一种语言。
- `2 p.m.`、`9th floor`、`Guest-9F`、`6 p.m.` 与公司名不可错误或遗漏。
- “BrightPath，不是 BluePath”及犹豫词“嗯 / 呃”应保留语义。

---

## 场景 2：相机租赁与押金确认

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 顾客 | 中文 | 你好，我想租一台相机两天，最好是适合夜景拍摄的。 | English | Hi, I’d like to rent a camera for two days, preferably one that is good for night photography. |
| 店员 | English | Sure. Are you looking for just the camera body, or do you need a lens as well? | 中文 | 当然。您只需要相机机身，还是也需要镜头？ |
| 顾客 | 中文 | 我需要镜头。嗯，24 到 70 毫米的变焦镜头有吗？ | English | I need a lens. Um, do you have a 24-to-70-millimeter zoom lens? |
| 店员 | English | We do. The camera and that lens are 320 yuan per day, plus a refundable deposit. | 中文 | 有的。相机和那支镜头每天 320 元，另外需要支付可退还的押金。 |
| 顾客 | 中文 | 押金是多少？我用信用卡支付可以吗？ | English | How much is the deposit? Can I pay by credit card? |
| 店员 | English | The deposit is 2,000 yuan. A credit card is fine, but we need to place a temporary hold. | 中文 | 押金是 2,000 元。可以使用信用卡，但我们需要先进行临时预授权。 |
| 顾客 | 中文 | 临时预授权不是直接扣款，对吗？我想确认一下。 | English | A temporary hold is not an actual charge, right? I just want to confirm. |
| 店员 | English | Correct. We release the hold after we inspect the equipment when you return it. | 中文 | 对，不是直接扣款。您归还设备后，我们检查完毕就会解除预授权。 |
| 顾客 | 中文 | 好。电池和存储卡包括在租金里吗？我明天一早六点要出发。 | English | Okay. Are batteries and a memory card included in the rental? I need to leave at 6 a.m. tomorrow. |
| 店员 | English | Two batteries are included. A 64-gigabyte memory card costs an extra 40 yuan for the rental period. | 中文 | 包含两块电池。64GB 存储卡在整个租期内额外收费 40 元。 |
| 顾客 | 中文 | 那我也要一张存储卡。归还时间是后天下午几点？ | English | Then I’ll take a memory card as well. What time do I need to return everything the day after tomorrow? |
| 店员 | English | Please return it by 7 p.m. If you are late, an additional day will be charged. | 中文 | 请在后天晚上七点前归还。如果逾期，将按额外一天收费。 |
| 顾客 | 中文 | 明白。可以先让我检查一下镜头有没有划痕吗？ | English | Understood. Could I inspect the lens for scratches first? |
| 店员 | English | Absolutely. We can record its condition together on this checklist before you leave. | 中文 | 当然可以。您离开前，我们可以一起在这张检查表上记录设备状态。 |
| 顾客 | 中文 | 很好。呃，我的姓名拼音是 LIU YUXIN，不是 LIU YUXING。 | English | Great. Um, my name in pinyin is LIU YUXIN, not LIU YUXING. |
| 店员 | English | Thank you. I’ve corrected it on the rental agreement. Please review it before signing. | 中文 | 谢谢。我已在租赁协议上更正。请在签字前核对一下。 |

**自动模式检查点**
- 金额 `320 / 2,000 / 40 yuan`、`64 GB`、时间 `6 a.m. / 7 p.m.` 不可混淆。
- `temporary hold` 应译为“临时预授权”，不能误译为直接扣款。
- 拼音姓名更正必须完整保留。

---

## 场景 3：邻里噪音沟通

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 住户 | 中文 | 你好，不好意思打扰。昨晚十一点以后你们房间的音乐有点大。 | English | Hi, sorry to bother you. The music from your apartment was a bit loud after 11 last night. |
| Neighbor | English | I’m really sorry. We had a friend visiting, and I didn’t realize the walls were so thin. | 中文 | 真的很抱歉。我们当时有朋友来访，我没意识到墙这么薄。 |
| 住户 | 中文 | 没关系，我理解。只是我今天早上七点要上班，所以昨晚没怎么睡好。 | English | It’s okay, I understand. I just had to work at 7 this morning, so I didn’t sleep very well. |
| Neighbor | English | That makes sense. We’ll keep it down tonight, and we won’t use the speaker after 10 p.m. | 中文 | 这可以理解。今晚我们会小声一些，晚上十点后不会再用音响。 |
| 住户 | 中文 | 谢谢。其实不是要你们完全安静，正常说话没问题。 | English | Thank you. I’m not asking you to be completely silent; normal conversation is fine. |
| Neighbor | English | I appreciate that. If we are ever too loud again, please message me instead of waiting. | 中文 | 谢谢您的理解。如果我们以后又太吵，请直接给我发消息，不用一直忍着。 |
| 住户 | 中文 | 好的。我是 1203 室的王宁，方便的话加一下联系方式。 | English | Okay. I’m Wang Ning from apartment 1203. If it’s convenient, let’s exchange contact details. |
| Neighbor | English | Sure, I’m Emma from 1205. Here is my number, but please do not share it with the building group. | 中文 | 好的，我是 1205 室的 Emma。这是我的号码，但请不要分享到楼栋群里。 |
| 住户 | 中文 | 放心，我只会在有需要时联系。对了，周末白天装修的声音你们能接受吗？ | English | Don’t worry, I’ll only contact you if needed. By the way, would daytime renovation noise on the weekend be okay with you? |
| Neighbor | English | Saturday afternoon is fine, but Sunday morning would be difficult because I work nights. | 中文 | 周六下午可以，但周日早上不太方便，因为我上夜班。 |
| 住户 | 中文 | 明白了，那我尽量安排在周六下午两点到五点之间。 | English | Understood. Then I’ll try to schedule it between 2 and 5 p.m. on Saturday afternoon. |
| Neighbor | English | That would be very helpful. Thanks for asking in advance. | 中文 | 那会很有帮助。谢谢您提前询问。 |
| 住户 | 中文 | 不客气。呃，如果施工延长，我会提前告诉你，不会突然开始。 | English | You’re welcome. Um, if the work runs longer, I’ll let you know in advance; it won’t start unexpectedly. |
| Neighbor | English | I appreciate it. I’m glad we could sort this out directly. | 中文 | 谢谢，我很感激。很高兴我们能直接把这件事沟通好。 |
| 住户 | 中文 | 我也是。那今天先不打扰你了，晚安。 | English | Me too. I won’t disturb you any further tonight. Good night. |
| Neighbor | English | Good night, and thank you again. | 中文 | 晚安，再次谢谢您。 |

**自动模式检查点**
- 识别投诉、缓和语气与礼貌协商，而非将其翻为指责。
- `11 p.m.`、`10 p.m.`、房号 `1203/1205`、周六 `2–5 p.m.` 应正确。
- “不要分享联系方式”是隐私要求，不可遗漏。
