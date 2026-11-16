> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-5-fix-round-2-report.md`；原始 SHA-256 `a01211a2c02e9e87ce7a6e78f3e33cda4fc1fa8042a26b6c5416718a62fde3df`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 5 I-6 fix round 2 report

## Scope

Only changed `[旧临时路径]`. No device/emulator run, no subagent dispatch, and no token/credential output.

## I-6 remediation

- Added `CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED`, a fixed UI-safe enum with no `sessionId`, token, or transport exception.
- Added lifecycle-bound `collectTerminalCloudSessionFailures`: it consumes `TranslationSessionCoordinator.endFailures` and maps only `willRetry=false` to the safe code. Retryable failures are ignored.
- `FaceToFaceViewModel` and `InterpretationViewModel` each now expose read-only `StateFlow<CloudSessionFailureCode?>` as `cloudSessionCloseFailure` and start their respective observer binding in `init` using `viewModelScope`. The collector is therefore cancelled with the ViewModel and does not alter Cloud retry, sockets, or microphone state.
- Added deterministic coroutine tests using `runCurrent()` before each `MutableSharedFlow.emit()` and `advanceUntilIdle()` afterward; no sleep/poll timing is used. Tests are explicitly opted in to `ExperimentalCoroutinesApi`.

## Coverage

- terminal failure maps to `CLOUD_SESSION_CLOSE_FAILED` without escaping the input session identity;
- retryable (`willRetry=true`) events leave state unset for each Face-to-Face and Interpretation production binding;
- each production binding publishes the safe terminal code;
- a coordinator that fails once then succeeds on its bounded retry does not produce terminal observer state.

## Validation

Environment:

```text
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk
PATH=$JAVA_HOME/bin:$PATH
```

Passed focused tests:

```text
./gradlew --no-daemon testDebugUnitTest --tests '*CloudSessionFailureObserverTest' --tests '*CloudSessionFailureBindingsTest' --tests '*TranslationSessionCoordinatorTest' --tests '*FaceToFaceCoordinatorTest' --tests '*TurnSessionCoordinatorTest'
```

Passed full Android quality gate:

```text
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The full gate completed with `BUILD SUCCESSFUL` (55 actionable tasks).

## Deferred risk

No real device/emulator, live network, lifecycle race, or server-side session validation was performed, per task constraint. The internal observer is in-memory and process-local; process termination during retry remains outside this change's scope.
