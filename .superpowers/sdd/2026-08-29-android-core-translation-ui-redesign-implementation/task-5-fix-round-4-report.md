# Task 5 Fix Round 4 Report

## Delivered

- Replaced static secondary-route exit targets with immutable `ProductNavigationStack` state.
- `InterpretationApp` now resets its stack on navigation-mode changes, renders `stack.current`, pushes secondary routes, pops only actual history, and resets history when a bottom primary destination is selected.
- System Back is enabled only while the local stack can pop.
- Interpretation exit explicitly selects the Face-to-Face primary root.
- Added JVM regressions for Account → History → Back, Account → Endpoint Settings → Back, Profile → Endpoint Settings → Back, root pop behavior, and primary selection history reset.
- Added the three required return-path checks to the token-safe acceptance checklist.

## TDD evidence

- RED: focused policy test compilation failed because `ProductNavigationStack` did not exist.
- GREEN: focused `ProductNavigationPolicyTest` passed after the immutable stack implementation and host integration.

## Verification

- Focused policy test: passed.
- Full `testDebugUnitTest`, `lintDebug`, and `assembleDebug`: passed with JDK 17 and the configured Android SDK.
- `git diff --check`: passed.
- Tracked-artifact exclusion check: passed; `android/app/build/outputs/apk/debug/app-debug.apk` exists locally and is not tracked.

## Commit and push

- Commit: `6ba19a72914481a9957e9836385dd5ff61bc096d` (`fix(android): preserve account navigation history`).
- Push: pending at report update; recorded after push.
