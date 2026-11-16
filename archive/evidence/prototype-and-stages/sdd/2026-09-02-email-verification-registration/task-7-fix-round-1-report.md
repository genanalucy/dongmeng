# Task 7 fix round 1 report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-7-fix-round-1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

Status: complete.

Root cause:
- Challenge retry time was an immutable response value, so the Compose UI never observed cooldown expiry and resend remained disabled.

TDD:
- Added `RegistrationResendPolicyTest.resendBecomesEnabledAtCooldownExpiryAndInvokesItsRequest` before production code.
- RED recorded: focused Gradle test failed with unresolved `RegistrationResendPolicy` references, as expected.
- GREEN: focused resend policy and ViewModel tests pass.

Fix:
- Store only a resend deadline in challenge UI state; derive remaining seconds from the current clock.
- Compose runs a challenge-keyed timer while cooling down, then enables resend. The resend action invokes the existing verification request only after deadline expiry.
- Password/code remain unpersisted; the resend path requires current form credentials and does not add them to ViewModel state.

Validation:
- Focused: `./gradlew testDebugUnitTest --tests '*RegistrationResendPolicyTest' --tests '*AccountViewModelPhoneAuthenticationTest'` — PASS.
- Full: `./gradlew testDebugUnitTest lintDebug assembleDebug` with supplied JDK/SDK — PASS.
- `git diff --check` — PASS.
