# 对话翻译 App 模拟测试文案

> 形式：实时双人双向对话；每个场景 6 轮。<br>
> 越南语：标准书写、偏南方日常口语。<br>
> 说明：`呃 / 嗯 / I—I / uh / ừm` 等应尽量被保留。

---

## 一、中英对话

### 1. 机场改签

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 旅客 | 你好，呃，我原本是下午三点二十飞上海的，但航班取消了。 | Hi, um, my original flight to Shanghai at 3:20 p.m. was cancelled. |
| 工作人员 | I’m sorry about that. Could I see your passport and booking confirmation? | 很抱歉。请给我看一下您的护照和订票确认信息，好吗？ |
| 旅客 | 可以。这是护照。确认号是 MU7……不好意思，是 MU7294。 | Sure. Here is my passport. The confirmation number is MU7… sorry, it’s MU7294. |
| 工作人员 | Thank you. There is a flight at 6:45 p.m., but it has only one seat left. | 谢谢。有一趟晚上六点四十五分的航班，但只剩一个座位了。 |
| 旅客 | 嗯，可以帮我改到那一班吗？我还需要托运行李，两个箱子。 | Um, could you change me to that flight? I also need to check two suitcases. |
| 工作人员 | Yes. The change is free because of the cancellation, and your baggage allowance stays the same. | 可以。由于航班取消，改签免费，您的行李额度保持不变。 |
| 旅客 | 太好了。请问登机口还是在 A 区吗？我第一次来这个机场，有点找不到方向。 | That’s great. Will the gate still be in Area A? It’s my first time at this airport, and I’m a little lost. |
| 工作人员 | No, it will depart from Gate C18. Please be there by 6:05 p.m. | 不，航班将从 C18 登机口出发。请您在晚上六点零五分前到达。 |
| 旅客 | C18，晚上六点零五分前，明白了。那个……登机牌需要重新打印吗？ | Gate C18, before 6:05 p.m., got it. Um… do I need a new boarding pass printed? |
| 工作人员 | Yes, I’ll print it now. Your checked bags should be dropped off at Counter 12. | 是的，我现在为您打印。托运行李请到 12 号柜台办理。 |
| 旅客 | 谢谢。要是我赶不上这班，呃，还有别的选择吗？ | Thank you. If I don’t make this flight, um, are there any other options? |
| 工作人员 | There is one tomorrow morning, but let’s make sure you get to C18 on time today. | 明天早上还有一班，不过我们先确保您今天能准时到达 C18。 |

**测试点**
- 航班号 `MU7294`、时间 `3:20 p.m.`、`6:45 p.m.`、`6:05 p.m.` 不应丢失或改写。
- “MU7……不好意思，是 MU7294”应保留自我修正。
- `Area A`、`Gate C18`、`Counter 12` 应准确区分。
- “only one seat left”应正确传达“仅剩一个座位”。

### 2. 商务会议

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 项目经理 | 大家下午好。呃，我们先确认一下，今天的会议预计四十五分钟。 | Good afternoon, everyone. Um, first, let’s confirm that today’s meeting is expected to last forty-five minutes. |
| 客户代表 | Sounds good. We mainly want to discuss the delivery date and the revised quote. | 听起来不错。我们主要想讨论交付日期和修改后的报价。 |
| 项目经理 | 关于交付，我们原计划在 6 月 18 日完成，但测试多花了一点时间。 | Regarding delivery, we originally planned to finish on June 18, but testing took a little longer. |
| 客户代表 | Do you mean the date will be delayed, or are you still confident about June 18? | 您的意思是日期会延期，还是您仍然有信心在 6 月 18 日完成？ |
| 项目经理 | 嗯，准确地说，我们需要延期三天，改到 6 月 21 日。 | Um, to be precise, we need a three-day extension, moving it to June 21. |
| 客户代表 | I appreciate the clarification. Will that affect the training session scheduled for June 24? | 感谢您的说明。这会影响安排在 6 月 24 日的培训吗？ |
| 项目经理 | 不会。培训材料已经完成了，只是正式上线会稍微晚一点。 | No. The training materials are already complete; only the official launch will be slightly later. |
| 客户代表 | Okay. And about the quote—your email mentioned 18,500 US dollars. Is tax included? | 好的。关于报价，您的邮件提到 18,500 美元。这个价格含税吗？ |
| 项目经理 | 抱歉，我说得不够清楚。18,500 美元不含税，含税总额大约是 20,165 美元。 | Sorry, I wasn’t clear enough. The 18,500 dollars excludes tax, and the total including tax is about 20,165 dollars. |
| 客户代表 | That’s higher than our budget. Could you offer a small discount if we pay in full this month? | 这高于我们的预算。如果我们这个月全额付款，您能提供一点折扣吗？ |
| 项目经理 | 我不能当场保证，不过我可以今天和财务确认，最晚明天上午十点前回复您。 | I can’t promise that on the spot, but I can check with Finance today and get back to you by 10 a.m. tomorrow at the latest. |
| 客户代表 | That works for us. Please send the updated timeline in writing as well. | 这样可以。也请您以书面形式发送更新后的时间表。 |

**测试点**
- 日期 `June 18 / 21 / 24` 与金额 `$18,500 / $20,165` 必须准确。
- “不含税”与“含税总额”不可混淆。
- “不能当场保证”应体现礼貌但不承诺的语气。
- 注意 `training materials` 与 `official launch` 是不同事项。

### 3. 朋友约饭

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 小林 | 你今晚有空吗？我本来想约你去吃火锅，不过，呃，我还没订位。 | Are you free tonight? I was thinking of inviting you for hot pot, but, um, I haven’t made a reservation yet. |
| Emma | I might be free after seven, but I need to finish something at work first. | 我七点以后可能有空，但我得先完成一点工作。 |
| 小林 | 没问题。那我们约七点半？在南京西路那家“川味里”怎么样？ | No problem. How about 7:30? What do you think of the restaurant “Chuan Wei Li” on West Nanjing Road? |
| Emma | Is that the place near Exit 4 of the metro station? I think I’ve been there once. | 是地铁站 4 号出口附近的那家吗？我觉得我去过一次。 |
| 小林 | 对，不过不是 4 号出口，是 5 号出口。走路大概两分钟。 | Yes, but it’s not Exit 4—it’s Exit 5. It’s about a two-minute walk. |
| Emma | Ah, right. Thanks for correcting me. Is it very spicy? I can eat a little spicy food, but not too much. | 啊，对。谢谢你纠正我。那家很辣吗？我能吃一点辣，但不能太辣。 |
| 小林 | 可以点鸳鸯锅，一边辣一边不辣。嗯，不过他们的微辣对有些人来说也挺辣的。 | We can order a split pot, one spicy side and one non-spicy side. Um, though their mild spice can still be pretty spicy for some people. |
| Emma | Then let’s start with mild. And please don’t order too much—I hate wasting food. | 那我们先点微辣吧。而且请不要点太多——我不喜欢浪费食物。 |
| 小林 | 好，我先订两个人的位置。你有什么不吃的吗？ | Okay, I’ll reserve a table for two first. Is there anything you don’t eat? |
| Emma | No beef, please. I’m not allergic; I just prefer not to eat it. | 请不要牛肉。我不是过敏，只是更愿意不吃。 |
| 小林 | 好的，牛肉不点。要是订不到七点半，我再问你八点行不行。 | Got it, no beef. If I can’t get a table for 7:30, I’ll ask whether 8:00 works for you. |
| Emma | Perfect. Text me when you know—I mean, when the reservation is confirmed. | 太好了。你确定后给我发消息——我是说，订位确认后。 |

**测试点**
- 地址定位中的 `Exit 4` 与 `Exit 5` 应正确表达自我纠正。
- “鸳鸯锅”宜译为 `split pot` 或 `divided hot pot`，不能误译为两份锅。
- “不吃牛肉”不是过敏，需保留澄清。
- “微辣”存在相对程度，不宜绝对化为“不辣”。

---

## 二、中越对话

### 4. 餐厅点餐

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 顾客 | 你好，呃，请问现在还有两个人的位置吗？最好靠窗，不过没有也没关系。 | Chào bạn, ừm, bây giờ còn bàn cho hai người không? Tốt nhất là gần cửa sổ, nhưng không có cũng không sao. |
| 服务员 | Dạ còn ạ. Bàn gần cửa sổ phải đợi khoảng mười phút, còn bàn bên trong thì có ngay. | Dạ, còn ạ. Bàn gần cửa sổ cần đợi khoảng mười phút, còn bàn bên trong thì có ngay. |
| 顾客 | 那我们等十分钟吧。对了，你们菜单有英文版吗？ | Vậy bọn tôi đợi mười phút nhé. À, bên mình có thực đơn tiếng Anh không? |
| 服务员 | Có ạ, em mang ra ngay. Hai anh chị có dị ứng với món gì không? | 有的，我马上拿来。两位有对什么食物过敏吗？ |
| 顾客 | 我对花生过敏。嗯，鱼露可以吃，但是不要放花生碎。 | Tôi bị dị ứng với đậu phộng. Ừm, tôi ăn được nước mắm, nhưng đừng cho đậu phộng giã nhé. |
| 服务员 | Dạ được. Món nào có đậu phộng em sẽ ghi chú cho bếp không cho vào. | 好的。含花生的菜我会备注让厨房不要放。 |
| 顾客 | 我想要一份牛肉河粉，牛肉要熟一点。还有春卷，先来两份。 | Tôi muốn một tô phở bò, thịt bò chín kỹ một chút. Và nem rán, trước mắt cho hai phần. |
| 服务员 | Anh muốn phở bò tái chín hay bò viên? Và nem rán bên mình là chả giò chiên. | 您想要半熟熟牛肉河粉，还是牛肉丸？另外，我们的春卷是炸春卷。 |
| 顾客 | 不好意思，我刚才没说清楚。我想要牛肉片，不要牛肉丸，而且要全熟。 | Xin lỗi, lúc nãy tôi nói chưa rõ. Tôi muốn thịt bò lát, không lấy bò viên, và thịt phải chín hoàn toàn. |
| 服务员 | Dạ, một phở bò thịt chín kỹ, không bò viên; hai phần chả giò chiên. Anh uống gì ạ? | 好的，一份全熟牛肉片河粉，不要牛肉丸；两份炸春卷。您喝什么？ |
| 顾客 | 一杯不加糖的冰咖啡，另外一杯椰子水。总共大概多少钱？ | Một cà phê đá không đường, và một nước dừa. Tổng cộng khoảng bao nhiêu tiền vậy? |
| 服务员 | Khoảng 285 nghìn đồng ạ. Giá chính xác sẽ có trên hóa đơn sau khi anh chị dùng xong. | 大约 285,000 越南盾。准确价格会在两位用餐后的账单上显示。 |

**测试点**
- `花生过敏` 必须明确为过敏，不能弱化成“不喜欢”。
- `鱼露可以吃` 与 `不要花生碎` 是独立限制。
- `bò viên` 是牛肉丸，不可与牛肉片混淆。
- 金额 `285 nghìn đồng` 应正确理解为约 285,000 越南盾。

### 5. 租房看房

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 租客 | 你好，我是昨天联系你的李娜。嗯，我想看一下第二郡这套公寓。 | Chào bạn, tôi là Lý Na, người đã liên hệ với bạn hôm qua. Ừm, tôi muốn xem căn hộ này ở Quận 2. |
| 房东 | Chào chị. Dạ, căn ở số 18 đường Nguyễn Văn Hưởng, phường Thảo Điền. Chị đang ở gần đây không? | 您好。房子在 Nguyễn Văn Hưởng 路 18 号，Thảo Điền 坊。您现在住在附近吗？ |
| 租客 | 我现在住在第一区，不过打车过来大概二十分钟。请问房子在几楼？ | Hiện tại tôi ở Quận 1, nhưng đi taxi tới đây khoảng hai mươi phút. Cho tôi hỏi căn hộ ở tầng mấy? |
| 房东 | Căn ở tầng 12, có thang máy. Tiền thuê là 16 triệu đồng một tháng, chưa bao gồm điện nước. | 公寓在 12 楼，有电梯。月租 1600 万越南盾，不包括水电费。 |
| 租客 | 十六百万，嗯，是每个月吗？押金需要多少？ | Mười sáu triệu, ừm, là mỗi tháng đúng không? Cần đặt cọc bao nhiêu? |
| 房东 | Đúng rồi, 16 triệu mỗi tháng. Tiền cọc là hai tháng tiền nhà, tức 32 triệu đồng. | 是的，每月 1600 万。押金是两个月房租，也就是 3200 万越南盾。 |
| 租客 | 我明白了。网络费和物业费包含在里面吗，还是要另外付？ | Tôi hiểu rồi. Phí internet và phí quản lý đã bao gồm chưa, hay phải trả riêng? |
| 房东 | Phí quản lý đã bao gồm, còn internet khoảng 250 nghìn đồng một tháng. | 物业费已包含，网络费每月大约 25 万越南盾。 |
| 租客 | 房子可以养猫吗？我有一只猫，很安静，不会抓家具。 | Có được nuôi mèo trong căn hộ không? Tôi có một con mèo, nó rất ngoan và không cào đồ đạc. |
| 房东 | Nuôi một con mèo thì được, nhưng chị cần báo trước với ban quản lý. | 可以养一只猫，但您需要提前通知物业管理处。 |
| 租客 | 好的。我今天可以先看看，但我不会马上签合同，想考虑到明天。 | Được. Hôm nay tôi có thể xem trước, nhưng tôi sẽ không ký hợp đồng ngay; tôi muốn suy nghĩ đến ngày mai. |
| 房东 | Không sao ạ. Chị cứ xem nhà trước, nếu phù hợp thì mai trước 5 giờ chiều báo em nhé. | 没关系。您先看房，如果合适，请在明天下午五点前告诉我。 |

**测试点**
- 越南地址 `18 đường Nguyễn Văn Hưởng, phường Thảo Điền` 应保留专名，不应意译。
- `16 triệu`、`32 triệu`、`250 nghìn` 的金额单位必须正确。
- “不马上签合同”是礼貌拒绝/延迟决定，不应译成完全拒租。
- `Quận 1`、`Quận 2` 是行政区名，应准确保留。

### 6. 快递沟通

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 收件人 | 你好，我收到短信说快递到了，但是我现在不在家。 | Chào bạn, tôi nhận được tin nhắn nói là hàng đã tới, nhưng hiện giờ tôi không ở nhà. |
| 快递员 | Dạ, em đang đứng trước tòa B, khu chung cư Masteri. Chị có thể xuống nhận trong vài phút không? | 好的，我现在站在 Masteri 公寓区 B 栋前。您几分钟内能下来取吗？ |
| 收件人 | 呃，我还在开会，至少还要二十分钟。可以先放在前台吗？ | Ừm, tôi vẫn đang họp, ít nhất còn khoảng hai mươi phút nữa. Bạn có thể để ở quầy lễ tân trước được không? |
| 快递员 | Em có thể để ở lễ tân, nhưng chị vui lòng nhắn cho lễ tân xác nhận giúp em nhé. | 我可以放在前台，但请您发消息给前台确认一下。 |
| 收件人 | 好，我现在发消息。对了，包裹上写的是李娜，不是 Lina Lee，别交错了。 | Được, tôi sẽ nhắn ngay bây giờ. À, trên gói hàng ghi là Lý Na, không phải Lina Lee, đừng giao nhầm nhé. |
| 快递员 | Dạ, em thấy tên người nhận là Lý Na và số điện thoại đuôi 4821. Đúng không chị? | 好的，我看到收件人是 Lý Na，手机号尾号是 4821。对吗？ |
| 收件人 | 对，4821。包裹是一个小纸箱吗？里面是电脑配件，不能淋雨。 | Đúng, 4821. Gói hàng là một thùng carton nhỏ phải không? Bên trong là phụ kiện máy tính, không được để ướt mưa. |
| 快递员 | Đúng rồi, một thùng nhỏ. Hiện tại trời chưa mưa, nhưng em sẽ để bên trong quầy lễ tân. | 对，是一个小纸箱。现在还没下雨，但我会把它放到前台里面。 |
| 收件人 | 谢谢。需要我现在在线支付运费吗？ | Cảm ơn bạn. Tôi có cần thanh toán phí giao hàng trực tuyến ngay bây giờ không? |
| 快递员 | Không cần ạ, phí giao hàng là 32 nghìn đồng và đã được thanh toán rồi. | 不需要，运费是 3.2 万越南盾，已经付过了。 |
| 收件人 | 哦，我还以为要付。嗯，那麻烦你拍一张放在前台的照片发给我。 | Ồ, tôi tưởng là phải trả. Ừm, vậy phiền bạn chụp một tấm ảnh lúc để ở quầy lễ tân rồi gửi cho tôi nhé. |
| 快递员 | Dạ được, em sẽ gửi ảnh qua ứng dụng ngay sau khi giao cho lễ tân. | 好的，交给前台后我会立即通过应用发送照片。 |

**测试点**
- 姓名 `李娜 / Lý Na / Lina Lee` 的辨识与纠正。
- 电话尾号 `4821`、运费 `32 nghìn đồng` 不能出错。
- “不能淋雨”应表达为防潮、防雨要求，而非“不要下雨”。
- `前台`、`lễ tân`、`quầy lễ tân` 语义需一致。

---

## 三、英越对话

### 7. 医院问诊（仅翻译模拟）

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Patient | Hi, uh, I’d like to see a doctor. I’ve had a sore throat since Monday. | Chào bạn, ừm, tôi muốn gặp bác sĩ. Tôi bị đau họng từ thứ Hai. |
| Receptionist | Dạ, do you have an appointment, or would you like to register as a walk-in patient? | Dạ, bạn đã đặt lịch hẹn chưa, hay muốn đăng ký khám không hẹn trước? |
| Patient | I don’t have an appointment. My name is Daniel Brooks, and I have my passport with me. | Tôi chưa đặt lịch. Tôi tên là Daniel Brooks, và tôi có mang theo hộ chiếu. |
| Receptionist | Thank you. Please fill out this form and write down any allergies you have. | Cảm ơn bạn. Vui lòng điền vào mẫu này và ghi rõ nếu bạn có dị ứng gì. |
| Patient | I’m allergic to penicillin. Um, I’m not sure how to spell it in Vietnamese. | Tôi bị dị ứng với penicillin. Ừm, tôi không chắc viết tên thuốc đó bằng tiếng Việt như thế nào. |
| Receptionist | That’s okay. You can write it in English, and I’ll note it for the doctor. | Không sao. Bạn có thể viết bằng tiếng Anh, tôi sẽ ghi chú cho bác sĩ. |
| Patient | Thanks. Is there a long wait? I have an online meeting at 3 p.m. | Cảm ơn. Có phải chờ lâu không? Tôi có một cuộc họp trực tuyến lúc 3 giờ chiều. |
| Receptionist | The current wait is about thirty to forty minutes. The doctor may call you earlier if another patient cancels. | Hiện tại bạn cần chờ khoảng ba mươi đến bốn mươi phút. Bác sĩ có thể gọi bạn sớm hơn nếu có bệnh nhân khác hủy lịch. |
| Patient | Okay. Could you tell me where the restroom is while I wait? | Được. Trong lúc chờ, bạn có thể cho tôi biết nhà vệ sinh ở đâu không? |
| Receptionist | It’s down the hall, on the left, next to Room 203. | Đi hết hành lang, ở bên trái, cạnh Phòng 203. |
| Patient | Room 203, got it. And, sorry, do I pay before or after seeing the doctor? | Phòng 203, tôi hiểu rồi. Và, xin lỗi, tôi thanh toán trước hay sau khi gặp bác sĩ? |
| Receptionist | Please pay after the consultation. The cashier is on the first floor. | Vui lòng thanh toán sau khi khám. Quầy thu ngân ở tầng một. |

**测试点**
- 仅用于语言翻译验证，不应被扩展为诊断或治疗建议。
- `penicillin` 应保留药物专名，且明确为过敏信息。
- `3 p.m.`、`30–40 minutes`、`Room 203` 等实体信息必须准确。
- `walk-in patient` 应译为“无预约就诊/现场挂号”，不应译成“步行患者”。

### 8. 酒店入住

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Guest | Hi, I have a reservation under the name Sarah Miller. Um, I think it’s for three nights. | Chào bạn, tôi có đặt phòng dưới tên Sarah Miller. Ừm, tôi nghĩ là đặt ba đêm. |
| Receptionist | Welcome, Ms. Miller. Yes, you booked a deluxe room from July 12 to July 15. | Chào mừng cô Miller. Đúng rồi, cô đã đặt một phòng deluxe từ ngày 12 tháng 7 đến ngày 15 tháng 7. |
| Guest | Great. Could you confirm whether breakfast is included? | Tuyệt. Bạn có thể xác nhận giúp tôi bữa sáng có bao gồm không? |
| Receptionist | Breakfast is included for two people, from 6:30 to 10:00 a.m. on the second floor. | Bữa sáng được bao gồm cho hai người, phục vụ từ 6 giờ 30 đến 10 giờ sáng ở tầng hai. |
| Guest | Okay. Actually, there will only be one of me tonight—my friend arrives tomorrow morning. | Được. Thực ra tối nay chỉ có một mình tôi—bạn tôi sẽ đến vào sáng mai. |
| Receptionist | No problem. Please let us know your friend’s full name so we can add it to the reservation. | Không vấn đề gì. Vui lòng cho chúng tôi biết họ tên đầy đủ của bạn cô để chúng tôi thêm vào đặt phòng. |
| Guest | Her name is Mai Nguyen. That’s M-A-I, Nguyen. Sorry, I know the spelling can be confusing. | Cô ấy tên là Mai Nguyen. Viết là M-A-I, Nguyen. Xin lỗi, tôi biết cách viết có thể gây nhầm lẫn. |
| Receptionist | Thank you. I’ve added Mai Nguyen as the second guest, arriving on July 13. | Cảm ơn. Tôi đã thêm Mai Nguyen làm khách thứ hai, đến vào ngày 13 tháng 7. |
| Guest | One more thing: is it possible to have a quiet room? I’m a light sleeper. | Còn một việc nữa: có thể sắp xếp cho tôi một phòng yên tĩnh không? Tôi ngủ rất dễ tỉnh. |
| Receptionist | We can offer you a room away from the elevator. It faces the garden, but it has two single beds. | Chúng tôi có thể bố trí một phòng xa thang máy. Phòng hướng ra vườn, nhưng có hai giường đơn. |
| Guest | Hmm, I booked one queen bed. I’d prefer to keep that, even if the room is a little less quiet. | Hmm, tôi đã đặt một giường cỡ queen. Tôi muốn giữ loại giường đó, dù phòng có thể bớt yên tĩnh hơn một chút. |
| Receptionist | Certainly. We’ll keep your queen-bed room and place a quiet-room request in your booking. | Dĩ nhiên. Chúng tôi sẽ giữ phòng có giường queen của cô và thêm yêu cầu phòng yên tĩnh vào đặt phòng. |

**测试点**
- 日期 `July 12–15` 与朋友抵达日 `July 13` 不应混淆。
- `three nights` 需要与日期范围一致。
- `queen bed`、`two single beds` 是不同房型需求。
- 姓名拼写 `Mai Nguyen` 应正确保留。

### 9. 日常购物

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Customer | Excuse me, do you have this jacket in a medium? The one on the display is too small. | Xin lỗi, bạn có áo khoác này cỡ M không? Cái trên mẫu trưng bày nhỏ quá. |
| Salesperson | Dạ, we have medium and large. Would you like to try them on? | Dạ, bên em có cỡ M và L. Bạn có muốn thử không? |
| Customer | Yes, please. Um, is there a fitting room nearby? | Có, làm ơn. Ừm, phòng thử đồ có ở gần đây không? |
| Salesperson | It’s behind the mirrors, on your right. You can take up to five items inside. | Phòng thử đồ ở phía sau dãy gương, bên phải của bạn. Bạn có thể mang tối đa năm món vào trong. |
| Customer | Thanks. I also saw a sign saying “buy two, get one 50% off.” Does that apply to jackets? | Cảm ơn. Tôi cũng thấy biển ghi “mua hai, món thứ ba giảm 50%”. Khuyến mãi đó có áp dụng cho áo khoác không? |
| Salesperson | It applies to selected items only. This jacket is included, but the discounted item must be of equal or lower value. | Khuyến mãi chỉ áp dụng cho một số mặt hàng. Áo khoác này được áp dụng, nhưng món giảm giá phải có giá bằng hoặc thấp hơn. |
| Customer | I see. So if I buy two jackets at 890,000 dong each, the third one gets half off? | Tôi hiểu rồi. Vậy nếu tôi mua hai áo khoác, mỗi cái 890.000 đồng, thì cái thứ ba được giảm một nửa phải không? |
| Salesperson | Yes, as long as the third jacket costs 890,000 dong or less. | Đúng, miễn là áo khoác thứ ba có giá 890.000 đồng hoặc thấp hơn. |
| Customer | Hmm, that makes sense. But I only need one today, so I’ll pass on the promotion. | Hmm, hợp lý. Nhưng hôm nay tôi chỉ cần một cái thôi, nên tôi xin không dùng khuyến mãi. |
| Salesperson | No problem at all. Would you like to check the color in navy as well? | Hoàn toàn không sao. Bạn có muốn xem thêm màu xanh navy không? |
| Customer | Maybe. Is navy darker than this blue one, or is it just a different shade? | Có lẽ. Màu xanh navy đậm hơn màu xanh này hay chỉ là một tông màu khác? |
| Salesperson | It’s darker, almost black in low light. I’ll bring one in medium for you. | Màu đó đậm hơn, gần như đen khi ánh sáng yếu. Tôi sẽ mang một chiếc cỡ M cho bạn. |

**测试点**
- 促销规则应准确表达：买两件，第三件半价，且第三件价格不得更高。
- 金额 `890,000 dong` 与折扣 `50% off` 不应错误转换。
- “I’ll pass on the promotion”是礼貌拒绝，并非拒绝购买。
- `navy` 应与一般 `blue` 区分为“深蓝/藏青”。

---

## 四、翻译质量验收记录表

> 使用方式：每完成一个场景，填写一张“场景验收表”；若发现具体错误，再在“问题明细表”逐条记录。<br>
> 勾选：`☐` 未检查 / `☑` 通过 / `☒` 有问题 / `—` 不适用。

### 场景验收表（每个场景一张）

| 测试信息 | 记录 |
|---|---|
| 场景编号 / 名称 |  |
| 语言方向 | ☐ 中→英 ☐ 英→中 ☐ 中→越 ☐ 越→中 ☐ 英→越 ☐ 越→英 |
| 测试日期 / 测试人 |  |
| App 版本 / 设备 |  |
| 网络或离线状态 | ☐ 在线 ☐ 弱网 ☐ 离线 |
| 总体结论 | ☐ 通过 ☐ 有条件通过 ☐ 不通过 |

| 验收项目 | ☑ 通过 | ☒ 有问题 | — 不适用 | 备注 / 问题编号 |
|---|:---:|:---:|:---:|---|
| 译文准确传达原意，无明显漏译、错译或添加 | ☐ | ☐ | ☐ |  |
| 双向翻译方向正确，未输出错误目标语言 | ☐ | ☐ | ☐ |  |
| 人名、地名、品牌、航班号、房间号等专名准确保留 | ☐ | ☐ | ☐ |  |
| 数字、金额、日期、时间、数量和单位准确 | ☐ | ☐ | ☐ |  |
| 否定、条件、比较、程度和因果关系准确 | ☐ | ☐ | ☐ |  |
| 口吃、停顿、思考词和自我修正被合理保留 | ☐ | ☐ | ☐ |  |
| 礼貌程度、正式度与说话者语气保持一致 | ☐ | ☐ | ☐ |  |
| 上下文指代、前后信息和话题切换理解正确 | ☐ | ☐ | ☐ |  |
| 食物禁忌、过敏、医疗、付款等关键信息无风险性误译 | ☐ | ☐ | ☐ |  |
| 术语和词汇前后一致，未出现不合理混语言 | ☐ | ☐ | ☐ |  |
| 译文自然通顺、符合目标语言日常表达 | ☐ | ☐ | ☐ |  |
| 实时输出完整，无截断、重复、延迟异常或错位 | ☐ | ☐ | ☐ |  |
| 标点、大小写、越南语声调符号等格式正确 | ☐ | ☐ | ☐ |  |
| 敏感或个人信息展示符合预期，无意外泄露 | ☐ | ☐ | ☐ |  |

### 问题明细表

| 问题编号 | 场景 / 轮次 | 语言方向 | 原文摘要 | 实际译文摘要 | 问题类型 | 严重程度 | 可复现 | 处理状态 |
|---|---|---|---|---|---|---|---|---|
| P-001 |  |  |  |  | ☐ 漏译 ☐ 错译 ☐ 数字/专名 ☐ 语气 ☐ 上下文 ☐ 格式 ☐ 性能 ☐ 其他 | ☐ 阻断 ☐ 高 ☐ 中 ☐ 低 | ☐ 总是 ☐ 偶发 ☐ 未复现 | ☐ 待处理 ☐ 已修复 ☐ 已验证 |
| P-002 |  |  |  |  | ☐ 漏译 ☐ 错译 ☐ 数字/专名 ☐ 语气 ☐ 上下文 ☐ 格式 ☐ 性能 ☐ 其他 | ☐ 阻断 ☐ 高 ☐ 中 ☐ 低 | ☐ 总是 ☐ 偶发 ☐ 未复现 | ☐ 待处理 ☐ 已修复 ☐ 已验证 |
| P-003 |  |  |  |  | ☐ 漏译 ☐ 错译 ☐ 数字/专名 ☐ 语气 ☐ 上下文 ☐ 格式 ☐ 性能 ☐ 其他 | ☐ 阻断 ☐ 高 ☐ 中 ☐ 低 | ☐ 总是 ☐ 偶发 ☐ 未复现 | ☐ 待处理 ☐ 已修复 ☐ 已验证 |
| P-004 |  |  |  |  | ☐ 漏译 ☐ 错译 ☐ 数字/专名 ☐ 语气 ☐ 上下文 ☐ 格式 ☐ 性能 ☐ 其他 | ☐ 阻断 ☐ 高 ☐ 中 ☐ 低 | ☐ 总是 ☐ 偶发 ☐ 未复现 | ☐ 待处理 ☐ 已修复 ☐ 已验证 |
| P-005 |  |  |  |  | ☐ 漏译 ☐ 错译 ☐ 数字/专名 ☐ 语气 ☐ 上下文 ☐ 格式 ☐ 性能 ☐ 其他 | ☐ 阻断 ☐ 高 ☐ 中 ☐ 低 | ☐ 总是 ☐ 偶发 ☐ 未复现 | ☐ 待处理 ☐ 已修复 ☐ 已验证 |

### 严重程度说明

| 等级 | 判定标准 |
|---|---|
| 阻断 | 无法完成翻译流程、翻译方向错误，或过敏/医疗/金额等关键信息出现可能造成严重后果的误译。 |
| 高 | 核心语义错误、漏译或专名/数字错误，足以导致用户作出错误决定。 |
| 中 | 语气、上下文、表达自然度或局部信息有误，但通常可由用户结合上下文理解。 |
| 低 | 标点、大小写、轻微措辞不自然或不影响理解的格式问题。 |
