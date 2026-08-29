# Task 5 Fix Round 2 Report

## Delivered

- Removed the visible Face-to-Face exit affordance and its no-op host callback because Face-to-Face is the default primary root.
- Kept Interpretation’s explicit exit meaningful by routing it to Face-to-Face.
- Added `ProductNavigationPolicy.showsProductBottomBar(mode, screen)` and made the host use it, preserving USER’s three destinations and ADMIN_TEST’s “测试 / 我的” destinations while hiding bars for authentication and secondary routes.
- Added policy regressions that fail if host visibility falls back to USER-only logic.
- Made permission-ordering acceptance explicit: a pre-decision release/cancel invalidates the pending start; a later grant must not start capture; denial never starts capture; each gesture has exactly one terminal release/cleanup.

## TDD evidence

- RED: focused `ProductNavigationPolicyTest` did not compile because `showsProductBottomBar` was absent.
- GREEN: focused policy test passed after adding the pure policy and updating host integration.

## Verification

- Focused `ProductNavigationPolicyTest` passed using explicit JDK 17 and Android SDK configuration.
- `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` passed using explicit JDK 17 and Android SDK configuration.
- `git diff --check` and the tracked-artifact exclusion check passed; no cache or APK artifact is tracked.
