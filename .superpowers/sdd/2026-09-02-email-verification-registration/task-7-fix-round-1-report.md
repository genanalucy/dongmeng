# Task 7 fix round 1 report

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
