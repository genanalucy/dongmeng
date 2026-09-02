# Task 4 Report: email-verification registration HTTP contract

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
