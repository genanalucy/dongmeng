# Task 4 Fix Round 1 Report: reserve before SMTP

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-4-fix-round-1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## Fixed P1

- `EmailRegistrationService.RequestVerification` now persists the verification reservation before calling the SMTP sender.
- The HTTP callback delegates that reservation to the existing atomic `RequestRegistrationVerification` store operation, so cooldown, email/IP limits, and formal-user conflicts reject before any SMTP call.
- On SMTP failure, the service invokes the injected invalidation callback; the HTTP callback removes the pending verification through `InvalidateRegistrationVerification`. Existing rate-limit accounting remains in the store and is not deleted by invalidation.
- Public handler behavior remains generic `202` for conflict, limited, and delivery/storage outcomes.

## Tests

Added/updated tests verify:

- reservation happens before sender invocation;
- conflict and rate-limit request paths make zero sender calls;
- sender failure invalidates the already reserved verification;
- existing generic HTTP response behavior remains intact.

Executed successfully:

- `cd cloud-api && go test ./internal/auth ./internal/http -count=1`
- `cd cloud-api && go test ./... -count=1`
- `git diff --check`

No EC2, Postfix, deployment, or Android changes were made.
