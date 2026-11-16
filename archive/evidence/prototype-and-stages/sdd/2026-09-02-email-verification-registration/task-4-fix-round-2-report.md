# Task 4 Fix Round 2 Report: conditionally invalidate exact reservation

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-4-fix-round-2-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## Fixed P1

- Changed the reservation writer contract to return the `registration_verifications` row UUID produced by the existing atomic store operation.
- Threaded that UUID through the email-registration service and HTTP callback into `InvalidateRegistrationVerification`.
- Store invalidation now requires both the canonical email and the exact reservation UUID: `DELETE ... WHERE id=$1 AND email=$2`. A late SMTP failure can therefore remove only its own still-current reservation.
- Retained prior behavior: reservation/limit/conflict handling precedes SMTP, sender failure is invalidated, and external operational outcomes remain generic `202`.

## Tests

Added a deterministic interleaving unit test:

1. A reserves then blocks in the SMTP sender.
2. The controlled clock advances by the resend delay.
3. B reserves and sends for the same canonical email.
4. A sender fails.
5. B's UUID/email reservation remains present and confirmable.

Executed successfully:

- `cd cloud-api && go test ./internal/auth ./internal/http ./... -count=1`
- `git diff --check`

No EC2, deployment, Postfix, or Android changes were made.
