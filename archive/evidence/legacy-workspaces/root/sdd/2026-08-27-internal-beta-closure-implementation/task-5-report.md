> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-report.md`；原始 SHA-256 `3968da585b92f578ff39951f3d26912f30e9bf5ed350a7770bd86b9391fdeb83`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 Android 生命周期修复报告

## 范围

仅修改 `[旧临时路径]` 下的源码和单元测试；未运行真实设备或模拟器，未输出 credential、token 或 DSN。

## 修复

- `TranslationSessionCoordinator`
  - `open()` 返回可取消的 `OpenHandle`。取消后的迟到 grant 被登记后立即进入同一 `end()` 路径，不会交给 UI 回调。
  - session 状态保留到远端 end 成功；一次 end transport 失败后，下一次显式 `end()` 可重试。
  - usage 资格独立且最多一次；`endInFlight` 防止并发的重复 end。
- `FaceToFaceCoordinator`
  - 增加 `canCloseCloudSession()`，仅当 entries 和 playback 都排空、状态已为 `IDLE` 时返回 true。
  - automatic stop 空输入可立即完成状态收口；普通 automatic stop 必须等待每个 turn terminal 和 TTS drain。
  - transition 显式标记空输入/空 stop 的 session close。
- `FaceToFaceViewModel`
  - 使用 pending `OpenHandle` 和 operation generation；cancel、failure、manual release、auto stop 会使 in-flight acquisition 失效。
  - 迟到 grant 或 mode/phase guard 失败仅调用 Cloud close，不创建 socket 或启动 microphone。
  - manual empty release 走相同的 `endCloudSession()` 幂等路径。
  - auto/manual 的 drain 收口后才关闭 grant。
- `InterpretationViewModel`
  - 使用相同的 pending acquisition 和 operation generation 机制。
  - cancel/onCleared 后迟到 grant 只关闭；`STARTING` 以外的 callback 不得创建 socket/microphone。

## TDD 证据

新增测试先后出现过：

1. `OpenHandle.cancel` 未实现导致编译失败；实现后测试通过。
2. `canCloseCloudSession` 未实现导致 `FaceToFaceCoordinatorTest` 编译失败；实现后通过。
3. auto-drain test 的第二个 drain 交接断言本身错误，修正测试编排后通过。

覆盖的确定性 cases：

- auto stop 在所有 turns terminal 且 TTS drain 前不具备 close 资格；
- auto stop 空输入可即时 close；
- end 第一次 transport failure 后 retry 成功，usage 一次、end 两次（一次失败一次成功）；
- cancel 后迟到 grant 仅关闭，不交付 `onGranted`。

## 验证

所有 Gradle 命令使用明确环境，而非系统 `java`：

```text
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH
```

通过：

```text
./gradlew --no-daemon testDebugUnitTest --tests '*FaceToFaceCoordinatorTest' --tests '*TranslationSessionCoordinatorTest' --tests '*TurnSessionCoordinatorTest'
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

最后一个完整质量门成功，55 actionable tasks。Debug APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`，未纳入 Git。

## Warnings

Kotlin 编译输出了 3 个既有的 `Condition is always 'true'` warnings，位于 `FaceToFaceCoordinator.kt` 的 165、194、301 行。这些来自已有 null-check 模式；本任务未扩展其行为，lint/build 未失败。

## Deferred device risks

未执行真机或模拟器：仍需在受控设备上验证 AudioRecord/AudioTrack drain、后台/旋转真实 callback 时序、网络中断、蓝牙路由及 Cloud/Agent 实际 end/usage 时序。单位测试不能替代这些验证。
