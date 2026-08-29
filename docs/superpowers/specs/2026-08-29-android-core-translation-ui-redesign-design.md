# Android Core Translation UI Redesign Design

## Goal

把 Android 的核心翻译体验重构为简洁、留白充足的移动端产品界面：默认进入面对面对话，采用 iOS 参考稿的圆角、层级和对话呈现方式，同时保留 Android 现有翻译、音频路由、Cloud session 与安全行为。

## Scope

本次只重构 Android 核心 UI 与导航：

- 面对面对话（默认入口）；
- 面对面按住翻译与连续对话；
- 同声传译；
- “我”（账户、权益与设置入口）；
- 核心语言选择、状态、错误与空状态。

不在范围内：Cloud API、Agent、JWT/PCM 协议、数据库、账号存储、相机翻译功能、真实 Provider 的翻译质量、Release 发布。

## Reference and Visual Language

权威视觉参考为 `ui/ios/IMG_8282.PNG` 至 `ui/ios/IMG_8286.PNG`。

Android 不机械复制 iOS 控件，而是采用以下一致的视觉语言：

- 背景：`#F6F7FB`；
- 卡片：`#FFFFFF`；
- 主文字：`#171923`；
- 次文字：`#747784`；
- 唯一交互强调色（静谧靛蓝）：`#5B6CFF`；
- 强调浅色：`#EEF0FF`；
- 危险操作：`#C95B63`；
- 白色大圆角容器、极弱阴影、无装饰性渐变；
- 主要操作以图标和短文本表达；不得以 emoji 作为生产图标；
- 字体使用 Android 系统 sans-serif 与 Material 图标，保证中文、越南语、法语显示。

## Information Architecture

底部固定为三个图标加文字入口，顺序不可变：

1. **面对面**：默认首页；图标为两个相对说话的人；
2. **同声传译**：图标为戴耳机的人；
3. **我**：标准用户轮廓图标。

移除当前底栏中的翻译、历史、相机等入口。相机翻译未实现，不应出现在底栏。账户、权益、服务设置、帮助和退出均收进“我”。

## Face-to-Face: Shared Model

面对面以左右耳，而非“你/对方”命名和路由：

- 左耳的源语言为 `leftLanguage`；其译文播放到右耳；
- 右耳的源语言为 `rightLanguage`；其译文播放到左耳；
- 页面顶部显示两个紧凑语言 chip：左为白色，右为靛蓝浅色；chip 显示语言名与切换图标；
- 点击 chip 或切换图标打开可访问的语言选择 sheet/menu；只在协调器允许的空闲状态调用既有 `setLanguages`；
- 会话记录采用微信式左右错位聊天流：左耳消息靠左、右耳消息靠右；
- 一条完成消息显示源语言、原文、细分隔线和靛蓝译文；有可访问的播放按钮；
- 聊天流可滚动，底部永久保留不被操作按钮覆盖的安全区；最新消息必须滚动到安全区上沿；
- 左右话筒固定在聊天流下方、底栏上方的左右角；绝不遮挡最新消息；
- 不显示“输入文字”“左耳语言”“Right channel”等冗余占位文案。

## Face-to-Face: Manual Mode (Default)

默认 `FaceToFaceMode.MANUAL`：

- 空闲时显示两路语言 chip、已有聊天记录以及左右静态话筒；
- 按住左话筒调用既有 `manualPress(..., LEFT, ...)`；按住右话筒调用 `manualPress(..., RIGHT, ...)`；
- 当前收音侧话筒有靛蓝同心波纹；另一侧保持静态；
- 收音中在最新的对应侧聊天位置显示本地化简短状态：中文为“听取中…”，英文为“Listening…”；
- 松开后调用既有 `endManualInput()`；进入处理/播放状态时波纹消失；完成后生成一条左右对应的原文与译文消息；
- 不允许并发手动输入，沿用 `manualInputLocked`。

## Face-to-Face: Continuous Mode

连续模式从右上角 `…` 菜单进入；默认菜单项为“按住翻译”，可选择“连续对话”。菜单同时可放置语言/耳机设置和清除本次对话，危险操作使用危险色。

连续模式必须严格遵循现有 ViewModel 状态机，不改协议语义：

- `startAuto()` 后左耳持续采集；
- 左耳话筒显示收音波纹；
- 右侧话筒是按住式临时插话：`pressRightAuto()` 切换到右耳，`releaseRightAuto()` 自动切回左耳；
- 右侧按住期间，波纹转到右耳；松开后波纹回到左耳；
- 在页面中以简短状态表达“连续对话 · 左耳持续听取”；
- 暂停、继续、结束沿用 `pauseAuto()`、`resumeAuto()`、`stopAuto()`；这些操作不遮挡聊天流；
- 每个 turn 的播放路由不变：左耳说话播放至右耳，右耳说话播放至左耳。

## Simultaneous Interpretation

同声传译沿用现有 `InterpretationViewModel` 状态机与 Cloud session 行为，但减为单一主任务屏幕：

- 顶部显示源/目标语言方向；
- 中央突出当前实时转写与译文；
- 录音时主话筒显示同一套波纹；
- 开始、暂停、继续、结束由现有状态决定，错误使用固定安全错误文案；
- 不展示内部 session id、token、原始异常或网络地址。

## Account

“我”页显示最少信息：登录态/角色、trial 或 entitlement 的安全摘要、翻译记录入口、服务设置、帮助与反馈、退出登录。不得显示 access token、refresh token、session token、DSN、API key、密码或完整服务诊断。

## Component Boundaries

把 `MainActivity.kt` 中的巨大 UI 拆为以下 Compose 文件，业务状态和 ViewModel 不迁移到 UI 文件：

- `ui/design/VerbaDesignTokens.kt`：颜色、圆角、间距和波纹 animation token；
- `ui/navigation/ProductBottomBar.kt`：三个固定入口与 icon/文本；
- `ui/facetoface/FaceToFaceScreen.kt`：编排面对面界面与状态；
- `ui/facetoface/ConversationTimeline.kt`：聊天流、turn bubble、收音占位；
- `ui/facetoface/EarMicControls.kt`：左右固定话筒和波纹；
- `ui/facetoface/FaceToFaceOverflowMenu.kt`：模式/设置/清除菜单；
- `ui/interpretation/InterpretationScreen.kt`：同声传译界面；
- `ui/account/AccountScreen.kt`：账户与权益界面。

保持现有 `FaceToFaceViewModel`、`InterpretationViewModel`、`AccountViewModel`、`FaceToFaceCoordinator`、Cloud coordinator 和协议类型为行为权威来源。只在必要时提取纯 UI 映射辅助函数；不改变其安全边界。

## Accessibility and Responsive Rules

- 所有图标按钮必须有中文 content description；
- 所有触摸目标最小 48dp；
- 语言 chip、左右话筒、播放、暂停、结束和底部导航必须可键盘/无障碍服务操作；
- 靛蓝文字/背景组合满足正文对比度；
- 波纹遵守系统 reduce-motion：关闭动画时显示静态高亮环；
- 面对面横屏继续支持左右工作区，不丢失现有双侧功能；
- 处理状态不允许意外更改语言或模式。

## Test and Acceptance Criteria

1. 默认用户页为面对面，底栏仅有“面对面 / 同声传译 / 我”；
2. 面对面聊天流按 `FaceToFaceTurn.side` 正确左右对齐，原文和译文均可见；
3. 最新聊天消息不被左右麦克风或底栏遮挡；
4. 手动按住任一侧时，仅该侧显示波纹和本地化听取状态；松开后波纹消失；
5. 连续模式中，左耳持续波纹；按住右耳转为右侧波纹；松开恢复左侧波纹；
6. 连续模式继续使用既有 `pressRightAuto()` / `releaseRightAuto()`，且 turn 的另一耳播放路由保持不变；
7. 语言 chip 的选择不会在非空闲状态错误改变 coordinator 语言；
8. 账户页不泄露秘密；
9. 现有 Android unit test、lint、Debug assemble 全部通过；新增纯 UI 映射/状态测试覆盖上述关键规则；
10. 重新构建 Debug APK，但 APK、Gradle/Kotlin 缓存均不进 Git。

## Risks and Non-Goals

该工作不验证真实 Provider 的识别质量、蓝牙耳机路由、设备旋转后的真实音频体验或网络稳定性；这些仍需要受控真机验收。视觉预览由本机临时静态服务提供，不纳入仓库，也不是发布资产。
