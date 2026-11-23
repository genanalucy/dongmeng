# Android 功能与使用指南

本页按 `main` 的 Android 客户端编写。普通用户登录后默认进入面对面工作台，底栏显示「对话」「同传」「我的」。已发布 APK 可按标签查阅各自的版本记录。[版本与安装包](development/repository-status.md) · [客户端导航](../android/app/src/main/java/com/verba/interpretation/ui/navigation/ProductBottomBar.kt)

## 登录与翻译准备

已有账户可用邮箱、手机号或用户名配合密码登录。新用户在「注册账户」填写用户名、邮箱和密码，按提示完成拼图验证。开始云端翻译需要联网、有效的账户权益和麦克风权限。可从「我的 → 权益详情」查看到期时间、用量或兑换权益码。[登录与注册界面](../android/app/src/main/java/com/verba/interpretation/ui/account/PhoneAuthenticationForm.kt)

## 两个人交谈

1. 打开「对话」，分别选择双方使用的语言。客户端提供中文、英语、法语和越南语，两侧须选择不同语言。[语言选项](../android/app/src/main/java/com/verba/interpretation/ui/LanguageConfig.kt)
2. 在默认的「按住说话模式」中，按住自己语言一侧的麦克风，说完一句后松手。对方用另一侧麦克风回答。屏幕按轮次显示原文和译文，服务返回译音后由手机播放。
3. 两个人共看一块屏幕时，可点「视图」切换「对话视图」或「面对面视图」。需要连续收音时，在空闲状态打开右上角更多菜单，选择「连续翻译模式」。两支麦克风中间的按钮用于开始、暂停和继续，更多菜单中可结束连续翻译。[面对面界面](../android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceScreen.kt) · [模式菜单](../android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceOverflowMenu.kt)

## 听一个人连续发言

打开「同传」，选好发言语言和译入语言，点「开始」。发言时可对照原文和译文；中途休息点「暂停」，再点「继续」恢复收音。发言结束后点结束按钮，等待剩余字幕处理完成。下次开始前可用两种语言之间的交换按钮反转方向。[同传界面](../android/app/src/main/java/com/verba/interpretation/ui/interpretation/InterpretationScreen.kt)

## 找回记录、分享和检查更新

- **历史记录**　在「我的 → 历史记录」中按标题、原文、译文或语言代码搜索本机记录。点开会话可逐条对照原译文，也可通过系统分享面板发送单个会话、搜索结果或全部记录。[历史页](../android/app/src/main/java/com/verba/interpretation/ui/history/HistoryPage.kt)
- **云端同步**　进入历史页时，客户端尝试同步当前账户的记录。Debug 包另提供「立即同步历史」和同步状态。需要确认跨设备同步时，可在另一台设备登录同一账户，打开历史页核对会话。[历史页入口](../android/app/src/main/java/com/verba/interpretation/MainActivity.kt)
- **版本与账户**　在「我的 → 关于」查看版本和检查更新。在「安全与登录」查看登录状态或退出账户。[我的页面](../android/app/src/main/java/com/verba/interpretation/ui/account/AccountScreen.kt)

## 真机复核

使用可联网的测试手机和有翻译权益的账户，记录 APK 版本、设备和语言方向。下表列出操作和预期结果，实测结果可填入[反馈表](testing/software-usage-feedback-form.md)。

| 操作 | 预期结果 |
|---|---|
| 在「对话」允许麦克风权限，中文一侧说「车站在哪里？」并松手，对方在英语一侧回答。 | 两轮分别显示各自的原文及对方语言的译文。使用耳机时，核对译音的声道及末尾是否完整。 |
| 在「同传」说一句话，暂停后再继续说第二句，然后结束。 | 暂停时停止收音，继续后出现第二句字幕，结束后回到可开始状态。 |
| 在「我的 → 历史记录」搜索刚才说过的词，打开会话并点分享。 | 可以核对已保存的原文和译文，系统分享面板打开。 |
