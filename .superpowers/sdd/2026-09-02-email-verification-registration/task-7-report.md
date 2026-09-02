# Task 7 report

Status: complete.

Changes:
- Replaced the Compose new-registration form with email verification details and challenge steps; new registration contains username, email, password, confirmation, then masked-email six-digit verification.
- Removed new-registration phone input, phone DTO, deprecated local-only `register` bridge, and its call sites; legacy phone/email/username login identifier normalization remains.
- Added ASCII six-digit code policy/submission checks and focused UI/policy coverage, including disabled resend during the 60-second retry window.
- Preserved narrow mainland-phone validation for account identity settings only.
- Password fields are cleared when a verification request is dispatched; ViewModel does not retain passwords or verification codes. The challenge retains the email solely to confirm the verification request and exposes only its masked form in UI.

Validation:
- `cd android && export JAVA_HOME="$HOME/.local/share/verba-android-tools/jdk17" ANDROID_SDK_ROOT="$HOME/.local/share/verba-android-tools/android-sdk" && ./gradlew testDebugUnitTest lintDebug assembleDebug` — PASS
- `git diff --check` — PASS

Scope:
- No Go, EC2, API deployment, or other external-system changes.
