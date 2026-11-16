> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/conversation-export-reliable-save/spec.md`；原始 SHA-256 `e6a41fb54bed63b070da0feba1645bfcaf9d1325132ebc13d43f73d00b90058e`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 当前翻译会话导出与可靠保存

Status: ready-for-agent
Type: feature
Baseline: Debug v29 / 20261116.2307，源码 9717887
Scope: Android Debug；对话、面对面、同传

## Problem Statement

用户希望主动保存当前翻译内容，并导出可阅读、可再次处理的文件。目前用户感觉翻译不会自动保存，无法确认屏幕上的内容是否已经成为可重新打开的本机历史副本，也无法直接将当前翻译内容保存为文件。

已检查的实现具备加密历史库、保存控制器和云端同步队列，但存在如下证据与限制：

- 对话/面对面主要在完成事件触发归档；同传通常等整个 transport 的 Finished 才归档。屏幕上出现完整文字，不一定意味着已经落盘。
- 保存中与成功反馈当前默认不展示，仅失败时提示。
- 保存去重登记在尝试写入前发生，缺少完整失败重试流程；实现重试时必须避免既不再提交、又重复插入的两种问题。
- 两个工作台的保存任务使用 ViewModel 的协程生命周期，页面对应 ViewModel 销毁可能取消尚未完成的写入；切页也可能重置反馈与 ownership。
- 历史页已有文本分享，但不等于当前工作台提供 TXT/JSON 文件导出。
- 现有保存控制器定向单测通过，只证明已覆盖的行为；没有读取用户实际翻译内容、数据库或设备日志，不能认定用户手机已经丢数据，也不能认定只缺成功提示。

必须同时解决保存可靠性与可观察性，不能只加导出按钮，不能为了尽早归档而将未完成、失败、取消的内容冒充已完成 Turn。

## Solution

在对话、面对面、同传工作台提供统一的“保存到历史”“导出 TXT”“导出 JSON”操作，优先放入现有更多菜单。

已完成 Turn 自动、增量保存到当前账号的本机历史副本，不要求用户等整场翻译结束。用户可以主动保存当前已完成内容或重试失败项；界面简洁显示保存中、已保存到本机、保存失败及重试。没有完成内容时说明原因，不创建空翻译会话。

导出使用 Android 系统文件选择器，由用户选择文件名和位置。导出的是点击时当前工作台显示内容的快照，不是全部历史或私有数据库；支持 UTF-8 TXT 和 JSON。允许导出仍在变化的文字，但必须明确标注未完成，且这些内容不会因此进入翻译历史。导出与保存历史是独立结果：文件成功不代表历史写入成功，本机保存成功也不代表云端已同步。

不清理已有数据、不切换模型、不恢复空白气泡或短句松手丢失问题，不改变既有退出登录隔离与删除优先原则。

## User Stories

1. As an authenticated user, I want completed bilingual content to be saved automatically, so that I do not need to remember a manual save action.
2. As a conversation user, I want a short utterance to remain eligible for saving when subtitles arrive after release, so that speaking quickly does not lose my result.
3. As a face-to-face user, I want both speaking directions preserved in their original order, so that I can understand the exchange later.
4. As a continuous-translation user, I want successive completed utterances grouped into the same translation session, so that automatic reconnection does not fragment my history.
5. As a simultaneous-interpretation user, I want completed bilingual content saved during a long-running session, so that I do not have to stop interpretation to retain earlier results.
6. As a user, I want incomplete or unmatched text excluded from translation history, so that history does not claim unfinished translations are complete.
7. As a user, I want silent input excluded from saved sessions and exported entries, so that blank content does not accumulate.
8. As a user, I want identical words spoken twice preserved as two utterances, so that deduplication does not erase real repetitions.
9. As a user, I want repeated completion callbacks and repeated save clicks to avoid duplicate entries, so that history accurately reflects what happened.
10. As a user, I want a Save to History action for current completed content, so that I can explicitly request retention and see its outcome.
11. As a user, I want a saving indicator, so that I know a write has not yet completed.
12. As a user, I want a Saved on This Device indicator only after a successful local transaction, so that I can trust its meaning.
13. As a user, I want a persistent, non-blocking failure notice with Retry, so that a failed write is visible and recoverable.
14. As a user, I want successful entries preserved while failed entries are retried, so that retry does not duplicate or erase earlier results.
15. As a user, I want saved content available after leaving and reopening a workbench, so that navigation does not discard completed results.
16. As a user, I want already-accepted completed writes to survive the workbench ViewModel being cleared, so that ordinary navigation does not silently cancel saving.
17. As a user, I want pause, resume and playback to avoid blocking on history storage, so that saving does not disrupt translation audio.
18. As a user, I want completed content retained when a later utterance fails, so that one translation error does not erase earlier success.
19. As an offline user, I want local saving and export to work without cloud connectivity, so that I can retain content when synchronization is unavailable.
20. As a user, I want local save status distinguished from cloud synchronization status, so that I do not mistake one for the other.
21. As a user, I want to export the current translation session as TXT, so that I can read and send it using ordinary applications.
22. As a user, I want to export the same snapshot as JSON, so that structured content can be processed later.
23. As a user, I want export to be available in conversation, face-to-face and simultaneous-interpretation workbenches, so that retention works consistently across modes.
24. As a user, I want to select the destination using the system file picker, so that I control where private translation content is stored.
25. As a user, I want to export while translation is still running, so that I can retain current content without deliberately ending the session.
26. As a user, I want a fixed export snapshot taken before opening the picker, so that later subtitles cannot unexpectedly change the selected file's contents.
27. As a user, I want unfinished exported text clearly labeled, so that a recipient cannot confuse it with a completed translation.
28. As a user, I want language direction, speaking side when known, and text order preserved, so that the exported exchange remains understandable.
29. As a user, I want exports to omit invented utterance timestamps, so that exported metadata does not overstate what the app knows.
30. As a user, I want cancelling the picker to leave translation and history unchanged, so that cancellation is harmless.
31. As a user, I want a clear export success or failure result, so that I know whether the destination actually received the file.
32. As a user, I want file permission failures and insufficient storage handled without losing current content, so that I can choose another destination or retry.
33. As an account holder, I want saved content and pending writes isolated by account, so that another login cannot see or receive my translations.
34. As an account holder, I want deleted history to remain deleted despite delayed retries, so that background work cannot resurrect it.
35. As a privacy-conscious user, I want an explicit warning that exported files are plaintext, so that I can choose a trusted destination.
36. As a user with accessibility needs, I want readable save feedback and accessible export controls without repeated per-sentence interruptions, so that retention features remain usable during translation.
37. As a user approaching the history quota, I want a truthful quota failure and access to existing history management, so that I can free space without losing track of pending content.
38. As a user upgrading the Debug app, I want existing history, signing identity and account state retained, so that the new feature does not require a destructive reinstall.

## Implementation Decisions

### Scope and vocabulary

- 对话视图与面对面视图是同一双人翻译能力的两种呈现，复用同一导出和保存规则；同传复用保留服务与文件导出能力，不复制三套业务规则。
- “翻译会话”归档一组已完成 Turn；“已完成 Turn”必须有可确认归属的完整原文与译文。导出中的未完成快照条目不属于翻译历史。
- “当前”指当前工作台在点击时持有、对用户可见的内容范围，不因开启新的上游连接而截断，也不悄悄混入全部历史。新的一场工作台翻译应有明确的新会话边界，模式切换与暂停恢复不能错误合并不同账号内容。

### Incremental completion and identity

- 保留现有工作台 ViewModel、翻译状态机、保存控制器、历史仓储的职责边界。把保存触发从仅等待 transport Finished，提升到可确认的已完成双语单元边界；与音频播放是否完成解耦。
- 接收顺序可能是先原文或先译文，且一个 transport 可以包含多个完成单元。必须依赖现有明确的单元归属或已验证完成边界；不能仅凭两个 final 数组相同下标、文本相似或标点来猜配对。
- 当前上游/Agent事件归属能力不足时，先通过离线回放确定可证明的最小完成边界；若必须扩展事件元数据，应采用向后兼容的可选字段并补契约测试，不切换供应商、不擅自更改现有会话治理。不得把整个长会话的每次累计文本都保存成一条新 Turn。
- 每个持久化完成单元必须有稳定、账号/翻译会话内唯一的幂等标识。重复事件、自动保存、主动保存及失败重试使用同一身份，不能按内容去重；相同话语的两次真实发言仍为两条。
- 本机事务成功后才能标记已保存。保存控制器应区分待处理、写入中、成功、可重试失败，重试只提交未成功单元。一次失败不能永久占用去重键，也不能因不确定写入结果而产生重复。
- 保存请求在入队时冻结正文、语言、账号与翻译会话归属，后续UI状态或新会话不能修改这些属性。

### Durability, lifecycle and privacy

- 复用加密历史库、原有配额和同步 outbox；不以外部明文导出文件替代本机历史存储。
- 已接纳的保存工作不能仅依赖页面/ViewModel存活；可靠交接到受控的持久化生命周期，避免用无界全局协程掩盖任务丢失。页面离开只结束实时收音/翻译，不取消已接纳的已完成 Turn 保存。
- 进程被系统回收后，已经提交的本机历史副本必须可读；已经持久接纳的待办必须可恢复。没有完成持久化接纳的瞬时内容不承诺在断电/强杀时必然恢复，界面不得提前显示成功。
- 用户退出登录、账号切换和删除优先于迟到写入/重试。遵守现有退出登录清本机副本策略；不能让后台重试在清除后重新插入旧账号正文，不能让旧账号 outbox 用新账号凭据上传。
- 删除翻译会话后，旧待办不得复活该会话。不能通过新建翻译会话ID绕过用户删除。
- 云端历史副本继续遵守既有应用层加密与管理员审计决策；导出不改变云端访问边界。没有充分证据不得宣布云端同步成功。
- 如稳定幂等键或持久待办需要数据库字段/索引，实施必须提供非破坏迁移和升级测试；不预设具体表结构，不使用破坏性重建，不将迁移或后端契约变更隐含在UI改动中。

### Save interaction and feedback

- 三种工作台更多菜单均提供“保存到历史”“导出 TXT”“导出 JSON”。保存到历史是自动保存流程的显式检查/提交/重试入口，不创建第二份历史；没有已完成内容时给出准确说明。
- 正常翻译期间使用紧凑非阻塞状态：保存中、已保存到本机、部分内容保存失败。失败支持重试，必要时进入历史管理处理配额。
- 成功只覆盖当前翻译会话中已经完成的单元；若仍有更新未提交或失败，不能用一次早期成功覆盖整体状态。
- 新会话不显示旧会话的保存结果；离开页面后重新进入历史可以核验数据。保存状态与云同步状态独立，且不依赖后台音频连接。
- 无正文、凭据或原始异常进入日志/错误提示。无障碍状态播报避免逐句重复打断阅读，触控目标及系统字体缩放沿用项目规范。

### Export snapshot and file contract

- 导出操作先取得不可变快照，再打开系统文件选择器。文件选择返回时导出同一快照；不从届时正在变化的工作台重新取内容。
- 使用 Android Storage Access Framework 创建文档，TXT为text/plain、JSON为application/json；UTF-8编码。由用户选择目标，不申请整个存储目录访问权限、不默认公开上传。系统可能显示用户自己配置的云盘，App须如实说明这仍由用户选择。
- 两种格式使用同一快照模型，保留条目顺序、原文/译文、源/目标语言、可确认的发言侧及完成状态。部分文字可导出但标明未完成；全空白条目排除。只导出文本，不包含录音、密钥、JWT、数据库内容或与导出无关的内部标识。
- TXT为可读双语排列；JSON包含schema_version、导出时间、工作台类型及有序条目。条目有明确的完成状态；不存在的逐句时间为缺省或null，不以导出时间冒充完成时间。不使用音频分片号伪造发言身份。
- 建议文件名包含品牌、模式及导出时间，遵守目标系统文件名约束；导出时间标注时区。JSON能正确转义引号、换行、多语种及emoji；两格式不得丢字符或全局去重。
- 当前内容为空时不生成没有条目的文件，并给出说明。导出不依赖本机保存/云同步成功；导出完成不自动把部分文字写入历史。
- 用户取消选择不视为失败、不改变翻译或历史。只有输出流成功写入并关闭后才报成功；打开/写入/关闭失败有独立提示且保留可重试状态，不误报已保存。系统目标产生的部分文件需尽力安全清理，不承诺对所有文档提供者都具备原子替换能力。
- 文件选择可能让App进入后台：沿用现有安全停止收音策略，不因返回选择器自动恢复麦克风；必须保证这段生命周期不会丢已完成 Turn 或导出快照。选择期间发生账号切换或退出登录，应撤销尚未交付的旧账号导出，不让新账号界面完成旧账号文件写入；已经由用户保存的外部文件不由退出登录自动删除。
- 导出可能包含明文私人内容，应在首次导出或导出操作面板清楚提示；不反复阻断每次翻译，不自动复制到剪贴板或自动发送到第三方。

## Testing Decisions

### Primary seam and test quality

- 主要测试边界为“工作台用户动作/上游事件 → 可见保存状态、经历史读取接口获得的翻译会话、交给系统文档目标的文件内容”。优先复用现有面对面ViewModel注入runtime/保存器、历史仓储观察接口与Compose工作台测试，不测试私有函数和内部计数来替代实际结果。
- 同传当前耦合真实设备/Socket，应只在最高必要位置引入可注入的外部音频/翻译边界，以便复用同一套行为场景。不要为每个按钮、回调新建抽象层。
- 将系统文件选择/输出流视为外部边界，使用受控文档目标模拟成功、取消、拒绝权限及写入/关闭失败。保持业务导出格式与Android文档API解耦。
- 上述边界是根据当前已讨论方案选择的规格测试决策，并非另一次已获用户明确确认的设计评审；本次遵循to-spec要求不进行需求采访，实施开始前可在计划中复述这些边界。

### Required behavior scenarios

1. 对话、面对面、同传三个工作台均可执行TXT/JSON导出和保存到历史；空内容有说明，无新增空白气泡。
2. final到达顺序颠倒、多次partial、多次final、一个transport多段、重复事件以及真正重复发言，结果不丢、不猜配对、不重复落库。
3. 在未收到transport Finished前出现可确认的已完成 Turn，历史读取接口已能读到一次结果；仍未完成的文字不进入历史。
4. 手动短句气泡前松手→字幕迟到→完整结果保存；静音→正常结束→不产生历史；pointer cancel只丢未完成内容，不撤销已经接纳的完整结果。
5. 连续模式多句、暂停恢复、右侧接话及同传长会话，翻译会话归属稳定且已完成单元逐步可读，不依赖TTS播放结束。
6. 本机写入中离开页面、ViewModel清理、普通后台切换、重新读历史，保存工作不被页面取消；持久待办重启恢复有实际数据库测试。
7. 强制恢复翻译或后续异常发生前已有的已完成 Turn保留；未完成、失败正文不能误归档。
8. 混合成功/失败/写入中，状态不谎报整体成功；用户重试失败项并多次点击后，仓储仍只有一个对应完成单元。覆盖事务成功而调度/回调失败的不确定窗口。
9. 账号A写入/导出未结束时退出或切到账号B；旧任务不污染B状态、不重新创建A本机副本、不用B凭据上传A数据。
10. 用户删除翻译会话后旧写入或同步重试完成，不复活；配额满时如实反馈并保留可处理状态，不破坏已有历史。
11. 点击导出后文字继续更新，实际TXT/JSON均只包含冻结快照；未完成状态明确、顺序一致、内容重复不丢。
12. 中/英/越、多语种长文本、换行、引号、emoji、单边部分文字、未知逐句时间，输出可解析且与快照一致，不包含凭据或无关用户信息。
13. 文档选择取消、没有可用文档处理器、目标失效、写入/关闭失败、空间不足、重复点击和选择期间切账号，反馈准确且翻译内容不丢失。保存失败不阻止导出，导出失败不改历史。
14. 文件选择触发生命周期暂停时不恢复录音；快照能在受支持的旋转/界面重建路径保留，若系统不可恢复则明确取消而不写错会话。
15. 离线本机保存和导出成功时不报云端已同步；云端网络失败仍保留待同步条目。真实设备跨设备同步验收与本机单测结论分开。

### Prior art and validation gates

- 复用LocalHistorySaveController的归属、并行结果、完成后迟到写入测试；扩展失败可重试、幂等持久化及页面销毁用例。
- 复用LocalHistoryRepository的Room集成测试，验证实际写入、读取、升级迁移、账号隔离、配额和删除优先，不只断言save调用次数。
- 复用FaceToFaceViewModel的可控executor和外部runtime测试；同传补等价高层行为覆盖，不假定面对面通过即可代表同传。
- 复用保存反馈策略/Compose测试、历史页读取测试及已有云同步契约测试。更新现有“成功应静默”的断言以匹配本规格，不直接删除失败的旧测试。
- 先用精确失败回归锁定新行为，再改实现；运行合理范围单测、lint、Debug构建及云端设备回归。必要的Room迁移和文件选择器行为必须有相应设备测试。
- 验收必须单列未覆盖项。模拟器UI测试不能冒充真实音频或持久存储验收；任何录音/收费模型调用或读取用户实际历史须另获授权。

## Out of Scope

- PDF、Word、音频导出、文件导入、自动发送邮件、自动分享至第三方、全量历史批量文件导出。
- 将未完成快照文字自动归档为已完成 Turn；通过文本相似度/标点推断双语配对、说话人或时间。
- 双向语言自动检测、双麦/耳机SDK、模型切换、改变Qwen计费路由、连续模式重构和整体UI回滚。
- 替用户选择外部云盘、自动公开下载私人文件、上传私有数据库或对话至GitHub。
- 承诺修复已经丢失的历史，或从现有屏幕文本重建不存在的时间戳、旧账号归属和正文。
- 替换加密方案、重做账号或历史云API、取消历史配额、放松删除优先及管理员审计规则。
- 自动发布正式APK；未经单独授权执行生产迁移或新增后端契约部署。本规格阶段不实施功能、不部署、不发新版。

## Further Notes

- 用户已明确选择TXT + JSON、覆盖对话/面对面/同传。本文件是to-spec产物，不将此前方案询问或本次规格撰写视为已经实现、验收或正式版发布授权。
- “不会自动保存”仍是待复现的用户症状；当前静态证据包括完成触发偏晚、状态静默、失败重试缺口、页面作用域取消风险，不把其中任何一项无证据地宣布为唯一根因。
- 以当前Debug v29为基线，必须保留短句松手、连续续接、录音代次保护以及静音无空白气泡的回归。
- 后续Debug发布沿用现有包名/证书、不可变APK及单清单发布工具，同步官网与对应OTA，正式通道维持原包。版本号在真实发布时分配，不在规格中预占。
- 本地工作项按仓库约定存于.scratch，不提交Git或上传GitHub。当前文档未包含用户正文、密钥、私有数据库或原始设备日志。
