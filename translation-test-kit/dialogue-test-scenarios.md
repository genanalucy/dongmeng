# 对话翻译 App 模拟测试文案

> 形式：实时双人双向对话；每个场景 8 轮（双方各发言 8 次）。
> 越南语：标准书写、偏南方日常口语。  
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

| 旅客 | 那明天早上的航班是几点？我得先确认酒店要不要多住一晚。 | What time is tomorrow morning’s flight? I need to confirm whether I have to stay at the hotel for another night. |
| 工作人员 | It leaves at 8:10 a.m. Since there is a seat today, I recommend taking the 6:45 p.m. flight. | 明早的航班八点十分起飞。因为今天还有座位，我建议您乘坐晚上六点四十五分那班。 |
| 旅客 | 好，那就今天这班。麻烦把新行程单也发到我的邮箱。 | Okay, I’ll take today’s flight. Please send the new itinerary to my email as well. |
| 工作人员 | Certainly. The updated itinerary will arrive within five minutes. | 当然。更新后的行程单将在五分钟内发送给您。 |
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

| 项目经理 | 另外，新的时间表会标明测试完成、上线准备和最终交付三个节点。 | In addition, the new timeline will show three milestones: testing completion, launch preparation, and final delivery. |
| 客户代表 | Please make the dependencies clear, especially anything that requires input from our team. | 请明确标注依赖项，尤其是需要我们团队配合的事项。 |
| 项目经理 | 没问题。我们会把需要您确认的内容单独列出来，避免再耽误进度。 | No problem. We’ll list the items requiring your confirmation separately to avoid further delays. |
| 客户代表 | Thank you. Once we receive that, we can arrange the internal review. | 谢谢。收到后，我们就可以安排内部评审。 |
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

| 小林 | 对了，那家店只接受线上排队，我现在先取号，到了会提醒我们。 | By the way, the restaurant only has online queuing. I’ll get a number now, and it will notify us when it’s our turn. |
| Emma | Great. If the wait is longer than thirty minutes, let’s choose somewhere else nearby. | 太好了。如果要等超过三十分钟，我们就换去附近别的地方。 |
| 小林 | 好，我看到旁边还有一家面馆，不过它八点半就关门。 | Okay. I see a noodle shop nearby too, but it closes at 8:30 p.m. |
| Emma | Then hot pot first, noodle shop as a backup. See you later! | 那就优先火锅，面馆作为备选。待会儿见！ |
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

| 顾客 | 对了，河粉里也不要香菜。我刚才忘了说，不好意思。 | À, xin đừng cho rau mùi vào phở nữa. Lúc nãy tôi quên nói, xin lỗi. |
| 服务员 | Dạ, không rau mùi và không đậu phộng. Em đã ghi rõ rồi ạ. | 好的，不要香菜，也不要花生。我已经清楚备注了。 |
| 顾客 | 谢谢。我们可以先付钱吗？等会儿可能要赶时间。 | Cảm ơn bạn. Bọn tôi có thể thanh toán trước không? Lát nữa có thể phải đi gấp. |
| 服务员 | Dạ được, nhưng nếu gọi thêm món thì mình thanh toán phần thêm sau nhé. | 可以，但如果之后加菜，新增部分请稍后再结账。 |
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

| 租客 | 如果我决定租，最早可以什么时候入住？我目前的合同月底到期。 | Nếu tôi quyết định thuê, sớm nhất khi nào có thể dọn vào? Hợp đồng hiện tại của tôi hết hạn cuối tháng này. |
| 房东 | Căn hộ có thể vào ở từ ngày 28. Nhưng chị cần ký hợp đồng và đặt cọc trước ngày đó. | 公寓从 28 号起可以入住，但您需要在那之前签合同并支付押金。 |
| 租客 | 好的，日期合适。我明天回复前，也会先确认一下猫的登记流程。 | Được, ngày đó phù hợp. Trước khi trả lời vào ngày mai, tôi cũng sẽ xác nhận thủ tục đăng ký cho mèo. |
| 房东 | Dạ, chị cứ nhắn em nếu cần ảnh hợp đồng mẫu hoặc danh sách giấy tờ. | 好的。如果您需要合同样本照片或所需材料清单，随时给我发消息。 |
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

| 收件人 | 我已经和前台确认过了，他们说可以代收。请放在写着“包裹架”的地方。 | Tôi đã xác nhận với lễ tân rồi, họ nói có thể nhận giúp. Xin hãy để ở chỗ ghi “kệ nhận hàng”. |
| 快递员 | Dạ, em vừa giao cho lễ tân tên Hùng và đã chụp ảnh. | Dạ, tôi vừa giao cho nhân viên lễ tân tên Hùng và đã chụp ảnh. |
| 收件人 | 收到了照片，谢谢你。麻烦确认一下包裹没有破损。 | Tôi đã nhận được ảnh, cảm ơn bạn. Phiền bạn xác nhận giúp là gói hàng không bị hư hỏng nhé. |
| 快递员 | Em xác nhận thùng còn nguyên vẹn, không bị móp hay ướt. | Tôi xác nhận thùng còn nguyên vẹn, không bị móp hoặc ướt. |
**测试点**
- 姓名 `李娜 / Lý Na / Lina Lee` 的辨识与纠正。
- 电话尾号 `4821`、运费 `32 nghìn đồng` 不能出错。
- “不能淋雨”应表达为防潮、防雨要求，而非“不要下雨”。
- `前台`、`lễ tân`、`quầy lễ tân` 语义需一致。

---

## 三、英越对话

> 本组为英语与越南语母语者轮流发言；每条原话后的参考译文均为对方语言。

### 7. 医院问诊（仅翻译模拟）

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Patient | Hi, uh, I’d like to see a doctor. I’ve had a sore throat since Monday. | Chào bạn, ừm, tôi muốn gặp bác sĩ. Tôi bị đau họng từ thứ Hai. |
| Receptionist | Dạ, bạn đã đặt lịch hẹn chưa, hay muốn đăng ký khám không hẹn trước? | Do you have an appointment, or would you like to register as a walk-in patient? |
| Patient | I don’t have an appointment. My name is Daniel Brooks, and I have my passport with me. | Tôi chưa đặt lịch. Tôi tên là Daniel Brooks, và tôi có mang theo hộ chiếu. |
| Receptionist | Cảm ơn bạn. Vui lòng điền vào mẫu này và ghi rõ nếu bạn có dị ứng gì. | Thank you. Please fill out this form and write down any allergies you have. |
| Patient | I’m allergic to penicillin. Um, I’m not sure how to spell it in Vietnamese. | Tôi bị dị ứng với penicillin. Ừm, tôi không chắc viết tên thuốc đó bằng tiếng Việt như thế nào. |
| Receptionist | Không sao. Bạn có thể viết bằng tiếng Anh, tôi sẽ ghi chú cho bác sĩ. | That’s okay. You can write it in English, and I’ll note it for the doctor. |
| Patient | Thanks. Is there a long wait? I have an online meeting at 3 p.m. | Cảm ơn. Có phải chờ lâu không? Tôi có một cuộc họp trực tuyến lúc 3 giờ chiều. |
| Receptionist | Hiện tại bạn cần chờ khoảng ba mươi đến bốn mươi phút. Bác sĩ có thể gọi bạn sớm hơn nếu có bệnh nhân khác hủy lịch. | The current wait is about thirty to forty minutes. The doctor may call you earlier if another patient cancels. |
| Patient | Okay. Could you tell me where the restroom is while I wait? | Được. Trong lúc chờ, bạn có thể cho tôi biết nhà vệ sinh ở đâu không? |
| Receptionist | Đi hết hành lang, ở bên trái, cạnh Phòng 203. | It’s down the hall, on the left, next to Room 203. |
| Patient | Room 203, got it. And, sorry, do I pay before or after seeing the doctor? | Phòng 203, tôi hiểu rồi. Và xin lỗi, tôi thanh toán trước hay sau khi gặp bác sĩ? |
| Receptionist | Vui lòng thanh toán sau khi khám. Quầy thu ngân ở tầng một. | Please pay after the consultation. The cashier is on the first floor. |
| Patient | If I’m called during my meeting, could the nurse send me a text instead? | Nếu tôi đang họp khi đến lượt, y tá có thể nhắn tin cho tôi thay được không? |
| Receptionist | Dạ được, nhưng xin đừng rời khỏi khu vực chờ. Chúng tôi chỉ giữ lượt trong mười phút. | Yes, but please do not leave the waiting area. We can only hold your place for ten minutes. |
| Patient | Understood. I’ll stay nearby and keep my phone on. | Tôi hiểu rồi. Tôi sẽ ở gần đây và bật điện thoại. |
| Receptionist | Cảm ơn bạn. Khi hoàn thành mẫu đơn, vui lòng mang lại quầy này. | Thank you. When you finish the form, please bring it back to this desk. |

**测试点**
- 仅用于语言翻译验证，不应被扩展为诊断或治疗建议。
- `penicillin` 应保留药物专名，且明确为过敏信息。
- `3 p.m.`、`30–40 minutes`、`Room 203`、十分钟等实体信息必须准确。
- 英语与越南语发言交替时，应分别正确翻往对方语言。

### 8. 酒店入住

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Guest | Hi, I have a reservation under the name Sarah Miller. Um, I think it’s for three nights. | Chào bạn, tôi có đặt phòng dưới tên Sarah Miller. Ừm, tôi nghĩ là đặt ba đêm. |
| Receptionist | Chào mừng cô Miller. Đúng rồi, cô đã đặt một phòng deluxe từ ngày 12 tháng 7 đến ngày 15 tháng 7. | Welcome, Ms. Miller. Yes, you booked a deluxe room from July 12 to July 15. |
| Guest | Great. Could you confirm whether breakfast is included? | Tuyệt. Bạn có thể xác nhận giúp tôi bữa sáng có bao gồm không? |
| Receptionist | Bữa sáng được bao gồm cho hai người, phục vụ từ 6 giờ 30 đến 10 giờ sáng ở tầng hai. | Breakfast is included for two people, from 6:30 to 10:00 a.m. on the second floor. |
| Guest | Okay. Actually, there will only be one of me tonight—my friend arrives tomorrow morning. | Được. Thực ra tối nay chỉ có một mình tôi—bạn tôi sẽ đến vào sáng mai. |
| Receptionist | Không vấn đề gì. Vui lòng cho chúng tôi biết họ tên đầy đủ của bạn cô để chúng tôi thêm vào đặt phòng. | No problem. Please let us know your friend’s full name so we can add it to the reservation. |
| Guest | Her name is Mai Nguyen. That’s M-A-I, Nguyen. Sorry, I know the spelling can be confusing. | Cô ấy tên là Mai Nguyen. Viết là M-A-I, Nguyen. Xin lỗi, tôi biết cách viết có thể gây nhầm lẫn. |
| Receptionist | Cảm ơn. Tôi đã thêm Mai Nguyen làm khách thứ hai, đến vào ngày 13 tháng 7. | Thank you. I’ve added Mai Nguyen as the second guest, arriving on July 13. |
| Guest | One more thing: is it possible to have a quiet room? I’m a light sleeper. | Còn một việc nữa: có thể sắp xếp cho tôi một phòng yên tĩnh không? Tôi ngủ rất dễ tỉnh. |
| Receptionist | Chúng tôi có thể bố trí một phòng xa thang máy. Phòng hướng ra vườn, nhưng có hai giường đơn. | We can offer you a room away from the elevator. It faces the garden, but it has two single beds. |
| Guest | Hmm, I booked one queen bed. I’d prefer to keep that, even if the room is a little less quiet. | Hmm, tôi đã đặt một giường cỡ queen. Tôi muốn giữ loại giường đó, dù phòng có thể bớt yên tĩnh hơn một chút. |
| Receptionist | Dĩ nhiên. Chúng tôi sẽ giữ phòng có giường queen của cô và thêm yêu cầu phòng yên tĩnh vào đặt phòng. | Certainly. We’ll keep your queen-bed room and place a quiet-room request in your booking. |
| Guest | Could I also get two key cards? My friend may arrive before I return from a tour. | Tôi có thể nhận hai thẻ phòng không? Bạn tôi có thể đến trước khi tôi đi tham quan về. |
| Receptionist | Dạ được. Khi bạn cô đến, vui lòng yêu cầu cô ấy xuất trình hộ chiếu tại quầy. | Certainly. When your friend arrives, please ask her to show her passport at the desk. |
| Guest | Perfect. Is the Wi-Fi password printed in the room, or should I write it down now? | Tuyệt. Mật khẩu Wi-Fi được in trong phòng hay tôi nên ghi lại ngay bây giờ? |
| Receptionist | Mật khẩu có trên bìa thẻ phòng. Nếu không kết nối được, hãy gọi số 0 từ điện thoại trong phòng. | The password is on the key-card sleeve. If you cannot connect, dial 0 from the room phone. |

**测试点**
- 日期 `July 12–15` 与朋友抵达日 `July 13` 不应混淆。
- `three nights`、早餐时间、两张房卡和拨号 `0` 必须准确。
- `queen bed`、`two single beds` 是不同房型需求。
- 英越双方轮流发言时，姓名、拼写和条件句不可错译。

### 9. 日常购物

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Customer | Excuse me, do you have this jacket in a medium? The one on the display is too small. | Xin lỗi, bạn có áo khoác này cỡ M không? Cái trên mẫu trưng bày nhỏ quá. |
| Salesperson | Dạ, bên em có cỡ M và L. Bạn có muốn thử không? | Yes, we have medium and large. Would you like to try them on? |
| Customer | Yes, please. Um, is there a fitting room nearby? | Có, làm ơn. Ừm, phòng thử đồ có ở gần đây không? |
| Salesperson | Phòng thử đồ ở phía sau dãy gương, bên phải của bạn. Bạn có thể mang tối đa năm món vào trong. | It’s behind the mirrors, on your right. You can take up to five items inside. |
| Customer | Thanks. I also saw a sign saying “buy two, get one 50% off.” Does that apply to jackets? | Cảm ơn. Tôi cũng thấy biển ghi “mua hai, món thứ ba giảm 50%”. Khuyến mãi đó có áp dụng cho áo khoác không? |
| Salesperson | Khuyến mãi chỉ áp dụng cho một số mặt hàng. Áo khoác này được áp dụng, nhưng món giảm giá phải có giá bằng hoặc thấp hơn. | It applies to selected items only. This jacket is included, but the discounted item must be of equal or lower value. |
| Customer | I see. So if I buy two jackets at 890,000 dong each, the third one gets half off? | Tôi hiểu rồi. Vậy nếu tôi mua hai áo khoác, mỗi cái 890.000 đồng, thì cái thứ ba được giảm một nửa phải không? |
| Salesperson | Đúng, miễn là áo khoác thứ ba có giá 890.000 đồng hoặc thấp hơn. | Yes, as long as the third jacket costs 890,000 dong or less. |
| Customer | Hmm, that makes sense. But I only need one today, so I’ll pass on the promotion. | Hmm, hợp lý. Nhưng hôm nay tôi chỉ cần một cái thôi, nên tôi xin không dùng khuyến mãi. |
| Salesperson | Hoàn toàn không sao. Bạn có muốn xem thêm màu xanh navy không? | No problem at all. Would you like to check the color in navy as well? |
| Customer | Maybe. Is navy darker than this blue one, or is it just a different shade? | Có lẽ. Màu xanh navy đậm hơn màu xanh này hay chỉ là một tông màu khác? |
| Salesperson | Màu đó đậm hơn, gần như đen khi ánh sáng yếu. Tôi sẽ mang một chiếc cỡ M cho bạn. | It’s darker, almost black in low light. I’ll bring one in medium for you. |
| Customer | Before I decide, can I check the return policy? I might be buying this as a gift. | Trước khi quyết định, tôi có thể xem chính sách đổi trả không? Tôi có thể mua món này làm quà. |
| Salesperson | Dạ, hàng chưa mặc và còn nhãn có thể đổi trong bảy ngày, nhưng không hoàn tiền mặt. | Yes. Unworn items with tags can be exchanged within seven days, but we do not offer cash refunds. |
| Customer | Got it—exchange only, not a refund. I’ll take the medium in navy. | Tôi hiểu rồi—chỉ đổi hàng, không hoàn tiền. Tôi lấy cỡ M màu xanh navy. |
| Salesperson | Dạ, em sẽ lấy hàng mới trong kho và mang ra quầy thanh toán cho bạn. | Certainly. I’ll get a new one from the stockroom and bring it to the checkout for you. |

**测试点**
- 促销规则应准确表达：买两件，第三件半价，且第三件价格不得更高。
- 金额 `890,000 dong` 与折扣 `50% off` 不应错误转换。
- “I’ll pass on the promotion”是礼貌拒绝，并非拒绝购买。
- `navy`、退换货条件与“仅换不退”应正确保留。

---

## 四、新增中英对话

### 10. 打车路线变更

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 乘客 | 你好，麻烦去浦东机场二号航站楼。呃，不是国际出发层，是国内出发层。 | Hi, please take me to Terminal 2 at Pudong Airport. Um, not the international departures level—the domestic departures level. |
| Driver | Got it. Do you prefer the fastest route or the cheaper route? | 明白。您想走最快的路线，还是更便宜的路线？ |
| 乘客 | 如果只差十分钟以内，就走便宜一点的。我航班是晚上九点四十。 | If the difference is within ten minutes, please take the cheaper route. My flight is at 9:40 p.m. |
| Driver | The cheaper route is about twelve minutes slower because of traffic near the bridge. | 因为大桥附近堵车，便宜路线大约会慢十二分钟。 |
| 乘客 | 那还是走最快的吧。嗯，请不要走收费很高的那条隧道，除非真的必要。 | Then let’s take the fastest route. Um, please avoid the very expensive tunnel unless it is really necessary. |
| Driver | I understand. The toll should be around 20 yuan, and the trip will take about fifty minutes. | 明白。过路费大约 20 元，全程约五十分钟。 |
| 乘客 | 好。对了，我的定位显示在东门，但我其实在西门咖啡店门口。 | Okay. By the way, my location shows the east gate, but I’m actually in front of the café at the west gate. |
| Driver | Thanks for telling me. I’ll turn around at the next intersection; it may take three more minutes. | 谢谢您告诉我。我会在下一个路口掉头，可能还要三分钟。 |
| 乘客 | 没关系，我等。车牌尾号是 7832，对吗？ | That’s okay, I’ll wait. The last four digits of your plate are 7832, right? |
| Driver | Yes, 7832. I’m in a silver sedan with the roof light on. | 对，7832。我开的是一辆银色轿车，车顶灯亮着。 |
| 乘客 | 我看到了。上车后能开一下空调吗？不要太冷就行。 | I see you. Could you turn on the air conditioning after I get in? Just not too cold. |
| Driver | Of course. Please fasten your seat belt once you’re in. | 当然。上车后请系好安全带。 |
| 乘客 | 如果路上特别堵，麻烦提前告诉我，我可能需要改坐地铁。 | If traffic gets especially bad, please tell me early. I may need to switch to the metro. |
| Driver | Sure. I’ll update you if the estimated arrival time changes by more than ten minutes. | 好的。如果预计到达时间变化超过十分钟，我会通知您。 |
| 乘客 | 谢谢，这样我比较放心。 | Thanks, that makes me feel more at ease. |
| Driver | You’re welcome. We should arrive before 8:50 p.m. if traffic stays as it is. | 不客气。如果路况保持这样，我们应该能在晚上八点五十分前到达。 |

**测试点**
- “国内/国际出发层”、路线偏好、收费条件和定位纠正应准确。
- 时间 `9:40 p.m.`、`8:50 p.m.`、`50 minutes` 与车牌尾号 `7832` 不应错误。
- 条件句“除非真的必要”“如果特别堵”需保持条件关系。

### 11. 客服退款

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 顾客 | 你好，我想申请退款。订单号是 A-45821，不过我不确定是不是在正确的页面。 | Hi, I’d like to request a refund. My order number is A-45821, but I’m not sure I’m on the right page. |
| Support agent | You are in the right place. Could you tell me why you would like a refund? | 您在正确的页面。请问您想退款的原因是什么？ |
| 顾客 | 商品昨天到了，可是颜色和网页图片差很多，而且盒子有点压坏了。 | The item arrived yesterday, but the color is very different from the website photo, and the box is slightly crushed. |
| Support agent | I’m sorry to hear that. Is the item itself damaged, or only the outer box? | 很抱歉听到这个情况。商品本身损坏了吗，还是只有外包装受损？ |
| 顾客 | 嗯，商品本身没坏。我刚才说得不准确，主要是颜色不对。 | Um, the item itself is not damaged. I didn’t say that accurately—the main issue is the color. |
| Support agent | Thank you for clarifying. You can return it within seven days for a full refund. | 感谢您的澄清。您可以在七天内退货并获得全额退款。 |
| 顾客 | 退货运费谁承担？我不想先垫付很高的快递费。 | Who pays for return shipping? I don’t want to pay a high courier fee upfront. |
| Support agent | Because the color differs from the listing, we can provide a prepaid return label. | 因为颜色与商品页面不符，我们可以提供预付费退货标签。 |
| 顾客 | 好的。退款会退回原来的信用卡吗？ | Okay. Will the refund go back to the original credit card? |
| Support agent | Yes. After we receive the return, it usually takes three to five business days. | 会的。我们收到退货后，通常需要三到五个工作日。 |
| 顾客 | 我下周要出差，能不能安排快递明天上午来取？ | I’m traveling next week. Could the courier pick it up tomorrow morning? |
| Support agent | The earliest available pickup is tomorrow between 9 a.m. and noon. | 最早可安排明天上午九点到中午十二点之间取件。 |
| 顾客 | 可以。请把标签和取件时间发到我的邮箱，不要只发 App 通知。 | That works. Please send the label and pickup time to my email, not only through an app notification. |
| Support agent | Certainly. I’ve sent the email to the address ending in @example.com. | 当然。我已发送到以 `@example.com` 结尾的邮箱地址。 |
| 顾客 | 对，就是那个邮箱。谢谢你帮忙。 | Yes, that’s the correct address. Thank you for your help. |
| Support agent | You’re welcome. Please keep the pickup receipt until the refund is completed. | 不客气。退款完成前，请保留取件凭证。 |

**测试点**
- 区分“包装压坏”与“商品损坏”，并保留自我纠正。
- 订单号、七天、三至五工作日、取件时段及退款路径须准确。
- “prepaid return label”不可误译为先由用户垫付。

### 12. 药店购药（仅翻译模拟）

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 顾客 | 你好，我想买一些缓解感冒症状的非处方药。嗯，我不是来要处方药。 | Hi, I’m looking for some over-the-counter medicine for cold symptoms. Um, I’m not asking for prescription medicine. |
| Pharmacist | Of course. Could you describe your symptoms and how long you have had them? | 当然。您能描述一下症状，以及持续多久了吗？ |
| 顾客 | 我鼻塞、流鼻涕，还有一点咳嗽，大概两天了，没有发烧。 | I have a blocked and runny nose, plus a slight cough, for about two days. I don’t have a fever. |
| Pharmacist | Do you have any medicine allergies or health conditions we should know about? | 您有任何药物过敏或需要我们了解的健康状况吗？ |
| 顾客 | 我对阿司匹林过敏。另外我正在吃降压药，所以不想随便混着吃。 | I’m allergic to aspirin. Also, I’m taking blood-pressure medication, so I don’t want to mix medicines casually. |
| Pharmacist | Thank you for telling me. I can show you options, but please read the label and follow up with a doctor if symptoms worsen. | 谢谢您告知。我可以给您看一些选择，但请阅读标签；若症状加重，请咨询医生。 |
| 顾客 | 好。这个白天吃会不会犯困？我明天要开车。 | Okay. Will this make me drowsy during the day? I need to drive tomorrow. |
| Pharmacist | This daytime formula is labeled non-drowsy, but please check the ingredients and avoid alcohol. | 这款日用配方标注为“不易嗜睡”，但请查看成分并避免饮酒。 |
| 顾客 | 谢谢。那夜间的版本和这个有什么不同？ | Thanks. How is the nighttime version different from this one? |
| Pharmacist | The nighttime version may cause drowsiness and is intended for use before sleep. | 夜间版本可能导致嗜睡，适合睡前使用。 |
| 顾客 | 明白了。我先买日用的这一盒。价格是多少？ | I understand. I’ll take one box of the daytime version first. How much is it? |
| Pharmacist | It is 68 yuan. Please ask a doctor or pharmacist before taking anything else with it. | 是 68 元。服用其他药物前，请咨询医生或药师。 |
| 顾客 | 好的，我会看说明书。要是今晚发烧，我就不自己加量。 | Okay, I’ll read the instructions. If I get a fever tonight, I won’t increase the dose on my own. |
| Pharmacist | That is a good plan. Seek medical advice if you have concerns or severe symptoms. | 这是个好做法。如果您担心或症状严重，请寻求医疗建议。 |
| 顾客 | 谢谢，我只需要翻译和购买帮助。 | Thank you. I only need translation and help with the purchase. |
| Pharmacist | You’re welcome. The checkout counter is just to your left. | 不客气。收银台就在您左边。 |

**测试点**
- 仅作翻译和购买流程测试，不应把文案当作个性化医疗建议。
- `aspirin` 过敏、降压药、嗜睡风险与“不要自行加量”是关键安全信息。
- 药物类型、金额 `68 yuan`、日用/夜用区别不能混淆。

---

## 五、新增中越对话

### 13. 银行支付

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 顾客 | 你好，我想确认一下这笔转账为什么还显示“处理中”。是给房东的 8,500,000 越南盾。 | Chào bạn, tôi muốn hỏi tại sao khoản chuyển tiền này vẫn hiển thị “đang xử lý”. Đây là 8.500.000 đồng Việt Nam gửi cho chủ nhà. |
| 银行职员 | Dạ, chị chuyển khoản lúc nào và dùng ứng dụng hay đến quầy ạ? | 您是什么时候转账的？是通过 App 还是在柜台办理的？ |
| 顾客 | 我今天上午十点零五分用 App 转的。呃，收款人姓名是 Trần Minh Anh。 | Tôi chuyển bằng ứng dụng lúc 10 giờ 05 sáng nay. Ừm, tên người nhận là Trần Minh Anh. |
| 银行职员 | Em kiểm tra thấy giao dịch đang chờ xác minh do số tiền cao hơn hạn mức thường dùng. | 我查到这笔交易正在等待验证，因为金额高于您通常使用的限额。 |
| 顾客 | 那需要我做什么？房东今天下午五点前要收到，不然会收滞纳金。 | Vậy tôi cần làm gì? Chủ nhà cần nhận trước 5 giờ chiều nay, nếu không sẽ tính phí trễ hạn. |
| 银行职员 | Chị cần xác nhận bằng khuôn mặt trong ứng dụng, sau đó giao dịch thường hoàn tất trong khoảng mười lăm phút. | 您需要在 App 中完成人脸确认，之后交易通常会在约十五分钟内完成。 |
| 顾客 | 我刚才试过一次，App 说光线不足。可以在这里帮我处理吗？ | Lúc nãy tôi đã thử một lần, ứng dụng báo ánh sáng không đủ. Tôi có thể xử lý ở đây không? |
| 银行职员 | Dạ được, bên này có khu vực đủ sáng. Nhưng chị không cần đọc mật khẩu hay mã OTP cho bất kỳ ai. | 可以，这边有光线充足的区域。但您不需要向任何人念出密码或 OTP 验证码。 |
| 顾客 | 明白。我确认后，能给我一张转账成功的凭证吗？ | Tôi hiểu. Sau khi xác nhận, tôi có thể nhận biên lai chuyển tiền thành công không? |
| 银行职员 | Có ạ, chị có thể tải biên lai PDF trong lịch sử giao dịch hoặc in tại quầy. | 可以。您可在交易记录中下载 PDF 凭证，也可以在柜台打印。 |
| 顾客 | 我想打印一份，因为房东不太会用 App。 | Tôi muốn in một bản vì chủ nhà không quen dùng ứng dụng. |
| 银行职员 | Dạ, sau khi giao dịch thành công em sẽ in cho chị. | 好的，交易成功后我会为您打印。 |
| 顾客 | 这里显示收款账户尾号 9068，对吗？我怕自己输错了。 | Ở đây hiển thị số tài khoản người nhận có bốn số cuối là 9068, đúng không? Tôi sợ mình đã nhập nhầm. |
| 银行职员 | Đúng rồi, số cuối là 9068 và tên tài khoản khớp với Trần Minh Anh. | 对，尾号是 9068，账户名称与 Trần Minh Anh 一致。 |
| 顾客 | 好的，那我现在去做人脸确认。谢谢。 | Được, vậy bây giờ tôi sẽ đi xác nhận khuôn mặt. Cảm ơn bạn. |
| 银行职员 | Dạ, nếu ứng dụng vẫn báo lỗi, chị quay lại đây để em hỗ trợ tiếp. | 好的，如果 App 仍然报错，您回来这里，我再继续协助。 |

**测试点**
- 金额、时间、账户尾号、人名以及“处理中/验证”状态应准确。
- 密码和 OTP 的安全提醒不可遗漏或反向表达。
- `8,500,000` 的越南盾金额不应被简化为 8,500。

### 14. 维修报修

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 租客 | 你好，我想报修。厨房水槽下面一直滴水，今天早上开始更严重了。 | Chào bạn, tôi muốn báo sửa chữa. Bên dưới bồn rửa bát trong bếp bị nhỏ nước, sáng nay nặng hơn. |
| 管理员 | Dạ, nước có chảy ra sàn nhiều không? Và chị đã khóa van nước chưa? | 水流到地板上很多吗？您关掉水阀了吗？ |
| 租客 | 还没有很多，但柜子里面湿了。呃，我不知道水阀在哪儿。 | Chưa nhiều lắm, nhưng bên trong tủ đã ướt. Ừm, tôi không biết van nước ở đâu. |
| 管理员 | Van thường ở dưới bồn rửa, phía bên phải. Nếu thấy nước chảy mạnh, chị đừng chạm vào ổ điện gần đó. | 水阀通常在水槽下方右侧。如果水流变大，请不要碰附近的插座。 |
| 租客 | 我看到了，是蓝色的那个吗？我怕关错。 | Tôi thấy rồi, có phải cái màu xanh không? Tôi sợ khóa nhầm. |
| 管理员 | Đúng, van tay cầm màu xanh. Chị xoay theo chiều kim đồng hồ cho đến khi chặt vừa phải. | 对，是蓝色手柄阀门。请顺时针转到合适的紧度。 |
| 租客 | 好，现在水小很多了。维修师傅今天能来吗？ | Được, bây giờ nước ít hơn nhiều rồi. Thợ sửa có thể đến hôm nay không? |
| 管理员 | Thợ có thể đến từ 2 giờ đến 4 giờ chiều. Chị có ở nhà không? | 维修师傅可在下午两点到四点之间上门。您在家吗？ |
| 租客 | 两点半以后我在家。不过请在来之前十分钟打电话。 | Tôi có ở nhà sau 2 giờ 30. Nhưng xin gọi điện trước mười phút khi đến. |
| 管理员 | Dạ được. Thợ tên là Quang, sẽ mặc áo đồng phục màu xanh. | 好的。维修师傅叫 Quang，会穿蓝色制服。 |
| 租客 | 如果要更换零件，费用是房东付还是我付？ | Nếu cần thay linh kiện, chi phí do chủ nhà trả hay tôi trả? |
| 管理员 | Nếu hư hỏng do đường ống cũ thì chủ nhà trả; nếu do sử dụng sai thì sẽ kiểm tra rồi thông báo. | 如果是旧管道造成的损坏，由房东支付；如果是使用不当，我们会检查后再通知。 |
| 租客 | 明白。请不要在没告诉我的情况下直接收费。 | Tôi hiểu. Xin đừng tính phí trực tiếp mà không báo cho tôi trước. |
| 管理员 | Dạ, mọi chi phí phát sinh sẽ cần chị xác nhận trước. | 好的，所有额外费用都需要您事先确认。 |
| 租客 | 谢谢，我会把水槽下面清空，方便维修。 | Cảm ơn bạn, tôi sẽ dọn trống khu vực dưới bồn rửa để tiện sửa chữa. |
| 管理员 | Cảm ơn chị. Em đã tạo phiếu báo sửa số M-2047. | 谢谢您。我已创建报修单，编号 M-2047。 |

**测试点**
- 漏水、阀门方向、电气安全和收费条件均属关键信息。
- 时间段、师傅姓名、工单号及“先确认再收费”不可错误。

### 15. 迷路求助

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| 游客 | 你好，不好意思，我好像走错路了。我要去 Ben Thanh 市场，不是去夜市。 | Chào bạn, xin lỗi, hình như tôi đi lạc. Tôi muốn đến chợ Bến Thành, không phải chợ đêm. |
| 路人 | Dạ, chợ Bến Thành ở cách đây khoảng mười phút đi bộ. Chị đang đứng ở đường Lê Lợi. | 对，Bến Thành 市场离这里步行大约十分钟。您现在在 Lê Lợi 路。 |
| 游客 | 谢谢。我手机没电了，所以看不了地图。要一直直走吗？ | Cảm ơn bạn. Điện thoại của tôi hết pin nên không xem bản đồ được. Tôi cứ đi thẳng mãi phải không? |
| 路人 | Chị đi thẳng đến vòng xoay, rồi rẽ trái ở cửa hàng tiện lợi Circle K. | 您一直走到环岛，然后在 Circle K 便利店左转。 |
| 游客 | 左转以后还要过马路吗？这里车很多，我有点紧张。 | Sau khi rẽ trái tôi có phải qua đường không? Ở đây xe nhiều quá, tôi hơi lo. |
| 路人 | Có, nhưng chị nên qua ở đèn giao thông, đừng băng qua giữa đường. | 要过，但您应该在红绿灯处过马路，不要从马路中间穿过。 |
| 游客 | 嗯，红绿灯旁边有一个粉色教堂，是那个方向吗？ | Ừm, cạnh đèn giao thông có một nhà thờ màu hồng, có phải hướng đó không? |
| 路人 | Không, nhà thờ đó ở hướng ngược lại. Chị hãy nhìn biển “Hàm Nghi”, rồi đi về phía chợ. | 不，那个教堂在相反方向。请找 “Hàm Nghi” 的路牌，然后往市场方向走。 |
| 游客 | 我明白了：到环岛、Circle K 左转、在红绿灯过马路。 | Tôi hiểu rồi: đến vòng xoay, rẽ trái ở Circle K, rồi qua đường ở đèn giao thông. |
| 路人 | Đúng rồi. Sau khi qua đường, chợ sẽ ở bên phải chị. | 对。过马路后，市场就在您右边。 |
| 游客 | 非常感谢。走路真的只要十分钟吗？我四点有预约。 | Cảm ơn bạn rất nhiều. Đi bộ thật sự chỉ mất mười phút phải không? Tôi có hẹn lúc 4 giờ. |
| 路人 | Nếu đi bình thường thì khoảng mười phút, nhưng giờ này đông xe, chị nên đi sớm một chút. | 正常走大约十分钟，但这个时间车多，您最好早点出发。 |
| 游客 | 好的，我现在就走。附近有可以借充电宝的地方吗？ | Được, tôi đi ngay bây giờ. Gần đây có chỗ thuê sạc dự phòng không? |
| 路人 | Có một máy cho thuê ở trong Circle K, gần quầy tính tiền. | Circle K 里面靠近收银台有一个充电宝租赁机。 |
| 游客 | 太好了，谢谢你讲得这么清楚。 | Tuyệt quá, cảm ơn bạn đã chỉ rất rõ. |
| 路人 | Không có gì. Chúc chị đến nơi đúng giờ nhé. | 不客气。祝您准时到达。 |

**测试点**
- 方向、左右、相反方向、过街安全及地标名称不能混淆。
- `Ben Thanh/Bến Thành`、`Circle K`、`Hàm Nghi` 专名须保留。
- 四点预约与十分钟步行时间需准确。

---

## 六、新增英越对话

### 16. 手机卡办理

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Customer | Hi, I just arrived today and I need a local SIM card with data. | Chào bạn, tôi vừa đến hôm nay và cần một SIM nội địa có dữ liệu di động. |
| Staff | Dạ, bạn cần dùng trong bao lâu và điện thoại của bạn có hỗ trợ eSIM không? | How long do you need it for, and does your phone support eSIM? |
| Customer | I’ll be here for twelve days. I think my phone supports eSIM, but I’m not completely sure. | Tôi sẽ ở đây mười hai ngày. Tôi nghĩ điện thoại của mình hỗ trợ eSIM, nhưng không hoàn toàn chắc chắn. |
| Staff | Bạn có thể vào phần cài đặt để kiểm tra. Nếu không có eSIM, chúng tôi có SIM vật lý. | You can check in Settings. If it does not support eSIM, we have a physical SIM. |
| Customer | Okay, I found it. It says eSIM is supported. What plans do you have? | Được, tôi tìm thấy rồi. Máy ghi là có hỗ trợ eSIM. Bạn có những gói nào? |
| Staff | Gói 10 GB trong mười lăm ngày là 180 nghìn đồng; gói 20 GB là 250 nghìn đồng. | The 10 GB plan for fifteen days is 180,000 dong; the 20 GB plan is 250,000 dong. |
| Customer | Is the data high-speed for the whole allowance, or does it slow down after a limit? | Toàn bộ dung lượng có tốc độ cao hay sẽ bị giảm tốc sau một mức giới hạn? |
| Staff | Toàn bộ 10 GB hoặc 20 GB là tốc độ cao. Sau khi dùng hết, bạn có thể mua thêm dữ liệu. | All 10 GB or 20 GB is high-speed. After you use it, you can buy more data. |
| Customer | Hmm, I’ll take 10 GB. Can I use it for a hotspot too? | Hmm, tôi lấy gói 10 GB. Tôi có thể dùng để phát Wi-Fi không? |
| Staff | Dạ được, nhưng một số thiết bị có thể yêu cầu cài đặt APN. Em có thể hỗ trợ cài đặt. | Yes, but some devices may require APN settings. I can help you set it up. |
| Customer | Please do. Also, will I need my passport to register? | Làm ơn hỗ trợ tôi. Ngoài ra, tôi có cần hộ chiếu để đăng ký không? |
| Staff | Dạ, chúng tôi cần hộ chiếu bản gốc. Thông tin chỉ được dùng để đăng ký theo quy định. | Yes, we need your original passport. The information is used only for registration as required. |
| Customer | That’s fine. Could you show me where to see my remaining data later? | Không sao. Bạn có thể chỉ tôi cách xem dung lượng còn lại sau này không? |
| Staff | Chị có thể kiểm tra trong ứng dụng này hoặc bấm *101#. | You can check in this app or dial *101#. |
| Customer | Great. Please help me install it before I leave. | Tuyệt. Hãy giúp tôi cài đặt trước khi tôi đi nhé. |
| Staff | Certainly. It should be active within about five minutes. | Dĩ nhiên. SIM sẽ được kích hoạt trong khoảng năm phút. |

**测试点**
- `10 GB/15 days/180,000 dong` 与 `20 GB/250,000 dong` 不可混淆。
- eSIM、实体 SIM、热点、APN、护照登记等术语需准确。
- `*101#` 应完整保留。

### 17. 外卖缺件

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Customer | Hi, my delivery arrived, but one item is missing. I ordered two drinks and only received one. | Chào bạn, đơn giao của tôi đã đến nhưng thiếu một món. Tôi đặt hai đồ uống nhưng chỉ nhận được một. |
| Support agent | Xin lỗi bạn. Bạn có thể cho tôi mã đơn hàng và tên đồ uống bị thiếu không? | I’m sorry about that. Could you give me the order number and the name of the missing drink? |
| Customer | The order is FD-77109. The missing one is a mango smoothie with no sugar. | Mã đơn là FD-77109. Món bị thiếu là sinh tố xoài không đường. |
| Support agent | Cảm ơn bạn. Trong ảnh giao hàng có một túi. Bạn có kiểm tra xem đồ uống có được để riêng không? | Thank you. The delivery photo shows one bag. Could you check whether the drink was packed separately? |
| Customer | I checked the lobby and the bag twice. Um, I received the iced tea, not the smoothie. | Tôi đã kiểm tra sảnh và túi hai lần. Ừm, tôi nhận được trà đá, không phải sinh tố. |
| Support agent | Tôi hiểu. Vậy đây có thể là giao thiếu và giao nhầm đồ uống. | I understand. In that case, this may be both a missing item and a wrong drink. |
| Customer | Exactly. I don’t need a replacement if it will take too long; a refund is okay. | Đúng vậy. Nếu giao lại mất quá lâu thì tôi không cần gửi thay; hoàn tiền cũng được. |
| Support agent | Chúng tôi có thể hoàn lại 45 nghìn đồng cho sinh tố và 18 nghìn đồng cho trà giao nhầm. | We can refund 45,000 dong for the smoothie and 18,000 dong for the incorrect iced tea. |
| Customer | That sounds right. Will the refund go to my card or app balance? | Nghe đúng rồi. Tiền hoàn sẽ về thẻ của tôi hay số dư trong ứng dụng? |
| Support agent | Vì bạn thanh toán bằng thẻ, tiền sẽ về thẻ trong ba đến bảy ngày làm việc. | Since you paid by card, the refund will return to your card in three to seven business days. |
| Customer | Okay. Please don’t issue store credit instead—I won’t be in town next week. | Được. Xin đừng cấp tín dụng cửa hàng thay thế—tuần sau tôi sẽ không ở trong thành phố. |
| Support agent | Dạ, chúng tôi sẽ hoàn tiền về thẻ chứ không cấp tín dụng cửa hàng. | Understood. We will refund your card, not issue store credit. |
| Customer | Could you send me a written confirmation in the app? | Bạn có thể gửi cho tôi xác nhận bằng văn bản trong ứng dụng không? |
| Support agent | Dạ, em đã gửi xác nhận. Mã yêu cầu hoàn tiền là R-3096. | Yes, I have sent the confirmation. Your refund request number is R-3096. |
| Customer | Thanks. I’ll keep that number in case I need to follow up. | Cảm ơn. Tôi sẽ giữ số đó phòng khi cần theo dõi thêm. |
| Support agent | You’re welcome. Please contact us again if the refund does not appear after seven business days. | Không có gì. Vui lòng liên hệ lại nếu tiền hoàn chưa xuất hiện sau bảy ngày làm việc. |

**测试点**
- 缺件与错送是两个问题，不能合并丢失。
- 订单号、两笔退款、三至七工作日和请求号必须准确。
- “退款到原卡、非店铺余额”应明确保留。

### 18. 工作协作

| 发言者 | 原文 | 参考译文 |
|---|---|---|
| Team lead | Hi, can we quickly align on the presentation before the client call tomorrow? | Chào mọi người, chúng ta có thể nhanh chóng thống nhất về bài thuyết trình trước cuộc gọi với khách hàng ngày mai không? |
| Designer | Dạ được. Phần thiết kế đã xong khoảng 90%, nhưng em vẫn chờ số liệu cuối cùng từ nhóm phân tích. | Yes. The design is about 90% complete, but I’m still waiting for the final numbers from the analytics team. |
| Team lead | Thanks. The call starts at 10 a.m. Hanoi time, which is 11 a.m. in Singapore. | Cảm ơn. Cuộc gọi bắt đầu lúc 10 giờ sáng theo giờ Hà Nội, tức 11 giờ sáng ở Singapore. |
| Analyst | I can send the numbers by 4 p.m. today. Um, I need to double-check one assumption first. | Tôi có thể gửi số liệu trước 4 giờ chiều nay. Ừm, tôi cần kiểm tra lại một giả định trước. |
| Team lead | Please flag anything that could change the recommendation, even if the numbers are not final. | Xin hãy báo ngay bất cứ điều gì có thể làm thay đổi khuyến nghị, ngay cả khi số liệu chưa cuối cùng. |
| Designer | Nếu nhận số liệu lúc 4 giờ, em cần khoảng một tiếng để cập nhật biểu đồ và xuất bản PDF. | If I receive the numbers at 4 p.m., I’ll need about an hour to update the charts and export the PDF. |
| Team lead | That works. Let’s set an internal review for 5:30 p.m. today. | Như vậy được. Chúng ta đặt buổi rà soát nội bộ lúc 5 giờ 30 chiều nay nhé. |
| Analyst | Tôi có một cuộc họp khác đến 5 giờ 45. Tôi có thể gửi nhận xét bằng văn bản trước, rồi tham gia sau không? | I have another meeting until 5:45. Can I send written comments first and join afterward? |
| Team lead | Yes, but please prioritize the three slides about pricing and implementation timing. | Có, nhưng hãy ưu tiên ba slide về giá và thời điểm triển khai. |
| Designer | Để xác nhận: bản trình bày dùng tiếng Anh, còn ghi chú nội bộ có thể dùng tiếng Việt, đúng không? | To confirm: the presentation will be in English, while internal notes may be in Vietnamese, correct? |
| Team lead | Correct. Please do not translate the product names or the customer’s company name. | Đúng. Xin đừng dịch tên sản phẩm hoặc tên công ty của khách hàng. |
| Analyst | Hiểu rồi. Em cũng sẽ ghi rõ nguồn dữ liệu ở cuối mỗi biểu đồ. | Understood. I’ll also clearly cite the data source at the bottom of each chart. |
| Team lead | Great. If anything slips past 6 p.m., send me a message rather than waiting until tomorrow. | Tuyệt. Nếu bất cứ việc gì trễ quá 6 giờ chiều, hãy nhắn cho tôi thay vì đợi đến ngày mai. |
| Designer | Dạ. Em sẽ gửi bản nháp PDF vào kênh dự án trước 5 giờ 15. | Yes. I’ll send a draft PDF to the project channel before 5:15. |
| Team lead | Perfect. I’ll review it on my phone during the commute and leave comments. | Hoàn hảo. Tôi sẽ xem trên điện thoại khi đi đường và để lại nhận xét. |
| Analyst | Cảm ơn mọi người. Em sẽ bắt đầu kiểm tra số liệu ngay bây giờ. | Thanks, everyone. I’ll start checking the data now. |

**测试点**
- 跨时区时间 `10 a.m. Hanoi / 11 a.m. Singapore`、截止时间和优先级应准确。
- 90%、三张幻灯片、语言使用规则及不翻译专名的要求不可遗漏。
- 工作语气、条件表达和协作承诺应自然一致。
