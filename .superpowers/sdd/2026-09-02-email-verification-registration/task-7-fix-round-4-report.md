# Task 7 fix round 4 report

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
