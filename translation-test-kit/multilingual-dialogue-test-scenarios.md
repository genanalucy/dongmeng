# 多语对话翻译测试场景

约定：每个场景共 16 句；表格左列为实际说出的源语言，右列为对方所用语言的参考译文。越南语采用偏南方的日常表达。

## 1. 中法｜咖啡店点餐

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 顾客（中文） | 你好，我想点一杯拿铁和一个可颂。 | Bonjour, je voudrais un latte et un croissant, s'il vous plaît. |
| 店员（法语） | Bonjour, vous le prenez sur place ou à emporter ? | 您好，您在店里用还是带走？ |
| 顾客（中文） | 在这里喝，拿铁请用燕麦奶。 | Sur place, et avec du lait d'avoine pour le latte, s'il vous plaît. |
| 店员（法语） | Bien sûr. Quelle taille souhaitez-vous ? | 当然可以。您想要什么杯型？ |
| 顾客（中文） | 中杯就好，不要加糖。 | Une taille moyenne, sans sucre, merci. |
| 店员（法语） | Très bien. Le croissant, nature ou aux amandes ? | 好的。可颂要原味还是杏仁味？ |
| 顾客（中文） | 嗯，原味吧，等等，换成杏仁的。 | Euh, nature… attendez, je vais plutôt prendre celui aux amandes. |
| 店员（法语） | Pas de problème. Souhaitez-vous ajouter quelque chose ? | 没问题。您还想加点别的吗？ |
| 顾客（中文） | 再来一杯常温水，谢谢。 | Une carafe d'eau à température ambiante aussi, merci. |
| 店员（法语） | D'accord. Cela fera huit euros cinquante. | 好的。一共八欧五十。 |
| 顾客（中文） | 可以刷卡吗？ | Puis-je payer par carte ? |
| 店员（法语） | Oui, bien sûr, vous pouvez approcher votre carte ici. | 可以，当然。您可以把卡靠近这里。 |
| 顾客（中文） | 好的，付款成功了吗？ | D'accord, le paiement est-il passé ? |
| 店员（法语） | Oui, c'est bon. Votre commande sera prête dans quelques minutes. | 是的，已经成功。您的订单几分钟后就好。 |
| 顾客（中文） | 好的，我坐窗边等。 | Très bien, je vais attendre près de la fenêtre. |
| 店员（法语） | Parfait, nous vous l'apporterons à table. | 好的，我们会给您送到桌上。 |

测试点：
- 确认“sur place / à emporter”和杯型、植物奶等点单信息。
- 验证“原味吧，等等”自我更正的完整传达。
- 检查金额、支付方式与取餐安排的翻译。

## 2. 中法｜火车改签

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 旅客（中文） | 你好，我想改签今天下午去里昂的火车。 | Bonjour, je voudrais modifier mon billet pour Lyon cet après-midi. |
| 售票员（法语） | Bien sûr. Avez-vous votre référence de réservation ? | 当然。您有订单编号吗？ |
| 旅客（中文） | 有，编号是 Q7M4K2。 | Oui, c'est le Q7M4K2. |
| 售票员（法语） | Votre billet est prévu à 14 h 10. Quel train souhaitez-vous prendre ? | 您原来的车是 14 点 10 分。您想改乘哪一班？ |
| 旅客（中文） | 我想换到四点左右的，最好直达。 | Je voudrais un train vers 16 heures, de préférence direct. |
| 售票员（法语） | Il y a un direct à 16 h 24, mais il reste peu de places. | 16 点 24 分有一班直达车，不过余票不多。 |
| 旅客（中文） | 那就这班。对了，是二等座，不是头等座。 | Alors, je prends celui-là. Ah, et c'est en seconde classe, pas en première. |
| 售票员（法语） | Oui, je vois. Il y a un supplément de douze euros. | 是的，我看到了。需要补十二欧元差价。 |
| 旅客（中文） | 可以，行李额度不变吧？ | D'accord. La franchise de bagages ne change pas, n'est-ce pas ? |
| 售票员（法语） | Non, elle reste la même. Voulez-vous garder votre siège côté fenêtre ? | 不变，还是一样的。您想保留靠窗座位吗？ |
| 旅客（中文） | 如果可以的话，请保留靠窗。 | Si possible, oui, je voudrais garder une place côté fenêtre. |
| 售票员（法语） | C'est fait : voiture 12, place 46. | 已办好：12 号车厢，46 号座位。 |
| 旅客（中文） | 新车票需要打印吗？ | Dois-je imprimer le nouveau billet ? |
| 售票员（法语） | Non, le billet électronique dans votre application suffit. | 不需要，您应用里的电子票就可以。 |
| 旅客（中文） | 谢谢，站台什么时候公布？ | Merci. Quand le quai sera-t-il affiché ? |
| 售票员（法语） | Environ vingt minutes avant le départ. Bon voyage ! | 大约在发车前二十分钟公布。祝您旅途愉快！ |

测试点：
- 验证时间、车次性质、座位等级和补差金额。
- 验证“二等座，不是头等座”的澄清。
- 检查电子票和站台公布时间。

## 3. 中法｜项目报价

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 客户（中文） | 感谢你们发来报价，我想确认服务范围。 | Merci pour votre devis. Je voudrais confirmer le périmètre de la prestation. |
| 顾问（法语） | Avec plaisir. Le devis couvre l'analyse, la conception et deux cycles de révision. | 乐意效劳。报价包含分析、设计和两轮修改。 |
| 客户（中文） | 开发和上线支持也包含在内吗？ | Le développement et l'assistance à la mise en ligne sont-ils également inclus ? |
| 顾问（法语） | Le développement est inclus, mais l'assistance après mise en ligne est proposée en option. | 开发包含在内，但上线后的支持是可选服务。 |
| 客户（中文） | 这个选项每月多少钱？ | Combien coûte cette option par mois ? |
| 顾问（法语） | Elle coûte mille deux cents euros par mois, avec un engagement minimum de trois mois. | 每月一千二百欧元，最短承诺三个月。 |
| 客户（中文） | 我刚才说错了，我问的是项目总价，不是支持费。 | Je me suis mal exprimé : je demandais le prix total du projet, pas celui de l'assistance. |
| 顾问（法语） | Le montant total est de trente-huit mille euros hors taxes. | 项目总价是三万八千欧元，未含税。 |
| 客户（中文） | 付款可以分三期吗？ | Peut-on régler en trois échéances ? |
| 顾问（法语） | Oui : 40 % à la signature, 40 % à la livraison de la version bêta et 20 % à la réception finale. | 可以：签约时付 40%，交付测试版时付 40%，最终验收时付 20%。 |
| 客户（中文） | 报价有效期到什么时候？ | Jusqu'à quand le devis est-il valable ? |
| 顾问（法语） | Il est valable trente jours à compter d'aujourd'hui. | 从今天起有效三十天。 |
| 客户（中文） | 如果增加一种语言，工期会延长吗？ | Si nous ajoutons une langue, le délai sera-t-il prolongé ? |
| 顾问（法语） | Oui, il faudrait prévoir environ deux semaines supplémentaires. | 会的，大约需要额外增加两周。 |
| 客户（中文） | 明白了，请把修改后的方案发给我。 | Je comprends. Merci de m'envoyer la proposition révisée. |
| 顾问（法语） | Très bien, je vous l'envoie avant la fin de journée. | 好的，我会在今天下班前发给您。 |

测试点：
- 核对报价范围、可选支持与未税总价。
- 验证对问题的自我纠正。
- 检查分期比例、有效期与工期变更。

## 4. 中越｜诊所预约

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 患者（中文） | 你好，我想预约明天看皮肤科。 | Chào bạn, tôi muốn đặt lịch khám da liễu vào ngày mai. |
| 接待员（越语） | Dạ, ngày mai bác sĩ còn trống lúc chín giờ sáng hoặc ba giờ chiều. | 您好，明天医生上午九点或下午三点还有空。 |
| 患者（中文） | 下午三点可以，医生是女医生吗？ | Ba giờ chiều được. Bác sĩ là nữ phải không? |
| 接待员（越语） | Dạ đúng rồi, bác sĩ Lan là bác sĩ nữ. | 是的，Lan 医生是女医生。 |
| 患者（中文） | 我脸上起疹子两天了，需要先做检查吗？ | Tôi bị nổi mẩn trên mặt hai ngày rồi, có cần làm xét nghiệm trước không? |
| 接待员（越语） | Bác sĩ sẽ khám trước rồi mới chỉ định xét nghiệm nếu cần. | 医生会先检查，再根据需要开检查单。 |
| 患者（中文） | 好的，我有医保卡，要带身份证吗？ | Dạ. Tôi có thẻ bảo hiểm y tế, có cần mang căn cước không? |
| 接待员（越语） | Có ạ, bạn mang cả thẻ bảo hiểm và căn cước giúp em. | 需要的，请您带上医保卡和身份证。 |
| 患者（中文） | 我刚才说的是明天，对，明天下午三点。 | Lúc nãy tôi nói là ngày mai, đúng rồi, ba giờ chiều ngày mai. |
| 接待员（越语） | Em đã đặt lịch cho bạn vào lúc ba giờ chiều mai rồi. | 我已经为您预约明天下午三点。 |
| 患者（中文） | 需要提前多久到？ | Tôi cần đến sớm bao lâu? |
| 接待员（越语） | Bạn đến trước mười lăm phút để làm thủ tục là được. | 您提前十五分钟来办理手续即可。 |
| 患者（中文） | 诊所地址还是在阮氏明开街吗？ | Phòng khám vẫn ở đường Nguyễn Thị Minh Khai phải không? |
| 接待员（越语） | Dạ đúng, số 125 đường Nguyễn Thị Minh Khai, quận 3. | 是的，在第三区阮氏明开街 125 号。 |
| 患者（中文） | 好的，谢谢你的帮助。 | Dạ, cảm ơn bạn đã hỗ trợ. |
| 接待员（越语） | Không có gì, hẹn gặp bạn vào ngày mai. | 不客气，明天见。 |

测试点：
- 检查日期、时段、医生性别和症状描述。
- 验证携带证件和提前到达要求。
- 验证对预约日期与时间的再次确认。

## 5. 中越｜市场购物

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 顾客（中文） | 你好，这个芒果怎么卖？ | Chào cô, xoài này bán sao ạ? |
| 摊主（越语） | Xoài cát Hòa Lộc là tám mươi nghìn một ký, bạn ơi. | 和禄芒果八万越盾一公斤。 |
| 顾客（中文） | 能帮我挑三个熟一点的吗？ | Cô chọn giúp tôi ba trái chín hơn được không? |
| 摊主（越语） | Được chứ, bạn ăn hôm nay hay để mai ăn? | 可以。您今天吃还是留到明天吃？ |
| 顾客（中文） | 明天吃，别太软。 | Tôi ăn ngày mai, đừng mềm quá nha. |
| 摊主（越语） | Đây, mấy trái này vừa tới. Bạn lấy thêm thanh long không? | 这些刚好。您要不要再买些火龙果？ |
| 顾客（中文） | 嗯，拿两个红心的，不对，拿一个就够了。 | Ừm, lấy hai trái ruột đỏ… à không, một trái là đủ rồi. |
| 摊主（越语） | Dạ, một trái. Còn rau thì sao? | 好的，一个。蔬菜还需要吗？ |
| 顾客（中文） | 我要一把空心菜和半公斤番茄。 | Tôi lấy một bó rau muống và nửa ký cà chua. |
| 摊主（越语） | Tổng cộng là một trăm ba mươi nghìn. | 一共十三万越盾。 |
| 顾客（中文） | 可以扫码付款吗？ | Tôi quét mã để trả tiền được không? |
| 摊主（越语） | Được ạ, mã ở ngay đây. | 可以，二维码就在这里。 |
| 顾客（中文） | 我付好了，请给我一个袋子。 | Tôi trả xong rồi, cho tôi xin một cái túi. |
| 摊主（越语） | Dạ, đây là đồ của bạn. | 好的，这是您的东西。 |
| 顾客（中文） | 谢谢，芒果如果不熟可以放两天吗？ | Cảm ơn cô. Nếu xoài chưa chín thì tôi để hai ngày được không? |
| 摊主（越语） | Được, bạn để ngoài nhiệt độ phòng, đừng cho vào tủ lạnh vội. | 可以，先放在室温下，不要急着放冰箱。 |

测试点：
- 验证水果成熟度、数量与自我更正。
- 检查重量、价格、付款方式的对应关系。
- 核对保存建议的准确性。

## 6. 中越｜工作交接

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 交接人（中文） | 我明天开始休假，先和你交接客户项目。 | Từ ngày mai tôi nghỉ phép, nên muốn bàn giao dự án khách hàng cho bạn trước. |
| 接手人（越语） | Dạ được, hiện dự án đang ở giai đoạn nào rồi? | 可以，目前项目进行到哪个阶段了？ |
| 交接人（中文） | 设计稿已经确认，开发完成了大约百分之七十。 | Bản thiết kế đã được duyệt, phần phát triển hoàn thành khoảng bảy mươi phần trăm rồi. |
| 接手人（越语） | Còn lỗi quan trọng nào chưa xử lý không? | 还有未处理的重要问题吗？ |
| 交接人（中文） | 有两个支付流程的错误，技术团队承诺周三修复。 | Có hai lỗi ở luồng thanh toán, đội kỹ thuật hứa sẽ sửa vào thứ Tư. |
| 接手人（越语） | Khách hàng có biết việc này chưa? | 客户知道这件事了吗？ |
| 交接人（中文） | 知道了，不过他们希望每天收到一次进度更新。 | Họ biết rồi, nhưng họ muốn nhận cập nhật tiến độ mỗi ngày một lần. |
| 接手人（越语） | Vậy tôi sẽ gửi cập nhật vào cuối ngày. Ai là người duyệt bên phía khách hàng? | 那我会在每天结束时发送更新。客户方由谁审批？ |
| 交接人（中文） | 是陈女士。呃，不是陈女士，是她的同事王先生。 | Là chị Trần. À không, là đồng nghiệp của chị ấy, anh Vương. |
| 接手人（越语） | Anh Vương có số điện thoại và email trong tài liệu không? | 资料里有王先生的电话和邮箱吗？ |
| 交接人（中文） | 有，在交接文档第一页，也抄送了项目群。 | Có, ở trang đầu tài liệu bàn giao, và tôi cũng đã thêm vào nhóm dự án. |
| 接手人（越语） | Hạn chót bản thử nghiệm là khi nào? | 测试版的截止时间是什么时候？ |
| 交接人（中文） | 本周五下午五点前必须发给客户。 | Phải gửi cho khách hàng trước năm giờ chiều thứ Sáu tuần này. |
| 接手人（越语） | Tôi rõ rồi. Có việc gì cần ưu tiên sáng mai không? | 我明白了。明天上午有什么需要优先处理的吗？ |
| 交接人（中文） | 请先跟技术团队确认修复排期。 | Trước hết, bạn hãy xác nhận lịch sửa lỗi với đội kỹ thuật. |
| 接手人（越语） | Dạ, tôi sẽ xử lý và báo lại cho bạn. | 好的，我会处理并向您反馈。 |

测试点：
- 核对项目进度、缺陷数量、修复日期和交付期限。
- 验证审批联系人从陈女士更正为王先生。
- 检查交接责任与优先事项。

## 7. 中英｜酒店投诉

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 客人（中文） | 你好，我想投诉一下房间的空调。 | Hello, I'd like to make a complaint about the air conditioner in my room. |
| 前台（英语） | I'm sorry to hear that. What seems to be the problem? | 很抱歉听到这个情况。请问是什么问题？ |
| 客人（中文） | 它整晚都在响，而且房间一点也不凉。 | It was making noise all night, and the room isn't cool at all. |
| 前台（英语） | I apologize. May I have your room number, please? | 很抱歉。请告诉我您的房间号好吗？ |
| 客人（中文） | 是 816 房。 | It's room 816. |
| 前台（英语） | Thank you. We can send maintenance up within thirty minutes. | 谢谢。我们可以在三十分钟内派维修人员上去。 |
| 客人（中文） | 三十分钟太久了，我一会儿要开线上会议。 | Thirty minutes is too long; I have an online meeting shortly. |
| 前台（英语） | I understand. Let me see whether we have another room available. | 我理解。我看看是否有其他可用房间。 |
| 客人（中文） | 如果换房，请保证有安静的网络。 | If I move rooms, please make sure the Wi-Fi is reliable and quiet. |
| 前台（英语） | We have a room on the tenth floor with a stronger Wi-Fi signal. | 我们十楼有一间房，无线网络信号更强。 |
| 客人（中文） | 是双床房吗？哦，我需要的是大床房。 | Is it a twin room? Oh, I need a king room. |
| 前台（英语） | It's a king room, and we can move your luggage for you. | 那是一间大床房，我们可以帮您搬运行李。 |
| 客人（中文） | 好的，钥匙什么时候能拿？ | All right. When can I collect the key? |
| 前台（英语） | The new key is ready now. Please bring your current key card down. | 新房卡现在已经准备好。请把您现有的房卡带下来。 |
| 客人（中文） | 谢谢，希望这次能解决。 | Thank you. I hope this resolves the issue. |
| 前台（英语） | It will, and we're sorry again for the inconvenience. | 会解决的。再次为给您带来的不便致歉。 |

测试点：
- 检查故障、房号、响应时限与会议限制。
- 验证双床房到大床房的澄清。
- 核对换房、网络和房卡安排。

## 8. 中英｜租车取车

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 租客（中文） | 你好，我来取预订的车。 | Hello, I'm here to pick up my reserved car. |
| 工作人员（英语） | Welcome. May I see your driver's license and booking confirmation? | 欢迎。请出示您的驾照和预订确认单。 |
| 租客（中文） | 可以，这是我的驾照和订单。 | Sure, here are my driver's license and reservation. |
| 工作人员（英语） | Thank you. You reserved a compact automatic for three days. | 谢谢。您预订的是一辆紧凑型自动挡车，租期三天。 |
| 租客（中文） | 对，我想确认一下是否包含全险。 | That's right. I'd like to confirm whether full insurance is included. |
| 工作人员（英语） | Basic coverage is included; full coverage is an additional twenty dollars per day. | 基础保险已包含；全险每天另加二十美元。 |
| 租客（中文） | 那先不用全险。请问押金是多少？ | Then I won't take full coverage for now. How much is the deposit? |
| 工作人员（英语） | The deposit is three hundred dollars, held on your credit card. | 押金是三百美元，会冻结在您的信用卡上。 |
| 租客（中文） | 还车时需要加满油吗？ | Do I need to return the car with a full tank? |
| 工作人员（英语） | Yes, please return it full, otherwise we charge a refueling fee. | 是的，请满油归还，否则我们会收取加油服务费。 |
| 租客（中文） | 我预订的是自动挡，不是手动挡，对吧？ | I booked an automatic, not a manual transmission, right? |
| 工作人员（英语） | Correct. This vehicle is automatic and has GPS included. | 没错。这辆车是自动挡，并且包含 GPS。 |
| 租客（中文） | 车上有儿童安全座椅吗？ | Does the car come with a child safety seat? |
| 工作人员（英语） | We can add one for ten dollars per day. Would you like it? | 我们可以加配，每天十美元。您需要吗？ |
| 租客（中文） | 需要，请帮我安装好。 | Yes, please add it and install it for me. |
| 工作人员（英语） | Certainly. Please inspect the car with me before you leave. | 当然。离开前请和我一起检查车辆。 |

测试点：
- 核对车型、租期、保险和押金。
- 验证自动挡与手动挡的确认。
- 检查满油规则、儿童座椅与验车流程。

## 9. 中英｜线上会议

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| 主持人（中文） | 大家好，我们现在开始本周的项目会议。 | Hello everyone, let's start this week's project meeting. |
| 同事（英语） | Hi. Before we begin, can everyone hear me clearly? | 大家好。开始前，大家能清楚听到我吗？ |
| 主持人（中文） | 可以，声音很清楚。先请你更新一下进度。 | Yes, you're coming through clearly. Please give us a progress update first. |
| 同事（英语） | The prototype is complete, and user testing starts on Thursday. | 原型已经完成，用户测试周四开始。 |
| 主持人（中文） | 测试招募了多少位用户？ | How many users have been recruited for the testing? |
| 同事（英语） | Twelve confirmed so far, but we are aiming for fifteen. | 目前确认了十二位，但我们的目标是十五位。 |
| 主持人（中文） | 好的，预算方面有没有变化？ | All right. Have there been any changes to the budget? |
| 同事（英语） | We are slightly over budget because the translation vendor raised its rate. | 由于翻译供应商提高了费率，我们略微超出预算。 |
| 主持人（中文） | 超出多少？我是问金额，不是百分比。 | By how much? I mean the amount, not the percentage. |
| 同事（英语） | About two thousand dollars above the original estimate. | 比原始预估多大约两千美元。 |
| 主持人（中文） | 明白了，我们下周能拿到测试报告吗？ | Understood. Can we have the test report next week? |
| 同事（英语） | Yes, provided we finish all sessions by Friday. | 可以，前提是我们在周五前完成所有场次。 |
| 主持人（中文） | 如果有用户临时取消，请及时通知我。 | If any participant cancels at the last minute, please notify me promptly. |
| 同事（英语） | Will do. I will also share the updated risk log after this call. | 好的。这通电话后我也会分享更新后的风险日志。 |
| 主持人（中文） | 好，那今天先到这里。 | Great, let's wrap up for today. |
| 同事（英语） | Thanks everyone. Have a good day. | 谢谢大家。祝大家今天愉快。 |

测试点：
- 检查会议进度、用户数量、日期和预算金额。
- 验证“金额，不是百分比”的澄清。
- 核对报告交付条件和风险日志后续动作。

## 10. 英法｜博物馆参观

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Visitor（英语） | Hello, is the Impressionist exhibition included in the general admission? | Bonjour, l'exposition impressionniste est-elle comprise dans le billet d'entrée général ? |
| Guide（法语） | Oui, elle est incluse, mais vous devez réserver un créneau horaire. | Yes, it is included, but you need to reserve a time slot. |
| Visitor（英语） | Is there a slot available around two o'clock? | Y a-t-il un créneau disponible vers deux heures ? |
| Guide（法语） | Il reste un créneau à 14 h 15 et un autre à 15 h. | There is one slot left at 2:15 p.m. and another at 3 p.m. |
| Visitor（英语） | I'll take 2:15. How long does the exhibition usually take? | Je prends celui de 14 h 15. Combien de temps dure habituellement la visite ? |
| Guide（法语） | Comptez environ une heure, un peu plus si vous prenez l'audioguide. | Allow about an hour, or a little longer if you take the audio guide. |
| Visitor（英语） | I'd like an audio guide in English, please. | Je voudrais un audioguide en anglais, s'il vous plaît. |
| Guide（法语） | Bien sûr. Il coûte cinq euros et se récupère au vestiaire. | Of course. It costs five euros and can be collected at the cloakroom. |
| Visitor（英语） | Can I take photos inside? | Puis-je prendre des photos à l'intérieur ? |
| Guide（法语） | Sans flash, oui, sauf dans les salles indiquées comme interdites. | Without flash, yes, except in rooms marked as prohibited. |
| Visitor（英语） | Sorry, I meant photos without flash, not video. | Pardon, je voulais dire des photos sans flash, pas des vidéos. |
| Guide（法语） | Dans ce cas, c'est autorisé dans la plupart des salles. | In that case, it is permitted in most rooms. |
| Visitor（英语） | Where can I leave my backpack? | Où puis-je laisser mon sac à dos ? |
| Guide（法语） | Au vestiaire, juste à votre gauche. C'est gratuit. | At the cloakroom, just to your left. It's free. |
| Visitor（英语） | Thank you. Is the café open until closing time? | Merci. Le café reste-t-il ouvert jusqu'à la fermeture ? |
| Guide（法语） | Il ferme à 17 h 30, soit une demi-heure avant le musée. | It closes at 5:30 p.m., which is half an hour before the museum. |

测试点：
- 检查门票、预约时段、语音导览费用。
- 验证照片与视频的澄清。
- 核对拍照规则、寄存处和闭馆时间。

## 11. 英法｜客服订阅取消

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Customer（英语） | Hi, I'd like to cancel my monthly subscription. | Bonjour, je voudrais résilier mon abonnement mensuel. |
| Agent（法语） | Bien sûr. Pouvez-vous me communiquer l'adresse e-mail associée au compte ? | Of course. Could you give me the email address associated with the account? |
| Customer（英语） | It's alex.morgan@example.com. | C'est alex.morgan@example.com. |
| Agent（法语） | Merci. Je vois un abonnement Premium renouvelé le 3 mai. | Thank you. I see a Premium subscription renewed on May 3rd. |
| Customer（英语） | That's correct. Will I lose access immediately? | C'est exact. Vais-je perdre l'accès immédiatement ? |
| Agent（法语） | Non, vous conserverez l'accès jusqu'à la fin de votre période de facturation. | No, you will keep access until the end of your billing period. |
| Customer（英语） | I was charged yesterday. Can I get a refund? | J'ai été débité hier. Puis-je obtenir un remboursement ? |
| Agent（法语） | Selon notre politique, nous pouvons rembourser les demandes faites dans les quatorze jours suivant le renouvellement. | According to our policy, we can refund requests made within fourteen days of renewal. |
| Customer（英语） | Great. I want to cancel and request the refund. | Très bien. Je souhaite résilier et demander le remboursement. |
| Agent（法语） | D'accord. Je vais traiter les deux demandes. | All right. I will process both requests. |
| Customer（英语） | Please make sure it's the Premium plan, not my separate storage add-on. | Veuillez vous assurer qu'il s'agit du forfait Premium, et non de mon option de stockage séparée. |
| Agent（法语） | Je confirme : seul l'abonnement Premium sera résilié. | I confirm: only the Premium subscription will be cancelled. |
| Customer（英语） | How long will the refund take to appear? | Dans combien de temps le remboursement apparaîtra-t-il ? |
| Agent（法语） | Comptez cinq à sept jours ouvrés sur votre moyen de paiement initial. | Please allow five to seven business days for it to appear on your original payment method. |
| Customer（英语） | Thank you for clarifying that. | Merci pour cette précision. |
| Agent（法语） | Avec plaisir. Vous recevrez un e-mail de confirmation dans quelques minutes. | You're welcome. You will receive a confirmation email within a few minutes. |

测试点：
- 检查账户识别、续费日期与访问截止时间。
- 验证 Premium 与独立存储附加项的澄清。
- 核对退款条件、方式和到账时限。

## 12. 英法｜团队排期

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Manager（英语） | Let's review the schedule for the next release. | Examinons le calendrier de la prochaine version. |
| Designer（法语） | D'accord. La maquette finale peut être prête mardi matin. | All right. The final mock-up can be ready Tuesday morning. |
| Manager（英语） | Can engineering start implementation that same day? | L'équipe technique peut-elle commencer l'implémentation le même jour ? |
| Designer（法语） | Oui, à condition que les contenus soient validés avant midi. | Yes, provided that the content is approved before noon. |
| Manager（英语） | Who is responsible for approving the content? | Qui est responsable de la validation des contenus ? |
| Designer（法语） | C'est Sophie, mais elle est absente lundi après-midi. | C'est Sophie, mais elle sera absente lundi après-midi. |
| Manager（英语） | Then let's ask her to review it Monday morning. | Alors, demandons-lui de le relire lundi matin. |
| Designer（法语） | Très bien. Pour les tests, il faudrait au moins trois jours. | Very well. For testing, we would need at least three days. |
| Manager（英语） | I thought testing was two days—sorry, is three days the minimum? | Je pensais que les tests duraient deux jours — pardon, trois jours est-ce le minimum ? |
| Designer（法语） | Oui, deux jours ne suffiraient pas pour les tests sur mobile. | Yes, two days would not be enough for mobile testing. |
| Manager（英语） | In that case, when can we safely release? | Dans ce cas, quand pouvons-nous publier la version sans risque ? |
| Designer（法语） | Vendredi en fin de journée, si aucune anomalie bloquante n'est trouvée. | Friday at the end of the day, if no blocking issue is found. |
| Manager（英语） | Please add a contingency day in case we find one. | Merci d'ajouter une journée de marge au cas où nous en trouverions une. |
| Designer（法语） | Je peux proposer un déploiement lundi suivant comme solution de repli. | I can propose deployment the following Monday as a fallback. |
| Manager（英语） | That works. I'll update the roadmap today. | Cela me convient. Je mettrai à jour la feuille de route aujourd'hui. |
| Designer（法语） | Parfait, je vous enverrai le planning détaillé cet après-midi. | Perfect. I will send you the detailed schedule this afternoon. |

测试点：
- 核对设计、内容审核、测试和发布的排期条件。
- 验证测试天数的自我澄清。
- 检查阻塞问题与备用发布日期。

## 13. 英越｜机场行李延误

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Passenger（英语） | Hello, my checked bag did not arrive on the carousel. | Chào bạn, hành lý ký gửi của tôi chưa ra ở băng chuyền. |
| Agent（越语） | Dạ, bạn cho em xem thẻ hành lý và hộ chiếu được không? | Certainly. Could I see your baggage tag and passport? |
| Passenger（英语） | Here they are. I flew in from Singapore on flight VN652. | Đây ạ. Tôi bay từ Singapore trên chuyến VN652. |
| Agent（越语） | Em kiểm tra thấy hành lý của bạn bị chậm chuyến và sẽ về trên chuyến tối nay. | I can see that your bag was delayed and will arrive on tonight's flight. |
| Passenger（英语） | Will it be delivered to my hotel? | Hành lý có được giao đến khách sạn của tôi không? |
| Agent（越语） | Dạ được, bên em sẽ giao miễn phí trong vòng hai mươi bốn giờ. | Yes. We will deliver it free of charge within twenty-four hours. |
| Passenger（英语） | My hotel is the Riverside Hotel, room 504. | Khách sạn của tôi là Riverside Hotel, phòng 504. |
| Agent（越语） | Bạn cho em xin số điện thoại để tài xế liên hệ khi giao nhé. | Please give me a phone number so the driver can contact you upon delivery. |
| Passenger（英语） | It's +44 7700 900123. Actually, please use WhatsApp, not regular calls. | Số của tôi là +44 7700 900123. À, xin liên hệ qua WhatsApp, đừng gọi điện thường. |
| Agent（越语） | Dạ, em đã ghi chú là liên hệ qua WhatsApp. | Certainly, I have noted that you prefer WhatsApp. |
| Passenger（英语） | Can I buy essential items and claim the cost? | Tôi có thể mua đồ thiết yếu rồi yêu cầu hoàn tiền không? |
| Agent（越语） | Bạn giữ lại hóa đơn, rồi gửi yêu cầu bồi hoàn theo hướng dẫn trong email này. | Please keep the receipts and submit a reimbursement claim following the instructions in this email. |
| Passenger（英语） | Is there a reference number for this report? | Báo cáo này có mã tham chiếu không? |
| Agent（越语） | Dạ có, mã của bạn là SGN65248. | Yes, your reference number is SGN65248. |
| Passenger（英语） | Thank you. Please notify me once the bag is found. | Cảm ơn bạn. Làm ơn báo cho tôi khi tìm thấy hành lý. |
| Agent（越语） | Dạ, bên em sẽ cập nhật ngay khi hành lý được quét vào hệ thống. | Certainly. We will update you as soon as the bag is scanned into the system. |

测试点：
- 检查航班、交付时限、酒店房号和报告编号。
- 验证电话改为 WhatsApp 的更正。
- 核对必需品报销流程。

## 14. 英越｜餐厅订位

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Customer（英语） | Hi, I'd like to book a table for four this Saturday evening. | Chào bạn, tôi muốn đặt bàn cho bốn người vào tối thứ Bảy này. |
| Host（越语） | Dạ, bạn muốn đặt lúc mấy giờ ạ? | Certainly. What time would you like to book? |
| Customer（英语） | Around 7:30 p.m., if possible. | Khoảng bảy giờ rưỡi tối, nếu được. |
| Host（越语） | Bảy giờ rưỡi còn bàn trong nhà, còn bàn ngoài trời thì hết rồi. | We have an indoor table at 7:30, but the outdoor tables are fully booked. |
| Customer（英语） | Indoor is fine. Is there a minimum spend? | Bàn trong nhà cũng được. Có mức chi tiêu tối thiểu không? |
| Host（越语） | Dạ không có, nhưng cuối tuần bên em nhận đặt cọc năm trăm nghìn. | No, but on weekends we require a deposit of five hundred thousand dong. |
| Customer（英语） | That's fine. One guest is vegetarian. | Được. Có một khách ăn chay. |
| Host（越语） | Dạ, nhà hàng có thực đơn chay. Bạn có dị ứng gì cần lưu ý không? | Certainly, the restaurant has a vegetarian menu. Do you have any allergies we should note? |
| Customer（英语） | Yes, one person is allergic to peanuts—sorry, specifically to all nuts. | Có, một người dị ứng đậu phộng — xin lỗi, cụ thể là dị ứng tất cả các loại hạt. |
| Host（越语） | Em đã ghi chú dị ứng với tất cả các loại hạt cho bàn của bạn. | I have noted an allergy to all nuts for your table. |
| Customer（英语） | Could you confirm the booking under Maya Chen? | Bạn xác nhận giúp tôi đặt bàn tên Maya Chen được không? |
| Host（越语） | Dạ, bàn bốn người lúc bảy giờ rưỡi thứ Bảy, tên Maya Chen. | Yes: a table for four at 7:30 on Saturday, under Maya Chen. |
| Customer（英语） | How do I pay the deposit? | Tôi thanh toán tiền cọc bằng cách nào? |
| Host（越语） | Em sẽ gửi cho bạn đường link thanh toán qua tin nhắn ngay bây giờ. | I will send you a payment link by message right away. |
| Customer（英语） | Great, thank you. | Tuyệt, cảm ơn bạn. |
| Host（越语） | Dạ, hẹn gặp bạn vào thứ Bảy. | You're welcome. We look forward to seeing you on Saturday. |

测试点：
- 核对人数、日期、时间、区域和订金。
- 验证从花生过敏澄清到全部坚果过敏。
- 检查姓名确认和付款链接安排。

## 15. 英越｜产品演示

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Presenter（英语） | Thanks for joining. Today I'll show you our inventory dashboard. | Cảm ơn mọi người đã tham gia. Hôm nay tôi sẽ giới thiệu bảng điều khiển hàng tồn kho của chúng tôi. |
| Client（越语） | Dạ, bên tôi muốn theo dõi tồn kho theo từng chi nhánh. | Vâng, công ty chúng tôi muốn theo dõi tồn kho theo từng chi nhánh. |
| Presenter（英语） | You can filter the dashboard by branch, warehouse, or product category. | Bạn có thể lọc bảng điều khiển theo chi nhánh, kho hoặc danh mục sản phẩm. |
| Client（越语） | Dữ liệu cập nhật theo thời gian thực hay theo ngày? | Does the data update in real time or daily? |
| Presenter（英语） | It updates every fifteen minutes by default. | Theo mặc định, dữ liệu cập nhật mỗi mười lăm phút. |
| Client（越语） | Bên tôi cần cập nhật nhanh hơn vào giờ cao điểm. | We need faster updates during peak hours. |
| Presenter（英语） | We can configure five-minute updates for selected locations. | Chúng tôi có thể cấu hình cập nhật mỗi năm phút cho các địa điểm được chọn. |
| Client（越语） | Tính năng cảnh báo có gửi qua Zalo không? | Can the alert feature send notifications through Zalo? |
| Presenter（英语） | Not yet, but it currently supports email and Slack notifications. | Chưa, nhưng hiện tại tính năng này hỗ trợ thông báo qua email và Slack. |
| Client（越语） | À, tôi tưởng là có Zalo rồi. Vậy email cũng được. | Oh, I thought Zalo was already supported. In that case, email is fine too. |
| Presenter（英语） | Would you like alerts for low stock, delayed shipments, or both? | Bạn muốn nhận cảnh báo cho tồn kho thấp, giao hàng chậm hay cả hai? |
| Client（越语） | Cả hai, nhưng chỉ gửi cho quản lý kho. | Both, but only send them to the warehouse manager. |
| Presenter（英语） | That can be set with role-based notification rules. | Điều đó có thể được thiết lập bằng quy tắc thông báo theo vai trò. |
| Client（越语） | Bên tôi có thể dùng thử trước khi ký hợp đồng không? | Can we try it before signing a contract? |
| Presenter（英语） | Yes, we offer a fourteen-day trial with sample data. | Có, chúng tôi cung cấp bản dùng thử mười bốn ngày với dữ liệu mẫu. |
| Client（越语） | Dạ, bạn gửi thông tin dùng thử cho tôi nhé. | Vâng, vui lòng gửi cho tôi thông tin dùng thử. |

测试点：
- 检查筛选维度、更新频率和定向配置。
- 验证 Zalo 支持状态的误解与澄清。
- 核对告警渠道、对象和试用期限。

## 16. 法越｜药店购买

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Client（法语） | Bonjour, je cherche quelque chose pour un mal de gorge. | Chào bạn, tôi đang tìm thuốc gì đó cho đau họng. |
| Pharmacien（越语） | Dạ, bạn bị đau họng từ bao lâu rồi? | Depuis combien de temps avez-vous mal à la gorge ? |
| Client（法语） | Depuis trois jours, et j'ai un peu de fièvre le soir. | Tôi bị ba ngày rồi, và buổi tối hơi sốt. |
| Pharmacien（越语） | Bạn có bị dị ứng thuốc gì không? | Avez-vous des allergies médicamenteuses ? |
| Client（法语） | Je suis allergique à la pénicilline, mais je ne prends aucun autre médicament. | Tôi dị ứng với penicillin, nhưng không dùng thuốc nào khác. |
| Pharmacien（越语） | Dạ, với triệu chứng này bạn có thể dùng viên ngậm và thuốc hạ sốt. | Avec ces symptômes, vous pouvez prendre des pastilles et un antipyrétique. |
| Client（法语） | Le médicament contre la fièvre contient-il du paracétamol ? | Thuốc hạ sốt có chứa paracetamol không? |
| Pharmacien（越语） | Dạ có, mỗi viên là năm trăm mi-li-gam paracetamol. | Oui, chaque comprimé contient cinq cents milligrammes de paracétamol. |
| Client（法语） | J'en prends deux à la fois ? Ah non, quelle est la dose recommandée ? | Tôi uống hai viên một lần à? À không, liều khuyến nghị là bao nhiêu? |
| Pharmacien（越语） | Người lớn uống một viên mỗi lần, cách ít nhất sáu tiếng và không quá bốn viên một ngày. | Un adulte prend un comprimé à la fois, avec au moins six heures d'intervalle, sans dépasser quatre comprimés par jour. |
| Client（法语） | D'accord. Dois-je consulter un médecin si la fièvre continue ? | Được. Nếu còn sốt thì tôi có nên đi khám bác sĩ không? |
| Pharmacien（越语） | Dạ, nếu sốt kéo dài quá hai ngày hoặc khó thở thì bạn nên đi khám ngay. | Oui. Si la fièvre dure plus de deux jours ou si vous avez du mal à respirer, consultez immédiatement. |
| Client（法语） | Je vais prendre aussi des pastilles au miel. | Tôi sẽ lấy thêm viên ngậm vị mật ong. |
| Pharmacien（越语） | Dạ, loại không đường hay loại thường ạ? | Bien sûr. Préférez-vous la version sans sucre ou la version classique ? |
| Client（法语） | La version sans sucre, s'il vous plaît. | Loại không đường nhé. |
| Pharmacien（越语） | Dạ, tổng cộng là một trăm tám mươi nghìn đồng. | Oui, le total est de cent quatre-vingt mille dôngs. |

测试点：
- 检查症状、过敏史、药品成分与剂量。
- 验证“服两片”改问推荐剂量的自我纠正。
- 核对就医警示条件和商品规格。

## 17. 法越｜银行开户咨询

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Client（法语） | Bonjour, je voudrais ouvrir un compte bancaire. | Chào bạn, tôi muốn mở tài khoản ngân hàng. |
| Conseiller（越语） | Dạ, bạn muốn mở tài khoản cá nhân hay tài khoản doanh nghiệp? | Souhaitez-vous ouvrir un compte personnel ou professionnel ? |
| Client（法语） | Un compte personnel, car je viens de m'installer ici. | Một tài khoản cá nhân, vì tôi mới chuyển đến đây. |
| Conseiller（越语） | Bạn cần hộ chiếu, visa còn hiệu lực và giấy tờ chứng minh địa chỉ. | Vous avez besoin de votre passeport, d'un visa valide et d'un justificatif de domicile. |
| Client（法语） | Une facture d'électricité à mon nom suffit-elle ? | Một hóa đơn tiền điện đứng tên tôi có đủ không? |
| Conseiller（越语） | Dạ được, nếu hóa đơn được phát hành trong ba tháng gần đây. | Oui, à condition que la facture date de moins de trois mois. |
| Client（法语） | Y a-t-il un dépôt minimum pour l'ouverture ? | Có số tiền gửi tối thiểu để mở tài khoản không? |
| Conseiller（越语） | Không có tiền gửi tối thiểu, nhưng bạn cần nạp ít nhất năm mươi nghìn đồng để kích hoạt thẻ. | Il n'y a pas de dépôt minimum, mais vous devez verser au moins cinquante mille dôngs pour activer la carte. |
| Client（法语） | Je voudrais aussi une carte physique, pas seulement une carte virtuelle. | Tôi cũng muốn thẻ vật lý, không chỉ thẻ ảo. |
| Conseiller（越语） | Dạ, thẻ vật lý được miễn phí năm đầu và nhận sau bảy đến mười ngày làm việc. | Oui. La carte physique est gratuite la première année et sera disponible sous sept à dix jours ouvrés. |
| Client（法语） | Est-ce que l'application est disponible en anglais ? | Ứng dụng có dùng được bằng tiếng Anh không? |
| Conseiller（越语） | Dạ có tiếng Anh và tiếng Việt. Bạn có muốn đăng ký ngân hàng trực tuyến luôn không? | Oui, elle est disponible en anglais et en vietnamien. Souhaitez-vous vous inscrire dès maintenant à la banque en ligne ? |
| Client（法语） | Oui, mais je n'ai pas encore de numéro vietnamien. | Có, nhưng tôi chưa có số điện thoại Việt Nam. |
| Conseiller（越语） | Vậy bạn có thể đăng ký sau khi có số điện thoại trong nước. | Dans ce cas, vous pourrez vous inscrire après avoir obtenu un numéro vietnamien. |
| Client（法语） | Pardon, je voulais dire que j'ai un numéro vietnamien, mais il est nouveau. | Xin lỗi, ý tôi là tôi có số Việt Nam rồi, nhưng đó là số mới. |
| Conseiller（越语） | Không sao, bạn có thể dùng số đó để đăng ký ngay hôm nay. | Ce n'est pas un problème, vous pouvez utiliser ce numéro pour vous inscrire dès aujourd'hui. |

测试点：
- 核对开户类型、证件、地址证明时效和激活金额。
- 验证电话号码状态的自我更正。
- 检查实体卡、应用语言和网银注册条件。

## 18. 法越｜维修预约

| 发言者 | 原文（源语言） | 参考译文（目标语言） |
|---|---|---|
| Client（法语） | Bonjour, je voudrais prendre rendez-vous pour faire réparer mon climatiseur. | Chào bạn, tôi muốn đặt lịch sửa máy lạnh. |
| Technicien（越语） | Dạ, máy lạnh nhà bạn bị vấn đề gì ạ? | Quel est le problème avec votre climatiseur ? |
| Client（法语） | Il ne refroidit plus et il goutte depuis hier. | Máy không còn làm lạnh và từ hôm qua đã bị nhỏ nước. |
| Technicien（越语） | Máy lạnh của bạn là loại treo tường hay máy âm trần? | Votre climatiseur est-il mural ou encastré au plafond ? |
| Client（法语） | C'est un modèle mural dans le salon. | Đó là loại treo tường trong phòng khách. |
| Technicien（越语） | Dạ, bên em có thể qua kiểm tra vào sáng mai hoặc chiều thứ Năm. | Nous pouvons venir l'inspecter demain matin ou jeudi après-midi. |
| Client（法语） | Demain matin me conviendrait. À quelle heure exactement ? | Sáng mai được. Chính xác là mấy giờ? |
| Technicien（越语） | Khoảng từ chín đến mười một giờ, kỹ thuật viên sẽ gọi trước khi đến. | Entre neuf et onze heures. Le technicien appellera avant son arrivée. |
| Client（法语） | Mon adresse est 28, rue des Fleurs, appartement 6B. | Địa chỉ của tôi là số 28 đường Hoa, căn hộ 6B. |
| Technicien（越语） | Dạ, tòa nhà có chỗ đậu xe cho kỹ thuật viên không ạ? | Y a-t-il un parking pour le technicien dans l'immeuble ? |
| Client（法语） | Oui, mais il faut annoncer le numéro d'appartement à l'entrée. | Có, nhưng phải báo số căn hộ ở cổng vào. |
| Technicien（越语） | Em đã ghi chú rồi. Phí kiểm tra là hai trăm nghìn, chưa bao gồm linh kiện. | Je l'ai noté. Les frais de diagnostic sont de deux cent mille dôngs, hors pièces détachées. |
| Client（法语） | Je pensais que la réparation était comprise… enfin, le diagnostic seulement coûte ce montant ? | Je pensais que la réparation était comprise… enfin, le diagnostic seul coûte ce montant ? |
| Technicien（越语） | Dạ đúng, phí này chỉ cho việc kiểm tra; bên em sẽ báo giá trước khi sửa. | Oui, ces frais couvrent uniquement le diagnostic ; nous vous donnerons un devis avant toute réparation. |
| Client（法语） | Très bien, merci de confirmer le rendez-vous par message. | Được rồi, vui lòng xác nhận lịch hẹn qua tin nhắn. |
| Technicien（越语） | Dạ, em sẽ gửi xác nhận ngay. Hẹn gặp bạn sáng mai. | Bien sûr, je vous envoie la confirmation immédiatement. À demain matin. |

测试点：
- 检查故障现象、设备类型、地址和上门时段。
- 验证维修费与检查费的误解及澄清。
- 核对检查费、配件报价与短信确认。
