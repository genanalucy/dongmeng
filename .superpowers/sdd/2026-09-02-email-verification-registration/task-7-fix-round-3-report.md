# Task 7 fix round 3 report

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
