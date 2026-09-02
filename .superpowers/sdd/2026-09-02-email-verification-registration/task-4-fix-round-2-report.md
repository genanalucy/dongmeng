# Task 4 Fix Round 2 Report: conditionally invalidate exact reservation

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
