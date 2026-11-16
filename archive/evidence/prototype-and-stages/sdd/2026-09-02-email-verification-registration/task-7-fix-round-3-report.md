# Task 7 fix round 3 report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-7-fix-round-3-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

Status: complete.

Root cause:
- The ViewModel registration request guard was a plain Boolean. Public direct callers on different threads could concurrently observe it false and both enqueue a resend.

TDD:
- Added `concurrentResendsDispatchExactlyOneRequest`, using two worker threads, a start latch, and a clock barrier to force concurrent entry into the expired resend path.
- RED recorded: focused test failed at the exact-one-request assertion with the Boolean guard, proving duplicate dispatch.
- GREEN: the same test passes after atomic acquisition.

Fix:
- Replaced the plain Boolean with `AtomicBoolean.compareAndSet(false, true)` before state publication/coroutine launch.
- The guard releases in `finally`, covering success, failure, and cancellation.
- No privacy behavior changed: password/code are neither persisted nor logged.

Validation:
- Focused: `./gradlew testDebugUnitTest --tests '*AccountViewModelPhoneAuthenticationTest.concurrentResendsDispatchExactlyOneRequest'` — PASS.
- Full: `./gradlew testDebugUnitTest lintDebug assembleDebug` with supplied JDK/SDK — PASS.
- `git diff --check` — PASS.
