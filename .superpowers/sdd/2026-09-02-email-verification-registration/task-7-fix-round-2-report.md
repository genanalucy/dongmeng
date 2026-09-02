# Task 7 fix round 2 report

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
