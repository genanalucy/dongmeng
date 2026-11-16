> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/first-use-guidance/spec.md`；原始 SHA-256 `c4ef33662e259908d28070a0bbc43c98f8c4d9345bb3e43b0267a100905725ea`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 首次使用指南规格

Status: ready-for-agent
Labels: ready-for-agent

## Problem Statement

新用户第一次打开言枢智能翻译时，不清楚底部导航、语言选择、按住说话、同传入口、面对面翻译和历史记录的使用方式。当前界面虽然提供了这些功能，但缺少有上下文的首次引导，用户需要自行试错，容易错过核心能力或误解按钮行为。

## Solution

首次打开应用时展示一套可跳过的分步使用指南。指南通过半透明遮罩和聚焦高亮，将用户注意力依次引导到最常用的真实按钮，并用简短说明告知操作方法。引导内容必须与当前页面、当前登录状态和可用功能一致；不模拟点击、不伪造翻译结果、不改变真实业务状态。

指南结束、用户跳过或完成关键步骤后，记录本机已完成状态，后续启动不再自动打扰。用户可以从“关于”或“帮助”重新查看指南。更新应用后不应无条件重复展示，只有引导版本发生变化且产品明确要求重新教学时才允许重新展示。

## User Stories

1. As a new user, I want to understand the app’s primary purpose on first launch, so that I know where to begin.
2. As a new user, I want to see the three primary destinations, so that I understand the difference between 对话、同传 and 我的.
3. As a new user, I want the 对话入口 highlighted, so that I can start a normal translation quickly.
4. As a new user, I want to know that I should choose the source and target languages before speaking, so that translation uses the intended language pair.
5. As a new user, I want the language selectors highlighted, so that I can identify how to change languages.
6. As a new user, I want to know that the microphone control is press-and-hold, so that I do not expect a tap to start an indefinite recording.
7. As a new user, I want to know which side’s microphone I am using, so that I understand where the translated result is sent.
8. As a new user, I want the first microphone control highlighted, so that I can try one complete translation turn.
9. As a new user, I want a concise explanation of the translation result area, so that I know where source text and translated text appear.
10. As a new user, I want the 同传 destination highlighted, so that I can discover continuous interpretation.
11. As a new user, I want to know that 同传 has separate source and target languages, so that I can configure both sides correctly.
12. As a new user, I want to know that active 同传 sessions lock language selection, so that I understand why controls may become unavailable.
13. As a new user, I want the 同传 start control highlighted, so that I can recognize how to begin a live session.
14. As a new user, I want an explanation of pause and end controls, so that I can control a live 同传 session safely.
15. As a new user, I want the 面对面入口 highlighted when it is available, so that I can discover two-sided conversation mode.
16. As a new user, I want to know that 面对面翻译 assigns a language to each side, so that each participant uses the correct microphone.
17. As a new user, I want the 面对面 microphone controls highlighted, so that I understand the left/right interaction model.
18. As a new user, I want to know that only one side should be recording at a time, so that I avoid conflicting input.
19. As a new user, I want the 我的 destination highlighted, so that I can find account, entitlement, history and settings.
20. As a new user, I want the history entry highlighted, so that I know completed translation sessions can be reviewed later.
21. As a new user, I want the account entry explained, so that I know where to sign in and manage entitlement.
22. As a signed-out user, I want the guide to explain sign-in without requiring sign-in, so that I can learn the product before committing to an account.
23. As a signed-in user, I want the guide to avoid showing irrelevant sign-in instructions, so that the experience reflects my current state.
24. As a user with unavailable network access, I want the guide to remain usable offline, so that educational content does not depend on a cloud request.
25. As a user, I want the guide to preserve the existing three-tab navigation, so that learning does not require adopting a new navigation model.
26. As a user, I want the highlighted target to be a real visible control, so that the instruction matches what I can actually press.
27. As a user, I want the rest of the interface dimmed but still recognizable, so that I retain context while focusing on the current step.
28. As a user, I want one clear next action per step, so that I am not overwhelmed by choices.
29. As a user, I want to skip the guide at any time, so that I can start using the app immediately.
30. As a user, I want the guide to finish automatically after the final step, so that I do not need to dismiss an unnecessary final screen.
31. As a user, I want a visible progress indicator, so that I know how many steps remain.
32. As a user, I want to navigate back to a previous step, so that I can reread an instruction.
33. As a user, I want the guide not to trigger microphone permission prompts automatically, so that I remain in control of sensitive permissions.
34. As a user, I want the guide not to start recording, translation, playback or a live session automatically, so that onboarding cannot cause unexpected device activity.
35. As a user, I want the guide not to create fake history records, so that my history remains truthful.
36. As a user, I want the guide not to alter selected languages or account state, so that onboarding does not change my preferences unexpectedly.
37. As a screen-reader user, I want each highlighted control to have a meaningful accessibility label, so that the guide remains understandable without visual highlighting.
38. As a screen-reader user, I want the current step and progress announced politely, so that I know what changed without losing my place.
39. As a user with large text or a narrow screen, I want instruction cards to remain readable and actionable, so that onboarding works across supported devices.
40. As a user in light or dark theme, I want the guide overlay and highlight contrast to remain clear, so that the target is visible in either theme.
41. As a user, I want the guide to respect system bars and safe areas, so that highlighted controls are not obscured.
42. As a user, I want the guide state saved locally, so that closing the app does not unexpectedly restart it.
43. As a user, I want a completed guide not to reappear on every launch, so that normal app use remains uninterrupted.
44. As a user, I want a skipped guide not to reappear on every launch, so that skipping has the expected effect.
45. As a user, I want to reopen the guide from a stable help/about entry, so that I can learn a missed feature later.
46. As a user, I want reopening the guide to be intentional rather than automatic, so that it does not interrupt an active translation.
47. As a user, I want the guide to defer or refuse reopening while recording or playing audio, so that onboarding cannot interfere with a live turn.
48. As a user, I want the guide to show only capabilities available in the current build, so that no step points to a disabled or absent control.
49. As a test-version user, I want new onboarding experiments isolated from the formal release experience until validated, so that production behavior remains stable.
50. As a product maintainer, I want onboarding content versioned independently from app version numbers, so that content can be revised without replaying it unnecessarily.
51. As a product maintainer, I want onboarding analytics to avoid collecting spoken text, translated text, account identifiers or precise personal data, so that learning usage does not compromise privacy.
52. As a product maintainer, I want every onboarding step to map to an explicit UI target, so that automated tests can verify the complete sequence.
53. As a product maintainer, I want missing targets to fail safely by skipping that step, so that a layout change cannot block app startup.
54. As a product maintainer, I want a single onboarding coordinator to own sequencing and persistence, so that screen components do not each implement conflicting first-use rules.
55. As a product maintainer, I want the guide to use existing design tokens and accessibility conventions, so that it looks and behaves like the rest of the app.

## Implementation Decisions

- 新增客户端首次使用指南协调器，负责引导版本、步骤序列、当前步骤、跳过/完成状态和重新打开动作；页面组件只提供目标控件与必要的语义锚点。
- 引导状态持久化在本机偏好存储中，不修改 Room schema，不写入历史数据库，不依赖账户身份，也不上传云端。
- 默认行为是首次启动显示一次；完成或跳过后不再自动显示。引导内容采用独立的 `onboardingVersion`，版本变更不会默认导致所有用户重复弹出，是否重放由明确的产品策略控制。
- 首次启动的“显示”必须等待应用主界面完成布局后进行，以便高亮真实控件；目标不存在时安全跳过该步骤，不阻塞启动。
- 步骤使用语义目标而非固定屏幕坐标定位。必要时通过 `Modifier` 语义标识或目标注册表提供高亮边界；禁止依赖设备分辨率硬编码坐标。
- 首次指南优先覆盖：主导航、对话语言选择、按住说话麦克风、同传入口/控制、面对面入口/左右麦克风、我的与历史入口。若当前页面或构建不提供某能力，跳过对应步骤。
- 每个步骤包含：短标题、操作说明、目标语义、目标动作提示、步骤序号/总数和可访问性朗读文本。文案使用 zh-CN，保留项目中的产品术语“对话”“同传”“面对面翻译”“历史”。
- 交互包含“下一步”“上一步”“跳过”；最终步骤使用“完成”。点击遮罩外区域不得误触底层业务控件；强制更新、权限弹窗、系统安装器等外部流程不在指南中触发。
- 高亮层使用现有主题色和设计 token，兼容浅色、深色、跟随系统主题；遮罩、卡片和焦点环须满足项目既有对比度与触控区域规范。
- 指南只教育真实操作，不自动请求麦克风权限、不自动开始录音、不自动建立翻译会话、不自动播放、不创建历史记录、不改变语言或账户选择。
- 自动显示范围由产品开关控制：测试版先启用；正式版是否启用由验证结果决定。重新查看入口可在“关于”或“帮助”中提供，但不得恢复为每次启动弹出。
- 若用户正在录音、播放、同传或面对面活动会话中，自动指南不得出现；主动重新查看也应延后到空闲状态或明确提示用户先结束当前操作。
- 不新增云端 API，不修改 OTA 合约，不修改 Room schema、历史加密格式、账户权限或底部导航结构。

## Testing Decisions

- 测试应验证用户可观察行为，而非内部实现：首次启动是否出现、步骤顺序与文案、目标是否高亮、跳过/完成后的持久化、重新查看入口、活动会话保护和不同主题/屏幕尺寸下的可用性。
- 最高测试 seam 是首次使用指南协调器与 Compose 页面目标注册之间的 UI 状态 seam：使用假的本机偏好存储和可控目标集合，验证状态序列不依赖真实时间、网络、账户或 Room。
- 增加 ViewModel/协调器单元测试，覆盖首次状态、逐步前进、后退、跳过、完成、重启后不重复、引导版本策略、缺失目标安全跳过、活动会话拒绝展示。
- 增加 Compose UI 测试，覆盖真实产品壳中的底部导航、语言选择、麦克风、同传、面对面、我的和历史入口的语义标签；断言当前步骤的说明和目标可访问性语义存在。
- 增加无障碍测试，验证步骤标题、进度、操作说明和当前目标可以被语义树读取；装饰性遮罩不产生重复或误导性朗读。
- 增加 light/dark/system 三种主题测试，并验证窄屏、大字体和旋转/重建后不会裁切主要说明或遮挡目标。
- 增加行为安全测试，确保引导不会调用录音、翻译会话、播放、历史保存、权限请求或云端接口。
- 增加正式版构建测试，确保正式版按产品开关不显示测试专属引导实验控件；测试版可启用自动首次指南。
- 参考现有测试惯例：沿用 `HistoryViewModelTest` 的可控 dispatcher/状态断言模式、账户/导航 policy 测试的纯行为断言，以及现有 Compose AndroidTest 对内容描述和真实触控目标的验证方式。
- 真机验收应覆盖首次安装、完成引导、跳过引导、清除应用数据后重现、正式/测试构建差异、已登录/未登录、无网络、浅色/深色主题和活动会话期间不打扰。

## Out of Scope

- 不重新设计主界面、底部导航或业务流程。
- 不新增或修改账号系统、权益系统、历史云同步、Room schema、云端 API 或 OTA 下载协议。
- 不自动替用户完成翻译、录音、播放、权限授权、登录或兑换权益。
- 不实现产品埋点、用户画像、远程配置后台或在线教程内容管理；如未来需要，应另立变更。
- 不保证对所有系统设置、第三方输入法、无障碍服务或厂商悬浮窗行为提供专门适配。
- 不在本规格中决定正式版最终是否长期启用全部实验步骤，只规定测试版先行验证和安全开关。

## Further Notes

- 引导文案应保持短句、动作导向，避免一次展示多个概念；复杂功能（同传和面对面）可拆成多个步骤。
- 高亮是教学辅助，不是业务状态。用户可以跳过任何步骤，跳过不应改变功能可用性。
- 目标注册失败时宁可跳过该步骤，也不能显示错误位置或阻塞首页。
- 重新查看入口应使用“使用指南”或“查看首次使用指南”等清晰名称，并在系统无障碍语义中说明当前动作。
- 本规格发布为 `ready-for-agent`，后续实现应先核对现有 Compose 页面边界与语义标签，再逐步接入，不使用固定坐标堆叠实现。
