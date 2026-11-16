# Task 5 Fix Round 4 Report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-08-29-android-core-translation-ui-redesign-implementation/task-5-fix-round-4-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

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

- Commit: `6b2ab69aad0ab42e03755c425a2aaacab95b4af8` (`fix(android): preserve account navigation history`).
- Push: `origin/android-ui-redesign` updated successfully.
