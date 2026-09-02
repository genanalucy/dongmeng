# Task 4 Fix Round 1 Report: reserve before SMTP

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
