# Task 5 Fix Round 2 Report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-08-29-android-core-translation-ui-redesign-implementation/task-5-fix-round-2-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

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
