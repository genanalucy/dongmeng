# Cloud API operations

## Apply schema migrations

Use the focused migration runner. It accepts only the explicit `up` command,
never invokes down migrations, records a SHA-256 checksum ledger, and does not
log the database URL.

```bash
cd cloud-api
CLOUD_API_MIGRATE_DATABASE_URL="$EXPLICIT_DATABASE_URL" \
  go run ./cmd/migrate up
```

`--database-url` is an equivalent explicit input. The runner reads the
repository migration directory by default; use `-migrations-dir` only when the
repository migrations are mounted at another path, such as Compose.

For real PostgreSQL tests, set `CLOUD_API_TEST_DATABASE_URL` only to the
isolated Cloud database listener at `127.0.0.1:15432`. Tests skip when it is
absent or points anywhere else. Do not use the migration runner or test suite
against `127.0.0.1:5432`.

`docker compose up` runs the same runner after PostgreSQL becomes healthy and
before starting `cloud-api`. The published PostgreSQL host mapping remains
`127.0.0.1:15432:5432`.
