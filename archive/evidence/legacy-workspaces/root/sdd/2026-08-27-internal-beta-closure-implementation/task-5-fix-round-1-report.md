> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-fix-round-1-report.md`；原始 SHA-256 `b3d63221bf0b60683ac983ede8590e99954316ce50282c09d754f3f25b531059`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 fix round 1 report

## 范围

仅修改 `[旧临时路径]` 的 Task 5 coordinator 源码与单元测试；本报告位于外部 SDD 目录，不纳入 Git。未运行真机或模拟器，未输出 token、DSN 或其他凭据。

## 修复

- `TranslationSessionCoordinator` 现在接受可注入的 `CoroutineDispatcher`，生产默认 `Dispatchers.IO`。
- 同步 Cloud `createTranslationSession`、`createUsageRecord` 与 `endTranslationSession` 均在该 IO dispatcher 中执行；协调状态仍可从 ViewModel 的协程上下文管理，不会在 Main dispatcher 执行同步 HTTP。
- coordinator 自行拥有最多三次的有限 end 尝试，默认线性退避；上层即使立刻清空 grant/session ID，正常结束、manual empty、auto drain、cancel 与迟到 grant 的同一 `end()` 路径仍可完成重试。
- usage 继续最多启动一次；同一 session 的 `endInFlight` 保证同一时间最多一个 end 请求；session state 仅在远端 end 成功后移除。
- 耗尽重试会以 `endFailures: SharedFlow<EndFailure>` 公开可观察的失败结果，而非静默吞掉；state 会保留，后续显式 `end()` 仍可再次尝试。

## TDD 与测试

新增确定性 `TranslationSessionCoordinatorTest` 场景：

1. 注入具名单线程 IO dispatcher，fake service 记录 usage/end 调用线程，断言两次同步调用均在该 dispatcher。
2. 首次 end 失败后 coordinator 自动重试并成功，验证 usage 一次、end 两次。
3. 耗尽有限重试时公开最终失败，验证 usage 一次、end 两次、失败次数与 `willRetry=false`。

测试首次编译按预期暴露缺少 dispatcher/retry API；实现后曾出现纯测试编译问题：`use` Lambda 的泛型推断歧义及漏导入 `assertTrue`。修复为显式 `ExecutorCoroutineDispatcher` 类型与 JUnit `assertTrue` 导入，未改变测试意图。

## 验证

使用：

```text
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH
```

通过：

```text
./gradlew --no-daemon testDebugUnitTest --tests '*TranslationSessionCoordinatorTest' --tests '*FaceToFaceCoordinatorTest' --tests '*TurnSessionCoordinatorTest'
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

完整质量门 exit code 为 0，55 actionable tasks。Debug APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`，未提交。

## 遗留风险

未进行真机/模拟器验证。仍需在受控环境验证真实网络下的 Cloud retry 时序、AudioRecord/AudioTrack 资源收口、后台/旋转生命周期和服务端会话最终状态。有限重试后若仍失败，失败会被 coordinator 公开并保留内存资格；进程终止后无法保证重试，未引入持久化队列以避免持久化会话凭据或扩大本任务范围。
