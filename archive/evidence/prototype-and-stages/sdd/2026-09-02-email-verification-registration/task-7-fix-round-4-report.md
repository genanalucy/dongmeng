# Task 7 fix round 4 report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-7-fix-round-4-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

Status: complete.

Test-proof root cause:
- The prior concurrency barrier was in clock eligibility, not in acquisition; it could not deterministically force the former Boolean guard's read/set race.

TDD:
- Extracted the production `RegistrationRequestGate` seam and wrote VM-path tests around it.
- RED recorded: `coordinatedNonAtomicGateDeterministicallyAllowsTwoResends` was temporarily asserted as one resend and failed deterministically at the API-call-count assertion. The controlled non-atomic gate holds both callers after observing false and before setting true, producing initial request plus two resend dispatches.
- GREEN: under the identical caller schedule, `AtomicRegistrationRequestGate` produces initial request plus exactly one resend dispatch.

Fix:
- `AccountViewModel` now uses the production `AtomicRegistrationRequestGate`; gate acquisition occurs before state publication/coroutine launch and release remains in `finally`.
- Test-only gate injection is restricted to the constructor seam; no UI or privacy behavior changed.

Validation:
- Focused: `./gradlew testDebugUnitTest --tests '*AccountViewModelPhoneAuthenticationTest.coordinatedNonAtomicGateDeterministicallyAllowsTwoResends' --tests '*AccountViewModelPhoneAuthenticationTest.atomicGateAllowsOnlyOneConcurrentResend'` — PASS.
- Full: `./gradlew testDebugUnitTest lintDebug assembleDebug` with supplied JDK/SDK — PASS.
