# Task 4 Fix Round 3 Report: per-write reservation identity

## Fixed P1

- Added migration `000006_registration_verification_reservations`.
  - It adds `reservation_id uuid`, backfills existing rows with `gen_random_uuid()`, then enforces `NOT NULL`.
- Kept the stable `registration_verifications.id` primary key unchanged.
- Every `RequestRegistrationVerification` insert and same-email upsert now creates a fresh `reservation_id`; the store returns it to the service.
- Sender-failure invalidation compares canonical email plus that per-write `reservation_id`, so a late failure can only remove the reservation created by its own request.
- Updated domain, store, service, HTTP callback, contract tests, and migration catalog expectations.

## Integration evidence

Added a real PostgreSQL integration test with the isolated `127.0.0.1:15432` target:

1. A reserves and blocks in sender.
2. Clock advances beyond the resend delay.
3. B overwrites the same email reservation and sends successfully.
4. A sender fails and conditionally invalidates its old reservation.
5. B confirms successfully through the real PostgreSQL store.

## Validation

Passed:

- `cd cloud-api && go test ./internal/auth ./internal/http ./internal/store ./internal/migrate -count=1`
- `cd cloud-api && go test ./... -count=1`
- `cd cloud-api && CLOUD_API_TEST_DATABASE_URL=<isolated 127.0.0.1:15432 DSN> go test -tags integration ./integration -run TestPostgresRegistrationVerificationLateSenderFailurePreservesNewerReservation -count=1`
- `git diff --check`

A Docker Compose migrate attempt could not download a Go module because outbound access to `proxy.golang.org` timed out; the locally cached Go toolchain then applied migrations and ran the isolated integration successfully. No EC2, deployment, Postfix, or Android changes were made.
