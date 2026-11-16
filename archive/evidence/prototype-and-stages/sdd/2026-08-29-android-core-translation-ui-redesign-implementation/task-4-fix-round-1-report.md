# Task 4 Fix Round 1 Report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-08-29-android-core-translation-ui-redesign-implementation/task-4-fix-round-1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## Fixed review findings
- Restored the visible 48dp exit affordance and passed `SoloWorkbench.onExit` into `InterpretationScreen`.
- Limited `STARTING` to the state-machine-permitted `FINISH` action; pure tests now cover every `SessionPhase` action set.
- Added anonymous account role labels (`访客` / `正式用户` / `管理员`) while suppressing user email and all raw messages.
- Replaced raw entitlement expiry interpolation with fixed safe entitlement copy.
- Moved account service-settings routing to the dedicated `ENDPOINT_SETTINGS` destination through a pure navigation policy method; added policy tests.
- Added static highlighted microphone ring when Android animators are disabled; the infinite transition is not created in that path.
- Made transcript content scroll independently while action controls stay pinned and reachable.
- Expanded sensitive-output coverage across all account summary fields for token, DSN, key, password, session, email, URL/endpoint terms and untrusted expiry content.

## Scope
No ViewModel, coordinator, Cloud, Agent, audio, or token-storage implementation was modified.

## Verification
With the prescribed JDK 17 and Android SDK environment:
- Red phase: focused tests failed before mapper/summary/navigation implementations were updated.
- Focused tests: `InterpretationUiMapperTest`, `AccountUiStateTest`, and `ProductNavigationPolicyTest` passed.
- Full `testDebugUnitTest`, `compileDebugKotlin`, `lintDebug`, `assembleDebug`, and `git diff --check` passed.
