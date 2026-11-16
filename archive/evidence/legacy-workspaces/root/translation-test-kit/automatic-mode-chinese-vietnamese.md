> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `translation-test-kit/automatic-mode-chinese-vietnamese.md`；原始 SHA-256 `31132abae322b52caf03c9c14b0ed5869d84f0377269912f63e0c4c483acaf48`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 自动模式测试文案：中越对话

> **模式说明**：不手动选择输入语言。App 应识别每条“原文”的实际语言，并自动翻译为另一种语言。
> **表格规则**：`原文语言` 是实际输入语言；`参考译文` 必须是对应目标语言。越南语采用标准书写、偏南方日常口语。
> 每个场景 8 轮（双方各发言 8 次）。

---

## 场景 1：诊所预约改期（仅翻译模拟）

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 患者 | 中文 | 你好，我想改一下明天下午的预约。我的名字是周雨，不是周宇。 | 越南语 | Chào bạn, tôi muốn đổi lịch hẹn vào chiều mai. Tôi tên là Châu Vũ, không phải Châu Vũ với chữ “vũ trụ”. |
| 接待员 | 越南语 | Dạ, chị cho em xin số điện thoại hoặc mã đặt lịch được không ạ? | 中文 | 好的，您可以给我电话号码或预约编号吗？ |
| 患者 | 中文 | 电话尾号是 6618，预约号好像是 CL-2084。 | 越南语 | Bốn số cuối điện thoại là 6618, mã hẹn hình như là CL-2084. |
| 接待员 | 越南语 | Em tìm thấy rồi. Chị đang hẹn lúc 3 giờ 30 chiều mai với bác sĩ Lan. | 中文 | 我找到了。您预约的是明天下午三点三十分与 Lan 医生见面。 |
| 患者 | 中文 | 对，不过我明天三点还在开会。最早能改到几点？ | 越南语 | Đúng rồi, nhưng 3 giờ chiều mai tôi vẫn họp. Sớm nhất có thể đổi sang mấy giờ? |
| 接待员 | 越南语 | Ngày mai bác sĩ còn trống lúc 5 giờ 15, hoặc sáng thứ Sáu lúc 9 giờ. | 中文 | Lan 医生明天还有下午五点十五分，或者周五上午九点的空档。 |
| 患者 | 中文 | 嗯，改到明天五点十五吧。需要提前多久到？ | 越南语 | Ừm, đổi sang 5 giờ 15 chiều mai nhé. Tôi cần đến trước bao lâu? |
| 接待员 | 越南语 | Chị vui lòng đến trước mười lăm phút và mang theo hộ chiếu nếu lần này là lần đầu chị dùng bảo hiểm. | 中文 | 请您提前十五分钟到达；如果这是您第一次使用保险，请带上护照。 |
| 患者 | 中文 | 我不用保险，直接自费。请问可以刷卡吗？ | 越南语 | Tôi không dùng bảo hiểm, tôi tự thanh toán. Cho tôi hỏi có thể quẹt thẻ không? |
| 接待员 | 越南语 | Dạ được. Bên em nhận thẻ Visa và Mastercard, nhưng không nhận American Express. | 中文 | 可以。我们接受 Visa 和 Mastercard，但不接受 American Express。 |
| 患者 | 中文 | 好的。对了，我只是改时间，不是取消预约，对吗？ | 越南语 | Được. À, tôi chỉ đổi giờ chứ không phải hủy lịch, đúng không? |
| 接待员 | 越南语 | Đúng rồi ạ, lịch của chị vẫn được giữ với bác sĩ Lan vào 5 giờ 15. | 中文 | 对，您的预约仍然保留，改为下午五点十五分见 Lan 医生。 |
| 患者 | 中文 | 如果我迟到十分钟，会不会要重新预约？ | 越南语 | Nếu tôi đến muộn mười phút thì có phải đặt lịch lại không? |
| 接待员 | 越南语 | Nếu chị trễ quá mười lăm phút, bác sĩ có thể phải chuyển sang bệnh nhân khác. | 中文 | 如果您迟到超过十五分钟，医生可能需要先接待其他患者。 |
| 患者 | 中文 | 明白了，我会提前到。谢谢你帮我改。 | 越南语 | Tôi hiểu rồi, tôi sẽ đến sớm. Cảm ơn bạn đã đổi lịch giúp tôi. |
| 接待员 | 越南语 | Dạ không có gì. Em sẽ gửi tin nhắn xác nhận trong ít phút nữa. | 中文 | 不客气。我会在几分钟内发送确认短信。 |

**自动模式检查点**
- 不应把姓名更正、改期与取消预约混为一谈。
- `CL-2084`、`6618`、`3:30 / 5:15 / 9:00`、十五分钟及卡种信息必须准确。
- 仅测试翻译流程，不输出诊断或治疗建议。

---

## 场景 2：市场购买并发现找零错误

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 顾客 | 中文 | 你好，这三种芒果分别多少钱一公斤？我想买不太熟的。 | 越南语 | Chào bạn, ba loại xoài này giá bao nhiêu một ký? Tôi muốn mua loại chưa chín quá. |
| 摊主 | 越南语 | Loại này 45 nghìn, loại kia 60 nghìn, còn xoài cát là 75 nghìn đồng một ký. | 中文 | 这种每公斤 4.5 万，那种 6 万，芒果 cát 每公斤 7.5 万越南盾。 |
| 顾客 | 中文 | 那我要一公斤六万的，再要半公斤四万五的。 | 越南语 | Vậy tôi lấy một ký loại 60 nghìn, và nửa ký loại 45 nghìn. |
| 摊主 | 越南语 | Dạ, tổng cộng là 82 nghìn 500 đồng. Chị có túi riêng không? | 中文 | 好的，总共 82,500 越南盾。您有自己的袋子吗？ |
| 顾客 | 中文 | 有，我自己装。嗯，这些里面能不能挑两个更硬一点的？ | 越南语 | Có, tôi tự đựng. Ừm, trong số này tôi có thể chọn hai quả cứng hơn một chút không? |
| 摊主 | 越南语 | Được chứ. Hai quả này còn xanh hơn, để thêm hai hoặc ba ngày sẽ ngon. | 中文 | 当然可以。这两个更青一些，再放两三天会更好吃。 |
| 顾客 | 中文 | 好的，我给你十万。麻烦数一下找零。 | 越南语 | Được, tôi gửi bạn 100 nghìn. Phiền bạn đếm tiền thối lại nhé. |
| 摊主 | 越南语 | Đây là 17 nghìn 500 đồng tiền thừa của chị. | 中文 | 这是找您的 17,500 越南盾。 |
| 顾客 | 中文 | 不好意思，我算的应该是找一万七千五，嗯，对，金额没问题。 | 越南语 | Xin lỗi, tôi tính là phải thối lại 17 nghìn 500, ừm, đúng rồi, số tiền không có vấn đề. |
| 摊主 | 越南语 | Dạ, đúng rồi ạ. Chị có cần hóa đơn không? | 中文 | 对，是的。您需要收据吗？ |
| 顾客 | 中文 | 不需要。可是刚才那袋小芒果里好像少放了一颗。 | 越南语 | Không cần. Nhưng hình như trong túi xoài nhỏ lúc nãy thiếu một quả. |
| 摊主 | 越南语 | Để em kiểm tra. Đúng là thiếu một quả, em xin lỗi, em thêm ngay cho chị. | 中文 | 我来检查。确实少了一颗，很抱歉，我马上给您补上。 |
| 顾客 | 中文 | 没关系，谢谢。这个芒果放冰箱会不会太早？ | 越南语 | Không sao, cảm ơn bạn. Cho xoài này vào tủ lạnh ngay có sớm quá không? |
| 摊主 | 越南语 | Loại còn xanh thì chị để ngoài đến khi mềm vừa, rồi mới cho vào tủ lạnh. | 中文 | 还青的先放在室温，变得适中柔软后再放冰箱。 |
| 顾客 | 中文 | 明白了，我会先放外面。谢谢你。 | 越南语 | Tôi hiểu rồi, tôi sẽ để bên ngoài trước. Cảm ơn bạn. |
| 摊主 | 越南语 | Dạ, cảm ơn chị. Hẹn gặp lại nhé. | 中文 | 好的，谢谢您。下次再见。 |

**自动模式检查点**
- `45 / 60 / 75 nghìn`、总价 `82,500`、找零 `17,500` 和重量不可错误。
- “不太熟 / 更硬”与储存建议需自然表达，且保留自我确认。
- 缺一颗商品与找零正确是不同问题。

---

## 场景 3：公寓健身房门禁故障

| 发言者 | 原文语言 | 原文 | 目标语言 | 参考译文 |
|---|---|---|---|---|
| 住户 | 中文 | 你好，健身房的门禁好像坏了。我刷卡后门没有开。 | 越南语 | Chào bạn, hình như cửa ra vào phòng gym bị lỗi. Tôi quẹt thẻ nhưng cửa không mở. |
| 管理员 | 越南语 | Dạ, chị đang ở tòa nào và thẻ của chị có mở được thang máy không? | 中文 | 好的，您住在哪一栋？您的卡能打开电梯吗？ |
| 住户 | 中文 | 我住 C 栋，电梯可以用，只有健身房不行。 | 越南语 | Tôi ở tòa C, thang máy vẫn dùng được, chỉ có phòng gym không vào được. |
| 管理员 | 越南语 | Em kiểm tra thấy hệ thống vừa bảo trì. Chị thử giữ thẻ trước đầu đọc khoảng hai giây nhé. | 中文 | 我查到系统刚维护过。请您把卡在读卡器前停留大约两秒试一下。 |
| 住户 | 中文 | 我试了，还是红灯。呃，门上显示“Access denied”。 | 越南语 | Tôi thử rồi, vẫn đèn đỏ. Ừm, trên cửa hiện “Access denied”. |
| 管理员 | 越南语 | Có thể quyền vào phòng gym của thẻ chị chưa được đồng bộ lại. Em sẽ làm mới ngay bây giờ. | 中文 | 可能您卡的健身房权限还没有重新同步。我现在马上刷新。 |
| 住户 | 中文 | 大概要等多久？我预约了七点的私教课。 | 越南语 | Tôi cần chờ bao lâu? Tôi có lớp huấn luyện cá nhân lúc 7 giờ. |
| 管理员 | 越南语 | Khoảng năm phút. Trong lúc chờ, chị có thể vào bằng cửa phụ cạnh hồ bơi. | 中文 | 大约五分钟。等待期间，您可以从泳池旁边的侧门进入。 |
| 住户 | 中文 | 侧门也需要刷卡吗？我怕又进不去。 | 越南语 | Cửa phụ cũng cần quẹt thẻ phải không? Tôi sợ lại không vào được. |
| 管理员 | 越南语 | Không cần, bảo vệ sẽ mở cho chị sau khi chị nói số căn hộ là C-1208. | 中文 | 不需要，您报房号 C-1208 后，保安会为您开门。 |
| 住户 | 中文 | 好。请不要把我的房号告诉其他人。 | 越南语 | Được. Xin đừng nói số căn hộ của tôi cho người khác. |
| 管理员 | 越南语 | Dạ, thông tin của chị chỉ dùng để xác nhận với bảo vệ. | 中文 | 好的，您的信息只用于向保安确认。 |
| 住户 | 中文 | 现在门禁刷新好了吗？ | 越南语 | Bây giờ thẻ vào cửa đã được làm mới chưa? |
| 管理员 | 越南语 | Xong rồi ạ. Chị thử lại sau một phút, vì hệ thống cần thời gian cập nhật. | 中文 | 已经完成。请一分钟后再试，因为系统需要时间更新。 |
| 住户 | 中文 | 明白。如果还是不行，我会从侧门进。谢谢。 | 越南语 | Tôi hiểu. Nếu vẫn không được, tôi sẽ vào bằng cửa phụ. Cảm ơn bạn. |
| 管理员 | 越南语 | Dạ, xin lỗi vì sự bất tiện này. Chúc chị tập tốt nhé. | 中文 | 好的，很抱歉给您带来不便。祝您锻炼愉快。 |

**自动模式检查点**
- “只有健身房不行”与“电梯可用”必须区分。
- `C-1208`、七点、两秒、五分钟、一分钟应准确。
- 隐私要求和侧门临时方案不可遗漏。
