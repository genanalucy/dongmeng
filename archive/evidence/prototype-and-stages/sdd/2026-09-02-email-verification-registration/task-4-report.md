# Task 4 Report: email-verification registration HTTP contract

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-02-email-verification-registration/task-4-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## Implemented

- Added public `POST /api/v1/auth/registration-verifications` and `/confirm` handlers.
- Requests use strict JSON, validate required fields, invoke the existing email-registration service/store/sender contracts, and return `202 {"retry_after_seconds":60}` for successful, conflict, limited, and delivery/storage outcomes to reduce registration-email enumeration.
- Confirmation returns `201` with the existing public user and trial-entitlement shapes. Invalid, expired, consumed, and fifth-attempt failures share `400 verification_failed`; final registration conflicts remain `409`.
- Client IP accepts a single `X-Forwarded-For` value only when the TCP peer is loopback (local Caddy). Direct peers ignore spoofed headers; malformed forwarded values fall back to the peer IP.
- Deprecated `POST /api/v1/auth/register` now returns `410 registration_verification_required`, without calling immediate registration. Login, tokens, and existing-user flows are unchanged.
- When `EMAIL_VERIFICATION_ENABLED=false`, the new endpoints are fail-closed with `503 registration_verification_not_enabled`; no SMTP sender is constructed or invoked.
- Corrected one pre-existing Task 3 trailing whitespace in `cloud-api/cmd/cloud-api/main.go`.

## Tests

- `cd cloud-api && go test ./internal/http -count=1`
- `cd cloud-api && go test ./... -count=1`
- `git diff --check`

All passed.

## Concerns

- SMTP delivery is intentionally attempted before persisting the verification record, following the committed `EmailRegistrationService` contract. Failed delivery therefore leaves no usable code. The generic `202` response remains deliberate anti-enumeration behavior.
- No EC2/Postfix deployment or Android files were changed.
