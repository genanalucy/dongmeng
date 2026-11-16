> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/dual-ear-visual-rework/visual-spec.md`；原始 SHA-256 `891027b76fec35139ade350b863373540f874ffc87c824c5d66588d403d4d3e4`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# Astra · 双耳 Compose 视觉重做规格

状态：可实施的视觉规格；不是已通过验收的 APK。仅覆盖表现层，不新增功能、不修改状态机。
基线：`[旧工作区路径]`，已核对 HEAD `3e8dd5e`。
交付约束：本文件位于 `.scratch`，不提交；不改 Android 生产代码。

## 1. 依据、优先级与已发现偏差

权威参考：

1. 用户认可的 `[旧工作区路径]` 及同目录 `README.md`，已完整读取。继承其午夜蓝黑画布、居中顶栏、白色原文/琥珀译文同气泡、左右错位时间线、两个完整圆形 mic、紧邻语言入口及浮动胶囊导航。
2. 用户失败截图：`[旧临时路径]`（实际 512×1040）、`[旧临时路径]`（实际 516×1030）。不是严格的 512×1024 原始 framebuffer；有截图外沿，不能直接反推设备 density。
3. 指定 iOS 图 `[旧临时路径]` 当前不存在，未能读取。README 中另一张 iOS 参考路径也不存在。本文不声称逐像素核对过该图；后续需要恢复原图再做内容层级复核。当前尺寸以已认可 HTML 的 CSS 为依据，Android 差异另列。

当前 Compose 核对：

| 文件（均相对 `android/app/src/main/java/com/verba/interpretation/`） | 当前问题 | 视觉重做范围 |
|---|---|---|
| `ui/facetoface/FaceToFaceScreen.kt` | 左对齐大标题+常驻说明，蓝色视图切换；控制区另铺色块；face 的两个阅读区各带一颗 mic 并一起旋转 | 居中单行顶栏；统一底色；face 等分阅读区，mic 脱离旋转层 |
| `ui/facetoface/EarMicControls.kt` | 86dp mic 内三层文字/箭头，连续模式额外整行按钮 | 82dp 双圆、短耳侧/语言标签；已有开始/暂停归左圆，结束仍由已有菜单提供 |
| `ui/facetoface/ConversationTimeline.kt` | `Alignment.Bottom` 让短历史沉底；360dp 上限可能几乎占满屏；原文16sp、译文继承 bodyMedium；占位说明、路由文字；无外侧播放槽 | 顶部正常流，稳定宽度和外侧槽，19/22sp，同 article live，删除说明 |
| `ui/navigation/ProductBottomBar.kt` | `VerbaColors.Background` 浅底；默认 Material 指示器与字号 | 显式暗色浮动胶囊；固定高度与内容区 |
| `ui/interpretation/InterpretationScreen.kt` | 浅色空卡、语言灰紫 chip、译文先于原文、整块蓝灰操作区、重复状态 | 同暗色 shell；原文上/译文下；无空卡；紧凑固定操作行 |
| `brand/BrandTheme.kt`、`ui/design/VerbaDesignTokens.kt` | `lightColorScheme`，浅色常量；未定义样式继承 Material 默认 | 对上述翻译目的地及共用导航提供同一显式暗色 token scope；不顺带重做其他业务页 |

## 2. 坐标系：先 inset，再应用布局

### 2.1 单位规则

- 实现尺寸用 dp，字体用 sp。HTML 375px phone 的设计长度作为 dp 设计起点，不把整个 HTML 手机含状态栏按高度缩放到 Android。
- 下列像素列是 **512×1024 归一化验收画布**，假设逻辑宽度 `Wdp=393`、fontScale=1，换算 `k=512/393=1.3028 px/dp`。这是规格测量基准，不是已测得的 Xiaomi density。
- 实机验收记录 framebuffer 宽高、density、fontScale、系统导航模式、top/bottom inset。实际截图换算必须用 `k=截图内容宽/Wdp`；外沿裁掉后等比缩放，不拉伸到 512×1024。不得为了凑像素把真实系统 inset 写死。
- 归一化示例采用 `T=36px`、`B=24px`（仅示例）。画布实际安全区域是 `[T, H-B]`。下面所有竖坐标随实测 T/B 平移，非要求 Android 状态栏必须36px高。

### 2.2 Shell 尺寸表（conversation / face 共用）

| 区域 | dp | 512px 基准约值 | 定位 |
|---|---:|---:|---|
| 顶栏高度，不含 system top inset | 66 | 86 | `y=T…T+66k`，示例36…122 |
| 顶栏左右边距 | 16 | 21 | 对称，不跟随标题长度变化 |
| 左视图入口 | 64×44 可见框，最小48dp高触控区 | 83×57，可触高63 | 框中心 y=`T+33k` |
| 右更多入口 | 44×44 可见框，48×48触控区 | 57×57，可触63×63 | 右距16dp |
| 内容区左右内边距 | 14（face 15） | 18（face 20） | 全部 timeline 共用 |
| 操作区高度 | 148 | 193 | `H-B-(81+148)k` 起，示例702…895 |
| nav 与操作区间隔 | 9 | 12 | 不插说明/footer |
| nav 胶囊本体 | 60高，左右margin30 | 78高，x39…473 | 示例y906…984 |
| nav 到系统底部安全区间距 | 12 | 16 | 本体下缘=`H-B-12k` |
| nav 占用总高度，不含系统 inset | 81=9+60+12 | 106 | 固定，不受页面内容影响 |
| 系统底部安全区 | 实际 navigationBars / safeDrawing bottom | 示例24 | 与画布同色，系统自行画手势指示条 |

整体结构：`暗色全屏底 → 系统安全区 → Topbar → weight(1) 阅读区 → Operation → Nav → 系统安全区`。

- 背景延伸到 Android 状态栏和导航栏后方，图标使用适合深底的浅色系统样式；不画假的 9:41、信号、电池、iOS Home indicator 或圆角手机边框。
- 最外层统一负责 inset；Scaffold innerPadding 应用一次并正确消费。禁止 root safeDrawingPadding + 子页面 statusBarsPadding + NavigationBar 默认 inset 重复叠加。
- 三键导航下以真实底部 inset 替换示例 B，绝不把 nav 压到系统按钮上。若系统强制 scrim，以系统区域单独标注，不扩散到应用底栏。
- 所有 pinned 区域不随 timeline 滚动；减少可用高度时首先缩小阅读区，不裁 mic/nav。字体135%时允许顶栏/操作区按内容增高；不得锁死文本框导致裁字。

## 3. 颜色和字体（禁止只换页面背景）

### 3.1 统一色板

| Token | Hex | 用途 |
|---|---|---|
| Canvas | `#07111F` | 页面、操作区、系统栏背板 |
| History | `#202630` | 普通历史气泡、菜单 |
| Raised | `#303947` | nav 当前项 |
| Nav | `#141D28` | 浮动胶囊 |
| TopControl | `#151F2B` | 顶部视图/更多按钮 |
| Ink | `#F5F5F2` | 原文、标题、顶部图标 |
| Muted | `#ABB5C3` | 语言入口、次要状态、未选 nav |
| Amber | `#FFC46B` | 译文、选中 nav、已有播放控件 |
| LeftMic | `#91B5D5` | 左 mic，固定不换色 |
| RightMic | `#E0BC83` | 右 mic，固定不换色 |
| LeftLive / stroke | `#182533` / `#7593AD` | 左 live |
| RightLive / stroke | `#28272A` / `#AC9169` | 右 live |
| Divider | `#48515E` | 同气泡原译文分隔 |
| ShellStroke | `#36404C` | nav/顶控/face 分隔，装饰非唯一识别 |
| ErrorSurface / ErrorInk | `#35242A` / `#FFDAD6` | 真实恢复错误，不能用于 idle |

文字与底色对比（本轮 Python 标准库按 WCAG 相对亮度复算）：Ink/History 13.92:1；Amber/History 9.67:1；Ink/LeftLive 14.23:1；Ink/RightLive 13.60:1；Amber/LeftLive 9.89:1；Amber/RightLive 9.45:1；Muted/Canvas 9.13:1；深色图文/左mic 8.81:1、/右mic 10.54:1；Muted/Nav 8.19:1；Amber/Raised 7.41:1。live stroke/对应底色分别4.83:1、4.96:1。

- 必须按这些 token 显式覆盖容器/内容色，关闭会染色的 tonal elevation；不采用 wallpaper dynamic color 改写。
- 低对比装饰细线不承担状态/控件的唯一辨识。操作图标保持≥3:1，正文/功能短字≥4.5:1。按钮不因非激活而降文字透明度。

### 3.2 字体表

使用 Android 系统 SansSerif/CJK fallback，不引入 SF 字体资源。全部显式 TextStyle，fontScale=1 基准，不依赖未定义的 bodyMedium/labelSmall 默认值。

| 层级 | 字号/行高 sp | weight | 512px 字号/行高约值 |
|---|---|---|---|
| 居中页标题 | 19/26 | 600 | 25/34 |
| 顶部视图入口“视图” | 16/22 | 500 | 21/29 |
| 对话原文 | 19/26 | 500 | 25/34 |
| 对话译文 | 22/29 | 500 | 29/38 |
| face 原文 | 17/24 | 500 | 22/31 |
| face 译文 | 20/27 | 500 | 26/35 |
| 同传原文 / 译文 | 19/26、22/29 | 500 | 同对话 |
| 语言入口 | 13/18 | 500 | 17/23 |
| mic 耳侧·短语言 | 11/15 | 600 | 14/20 |
| live 短状态 | 11/16 | 500 | 14/21 |
| nav 标签 | 10/14 | 500 | 13/18 |
| 同传操作按钮文字 | 14/20 | 500 | 18/26 |

正文 letterSpacing=0，避免对 CJK 机械照搬网页负字距；不额外加 font padding，核对项目 Compose 版本默认/显式 includeFontPadding=false。文字测量验收以行盒与基线为准，不拿 glyph 墨迹高度当字号。

## 4. 顶栏与模式入口

- 左：`视图` 小暗色描边胶囊，radius22dp。打开既有视图选择，条目仅“对话 / 面对面”；沿用原 onSelect/onView 回调及 enabled 条件。若当前组件是直接 toggle，允许以同一切换能力包装成展示菜单，不加新 view。
- 中：几何中心 x=W/2，标题“对话”或“面对面”；绝不左对齐，绝不加状态副标题。
- 右：横向 `···`，图标22dp，容器与左统一；不使用默认黑色/蓝色 icon。
- “手动翻译 / 连续翻译”只在现有更多菜单显示，当前项 Amber+勾选。菜单背景History，radius19dp，左右对齐对应触发器，最小48dp条目；现有结束/恢复动作不丢失，不凭视觉增加可操作阶段。
- 不新加常驻模式 chip、新设置入口或引导弹窗。打开菜单可核对模式；关闭后以按钮实际激活态和 live 短状态表达当前活动。

## 5. Conversation：空闲、有结果、live

### 5.1 阅读区和气泡几何

- 阅读区从 topbar 下缘开始，上padding12dp（示例首气泡顶y≈138），底padding5dp；正常从上到下流式，删除当前 `Alignment.Bottom`。短历史不沉到 mic 边上。长历史保留已有 follow-latest / 手动滚动机制。
- 每行宽 `Wdp-28`。气泡固定宽 `行宽-54dp`，外侧保留48dp操作槽+6dp gap；不按短文本 shrink-wrap。393dp屏：气泡311dp≈405px；375dp屏：293dp。与 web 44px槽的差异仅为 Android 48dp触控要求。
- 左气泡 x=14dp≈18px；右气泡 x=68dp≈89px，右缘=W-14dp。气泡尾角分别 bottomStart / bottomEnd=5dp，其他角22dp（≈29px），无漫画三角尾巴。
- bubble padding：水平15dp、垂直16dp；预占1dp边框，历史边框透明，live才着色。上下行间隔16dp≈21px。
- 内部顺序：固定22dp meta槽 → 8dp间隙 → 原文 → marginTop12dp → 1dp divider → paddingTop12dp → 译文。
- 原文最少一个26sp行盒，译文最少42dp（对齐原型近两行预留）；不通过插入假字符实现。字体放大时最小高度随真实行盒增长。
- meta 仅 live 显示；完成后内容隐藏但22+8dp占位保留，不让整条气泡变矮跳动。原文/译文按真实文本自然换行，不限制两行，不 ellipsis 正文。
- 当前结果无“译音 → 右耳”等脚注。外侧播放槽垂直居中：32dp Amber 圆，内17dp深色图标；下方10/14sp短目标耳标签，整体48dp宽、最小62dp高。
- **功能边界**：当前读取的 `ConversationBubble` 无播放回调。该槽必须预留，以接近认可原型；仅在既有真实回放能力可接入时显示可点击入口。不为达到截图效果添加假播放按钮或模拟播放，不把不可用按钮画成可用。本轮纯视觉实现若无可接入能力，槽留空并在验收记录注明此差异；完整外侧播放视觉验收须由负责人确认后续能力范围。

### 5.2 Idle 不是演示文本填充器

- 真 idle、无历史：**零气泡、零占位说明、零光标、零波形**。已认可 web 本来允许纯空阅读区，不能用假对话/灰 skeleton/欢迎插画掩盖空态。
- 不显“工程demo”的方法是完整、有意图的框架：居中紧凑顶栏、对称语言入口和完整双圆、同底色操作区、浮动暗色导航；删除当前把空白上下夹住的三处说明及亮白底栏。空白不是内容缺陷，未完成的 chrome 才是。
- 不将两个 mic 移到屏幕中间以填空；它们在 idle/result/live 中保持同一固定位置，不产生首句到来后的大跳动。
- 有历史但当前 idle/paused：原历史仍在顶部正常流，停止所有收音动态；不得隐藏已有结果或恢复空提示。

### 5.3 Result 与 live

- Result 用真实数据或测试 fixture，不能在生产 idle 注入演示内容。两条基准：左原文“请问，车站怎么走？” / 译文“How do I get to the station?”；右原文“Follow the river. Take the second left.” / 译文“沿河走，第二个路口左转。”。
- Listening：以现有 activeTurnId 定位末尾当前 article；左/右仅换 live底/描边。meta 内容“左耳 · 收音中”或“右耳 · 收音中”，波形5条、宽2dp/间隔3dp、高8–17dp；原文末尾2dp光标。原文尚空时只光标/短状态，不写“原文正在识别…”。
- Processing：同 article、同宽padding和key，原文已定，译文原位流入、光标移至译文末尾；短状态“左耳 · 翻译中”。未收到译文时区域留空，不插“译文将在识别完成后显示”。只在真实模型有对应 partial 时更新，不自行模拟逐字网络数据。
- Final：原位撤掉波形、光标、meta可见文本和live描边；保留槽位/内边距，不重新创建整张卡。不强改 EventBoundaryDisplay 的真实事件分行语义；多 final 行的新增内容不算视觉动画。
- live 不在底部新建输入卡，不悬浮盖在历史上。保留已有回到最新入口，但改为Raised圆底/Amber图标，48dp，阅读区内右下16dp；不会侵入mic。

## 6. 双 mic / 语言 / manual 与 continuous

操作区完全Canvas，不铺 `#0E1927` 横条，不加顶部分隔。

几何：区域padding top7 / bottom6dp；语言触控48dp高 → gap5dp → mic82dp，合计148dp。每组宽102dp，两组间距40dp，因此中心距142dp；整组居中。393dp宽屏中心x=125.5/267.5dp≈164/349px，mic外缘间距60dp≈78px；默认直径82dp≈107px。示例mic顶y≈780、底≈887。

- mic 内只有26dp图标 → 6dp gap → 一行耳图标+“左耳 · 中”/“右耳 · EN”。删掉单独第三行箭头。短语言由已有语言值映射，不影响真实语言选择。
- 语言入口与各 mic 同中心，最小宽84dp、最大102dp；透明底、Muted13/18sp，带小下拉chevron；文本“中文 / English”。不是两张语言卡，不把入口移到气泡上。Dropdown 延用既有选项/禁用规则，统一暗色。
- 激活侧scale1.065（约87.3dp），另一侧scale0.92（约75.4dp），中心与布局占位不动；外围呼吸环5…9dp，对应mic色8–15% alpha，1.6s平缓周期。颜色不交换、文字不透明化。processing无收音环。
- 动画关闭时使用静态描边与状态文字，不以动画作为唯一提示。禁用沿用实际 actionEnabled/pointerEnabled，不凭外观放开不可用操作。

| 已有模式/阶段 | 视觉差异 | 不变的行为边界 |
|---|---|---|
| manual idle | 两圆默认大小，无状态副标题 | 按住对应侧请求收音 |
| manual listening | 对应侧激活+live | 松开走现有提交/processing |
| manual processing | 两圆回默认，禁止误画正在收音；live译文更新 | 不新增可抢占手动句入口 |
| continuous idle | 两圆仍同大小；右侧既有禁用态用静态细轮廓，不灰掉文字 | 左圆开始，右圆不可提前接话 |
| continuous listening left | 左激活，右缩小 | 左圆暂停；右按住接话 |
| continuous right takeover | 右激活，左缩小 | 按现有右释放/取消回调恢复；不自动判人 |
| continuous paused | 无呼吸/光标；可在操作区既有语义中标“已暂停”，不加可见说明带 | 左圆恢复；保留已有结束入口 |
| stopping / error | 收音环停；保留既有安全错误和恢复入口 | 不自动重试、恢复或生成live |

删除 `AutoControls` 重复底部横排的视觉输出不等于删除动作：开始/暂停/恢复已有左圆入口；结束已有 overflow 入口。不得改变 MicPressGate、权限请求、取消/释放、回调所有权或处理延迟。

## 7. Face：两个阅读区，一套固定双 mic

- 从 topbar下缘到operation上缘的可用阅读高度，等分上下两半，中间1dp divider；不能用两个minHeight220dp item放进外层LazyColumn把布局撑成长页面。
- 左右padding15dp；divider再内收34dp，即x=49dp…W-49dp，色ShellStroke。基准分隔y≈412px；上下阅读区各约289px，计算随inset变化。
- 上半far/right整体阅读内容旋转180°；下半near/left正向。**顶栏、整套语言选择、两颗mic、底部nav均不旋转**。旋转区域边界严格裁剪，不能触及底部控制。
- 双方阅读同一份历史和当前article的镜像副本；复制显示不复制数据/状态机。当前 `faceToFacePanelTurns` 的按侧过滤不能被当成“镜像”保留，否则上方看不到另一方的回答；仅变更传给阅读渲染器的数据投影，保留同一turn身份、activeTurnId。
- 每半独立scroll，padding top12/bottom5dp，turn间距12dp；bubble几何同conversation，字体降至17/24和20/27；不缩放整个控件树。
- 最新live属于每半阅读内容的正常流，禁止在中线或按钮上方另叠一个固定输入卡。短内容靠该半的阅读起点；上半旋转后按对方视角阅读，不能仅翻转字形而不翻转布局。
- 双mic仍固定屏幕左/右，不能随说话方、旋转方向调换颜色位置。长文本可滚动，分隔线与mic不可被滚走。

## 8. Interpretation：同传不是另一个浅色产品

此页没有独立 web 原型；以下为继承同一已认可设计语言的视觉适配，不宣称用户已验收该页。保持 `InterpretationScreenModel`、actions、primaryAction、dispatcher 不变。

### 8.1 布局

- 页面Canvas；同样66dp顶栏，标题“实时同传”居中。左返回为44dp暗色圆控件+48dp触控；右对称占位保证标题居中，仅按已有状态显示小live marker，不新增更多菜单。
- 顶栏下语言方向行高36dp，居中“中文 → English”，13/18sp，源Muted、目标Amber；不含“原文/译文”后缀，不用两块灰紫chip。当前没有此页切换语言回调，方向行是只读标签，不画可点击下拉。
- 阅读区顶部padding12dp、水平14dp；首内容示例y≈185px。每个现有 `InterpretationDisplayBubble` 仍按已有稳定key进入同一LazyColumn，行间16dp。
- 气泡满阅读宽 `Wdp-28`（393dp屏365dp≈476px），radius22dp，无左右尾角、无双耳播放槽。内padding15/16dp，原文19/26sp Ink在上，细线和译文22/29sp Amber在下，与conversation同层级；禁止“译文大卡 + 次级灰原文”两套容器。
- 原文/译文partial在同一条的原位置增长。若 EventBoundaryDisplay 返回源/译缺项行，保留真实对应关系和空行盒，不靠字符串猜测配对；不强行修改模型或制造一条不存在的完整句。
- 不应凭 `RUNNING` 把所有历史卡画成live；只有现有partial证据指向当前行时才加LeftLive底/描边/短光标。模型没有明确partial证据时，保持History和顶栏既有运行指示即可，不添加假流式状态。
- Idle无任何灰卡/“译文会显示在这里”。真正空文本的RUNNING可保留小运行指示，不能新建大占位卡。错误仍显示真实可操作恢复，不被“删除说明”误删。

### 8.2 固定操作行

- 与nav之间仍9dp；nav同第9节。操作区域高度76dp（padding top/bottom14dp + 48dp按钮行），Canvas，横padding20dp，不铺蓝灰整块，无重复“准备开始”状态栏。
- primary按钮min宽104dp、高48dp、radius24dp，LeftMic底+Canvas图标/字，图标22dp/文字14sp。仅有主动作时居中；同时有FINISH时整组居中，间隔12dp；FINISH 88×48dp暗色TopControl/细描边+Ink，保留“结束”或连接期“取消”。
- STARTING/STOPPING无primary时，原有短statusLabel可在该行显示一次13/18sp；STARTING保留取消动作，STOPPING不添加动作。ERROR的RESET文字保留完整“重新开始翻译”，宽度按内容增加，不能压进104dp截断。
- 运行时显示已有“暂停 / 结束”，暂停时“继续 / 结束”；不把同传改为双mic，不增加双耳路由或新手势。
- 移除旧 `MicrophoneStatus + statusLabel` 独占一排的版式；麦克风/暂停图标归真实操作按钮。动作来源只读model.actions，不自行推断可用操作。
- 归一化示例：操作区y≈796…895，按钮y≈814…877；nav仍y≈906…984。它与双耳页底栏完全同位置，只把单页操作区做紧凑，而非硬凑双mic高度。

## 9. ProductBottomBar：一条暗色胶囊

- 本体左右30dp、高60dp、radius30dp，背景Nav、1dp ShellStroke，零elevation；内padding4dp，三项等分、gap3dp。
- 每项最小48dp高，正常50dp；图标22dp+gap2dp+标签10/14sp。选中项整个小胶囊Raised、radius25dp，图文Amber；未选中图文Muted。不是只有icon后面一个小浅紫椭圆。
- 延用当前目的地顺序和回调，不为模仿web重排路由。`FACE_TO_FACE` 导航显示名统一为“对话”（对应包含conversation/face两种视图的已有目的地），不让conversation页底部仍写“面对面”；此为呈现标签调整，不改enum/持久化key。其余“同传 / 我的”。
- 无浅白全宽背景、无品牌蓝选中态、无常驻保存提示。胶囊外侧、下方安全区、与操作区之间全部Canvas。

## 10. 必须从可见主界面删除的文字

删除视觉节点，不盲删无障碍描述或错误处理字符串：

- 顶部/空态：“按住对应麦克风开始”“按住对应麦克风开始对话”“点击左侧开始连续收音”。
- 正常状态副标题：“左耳正在收音”等重复长句、“正在翻译，暂不可操作”“正在结束，暂不可操作”。真实状态保留在live短meta、必要的过渡行和semantics，不能同时出现两三处。
- 常驻footer：“完成的文字自动保存，不保存音频”。如该信息由共用shell输出，需在shell移除翻译页常驻渲染；不能仅在子页面上遮盖。是否提供已有帮助入口由现有产品行为决定，不新增功能。
- 气泡：“原文正在识别…”“译文将在识别完成后显示”“译音 → 左耳/右耳”。mic第三行“→ 右耳/左耳”。路由说明保留在contentDescription/已有帮助中。
- 重复全宽按钮栏：“开始连续翻译”“暂停连续翻译”“恢复连续翻译”“结束连续翻译”的底部冗余展示；对应操作必须在已有左mic/overflow可达。
- 同传空态：“译文会显示在这里”“开始同传后，原文和译文会按顺序出现。”；重复“准备开始”；语言chip上的“原文/译文”后缀。
- 不进入产品的web实验标签：“OFFLINE STUDY”“DUAL EAR / 02”“原型控制器”“模拟一句”“无真实录音或播放”等。

必须保留：真实权限说明、安全错误、重新开始翻译/重试入口、必要的暂停/连接/结束状态、可访问操作名称、实际原文和译文。“不要说明”不意味着掩盖失败或丢失控制。

## 11. 截图验收：内容正确不等于视觉通过

至少提交以下5张，建议提交9张。统一同一台Xiaomi、相同fontScale=1/显示大小/系统导航，记录构建hash与fixture；APK必须实际运行，不能拿HTML截图冒充。

| 必交编号 | 场景 | 截图必须出现/不出现 |
|---|---|---|
| 01 conversation-idle-manual | 无历史、未收音 | 居中标题、零占位文本、完整双圆、两语言入口、暗色胶囊nav；没有白色底栏 |
| 02 conversation-result-manual | 两条第5节基准文本，一左一右 | 从顶端正常流、白原文/大琥珀译文、同卡分隔、固定错位宽度；播放能力缺口单独记录 |
| 03 conversation-live-continuous | 至少一条历史+当前左侧partial | 历史不变、末尾live、短meta/光标、左激活右缩小、无额外AutoControls按钮栏 |
| 04 face-live | 同一历史+当前右侧接话 | 上半180°、下半正向、同份内容双副本、中线；底部双圆均正向且位置不变 |
| 05 interpretation-running | 至少一个完成行+一个真实partial行 | 整页暗色；原文/译文同层顺序、无灰空卡/蓝底操作条；暂停/结束和nav清晰 |

补充必测路径（推荐另存图）：06 conversation-processing（译文partial）；07 continuous-paused；08 interpretation-idle；09 face-long-text-font135。另录短屏幕视频核对live→final不跳位及上下阅读滚动。fixture只用于测试注入，不进入生产初始状态。

### 11.1 像素级 reject criteria（任一项即退回）

测量条件：先裁掉截图外框，对应用内容按第2节换算；系统栏时钟、通知图标、系统privacy点和字体抗锯齿不做逐像素全图diff。

1. **底色割裂**：Canvas采样点任一RGB通道偏差>2（PNG、无色彩滤镜条件）；或应用区域出现旧浅色连续色块≥24×24归一化px（系统权限弹窗除外）。nav外还出现白条，直接reject。
2. **顶栏错误**：扣除T后高度偏离66dp超过2dp；标题中心偏离屏幕中心>2dp；出现第二行操作说明；旧左对齐“对话+按住…”一律reject。
3. **控制几何**：idle两mic直径非82±1dp，中心距非142±2dp，圆宽高差>1dp；只剩一主圆+一胶囊；对话/face任一mic被旋转、裁切、落进nav，直接reject。512基准直径约107±2px、中心距185±3px。
4. **安全区/导航**：nav本体非60±2dp，左右margin非30±2dp；底间距不是12dp+实测B（误差>2dp）；重复inset造成多余空带>4dp；任何点击区落到系统手势区域，reject。
5. **气泡尺度**：conversation气泡宽偏离`Wdp-82`超过2dp、左/右起点偏离14/68dp超过2dp；radius偏离22dp>2dp或无5dp尾角；仍以360dp cap几乎填满屏，reject。
6. **文字层级**：conversation/同传原文不为19sp±0.5、译文不为22sp±0.5；行高偏离26/29sp>1sp；译文小于原文、译文变白/蓝、正文ellipsis、裁字，reject。face按17/20sp同尺度判定。
7. **内容位置**：相同fixture首条顶端偏离topbar后12dp>2dp（同传另加36dp语言行）；两条短结果沉到下方操作区；任何固定占位输入卡，reject。
8. **live稳定性**：同内容定格的live→final，bubble宽/padding/左右坐标位移>1dp或高度因删meta骤缩>2dp；多余底部live面板；已完成文本仍有光标/波形；都reject。真实内容增加引起自然换行不算跳位。
9. **face分割**：两半高度差>2dp（除1dp中线取整）；上半不为180°或旋转了控制区；一边只显示自己的发言而另一边遗漏回答，reject。
10. **同传遗留**：仍出现176dp灰色empty card、灰紫语言chip、翻译优先/灰原文的旧层级、满宽蓝灰操作条、重复两处“准备开始”，任一reject。
11. **文本与虚假控件**：第10节删除文本仍可见；用假对话填idle；展示没有真实callback的可点播放/语言切换；直接reject，不以“视觉还原”为借口。
12. **回归**：同fixture 135%字体正文/语言被截断、触控目标<48dp、所有页面跟系统浅色切换回亮白、原有错误恢复/结束入口丢失，reject。

## 12. 实施顺序与验证责任

1. 先统一翻译页shell/theme与系统栏/nav，删除共用footer可见说明；截idle确认，不先堆气泡。
2. 按尺寸重排顶栏、双mic/语言入口；不动MicPressGate与权限/会话协调器。
3. 调整ConversationBubble与timeline布局，测试真实空态/两条结果/live/processing；不重写事件配对和状态推进。
4. face只改变投影/布局与旋转边界；保留一套固定控制。
5. Interpretation复用相同视觉token，按现有model.actions渲染紧凑控制；不增加model无法表达的partial状态。
6. 运行项目现有相关unit/instrumentation测试、编译、可用lint；通过这些之后仍须完成本节真机截图矩阵。测试通过不能代替视觉验收。

Compose实现依据（已通过context7核对官方文档）：
- https://developer.android.com/develop/ui/compose/system/material-insets （Scaffold innerPadding与消费）
- https://developer.android.com/develop/ui/compose/system/insets-ui （safeDrawing与系统安全区域）
- https://developer.android.com/develop/ui/compose/text/style-paragraph （行高与font padding）

本规格验证记录：已读取两张失败图、认可HTML/README、上述当前Compose与主题、同传model/action映射；核对HEAD；复算13组对比度。Python图像库PIL未安装，初次尺寸读取失败，随后用标准库PNG头解析成功，无安装依赖。未运行/修改APK，未拍新截图；iOS原图缺失、设备真实density/inset未测、真实播放入口未确认，均不能宣称已通过视觉验收。工作区已有 `.pi/git/` 与 `.scratch/` 未跟踪内容，不修改/暂存它们；本文件按用户要求不提交。
