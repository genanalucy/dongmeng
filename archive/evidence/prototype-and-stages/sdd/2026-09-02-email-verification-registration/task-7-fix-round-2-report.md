# Task 7 fix round 2 report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-7-fix-round-2-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

Status: complete.

Root cause:
- `resendRegistrationVerification` checked a completed cooldown but did not synchronously claim the request before queuing its coroutine. Consecutive callers could pass the same challenge check and dispatch duplicate requests.
- The production Compose timer used the system clock directly, preventing deterministic production-path UI testing.

TDD:
- Added the queued-dispatch duplicate-resend ViewModel regression first. RED recorded: `duplicateResendBeforeQueuedDispatcherRunsDispatchesOnlyOneRequest` failed, proving two API requests were queued.
- Added the Compose controlled-clock/ticker test first; it drives the actual `AuthenticationForm` resend control from 59 to 60 seconds, asserts enablement, clicks it, and asserts exactly one callback dispatch. Before the production clock/ticker seam existed, this test could not compile against the production composable parameters; the subsequent test-device run is blocked because no Android device/emulator is attached.

Fix:
- `AccountViewModel` now synchronously marks a registration-verification request in flight and publishes loading before launching its coroutine; the guard resets in `finally` after both success and failure.
- `AuthenticationForm` accepts narrow defaulted clock/ticker dependencies used only by the challenge countdown. Production defaults remain wall clock/one second. The actual resend button uses the derived deadline state and ViewModel entry guard.
- Password/code are not persisted or logged. Password remains composition-local only for the required resend request.

Validation:
- Focused unit: `./gradlew testDebugUnitTest --tests '*AccountViewModelPhoneAuthenticationTest'` — PASS.
- Android-test source: `./gradlew compileDebugAndroidTestKotlin` — PASS.
- Instrumented Compose execution attempted with `connectedDebugAndroidTest` — blocked: no connected devices.
- Full: `./gradlew testDebugUnitTest lintDebug assembleDebug` with supplied JDK/SDK — PASS.
- `git diff --check` — PASS.
