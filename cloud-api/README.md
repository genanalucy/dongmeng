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

The runner fails closed before connecting: a host invocation accepts only the
exact `127.0.0.1:15432` target. `localhost`, IPv6, Unix sockets, multi-host
DSNs, query-string host overrides, every other port, and every other host are
rejected without echoing the DSN. Compose is the sole narrow exception: its
one-shot `migrate` service receives `postgres:5432` through the controlled
`CLOUD_API_MIGRATE_DATABASE_URL` environment variable and sets
`CLOUD_API_MIGRATE_COMPOSE_SERVICE=1`. Never put a real DSN in command argv.

For real PostgreSQL tests, set `CLOUD_API_TEST_DATABASE_URL` only to the
isolated Cloud database listener at `127.0.0.1:15432`. Tests skip only when the
variable is absent. Once it is set, an unsafe or malformed target fails before
opening a connection; do not use the migration runner or test suite against
`127.0.0.1:5432`.

Existing volumes created by the former `docker-entrypoint-initdb.d` setup have
Cloud tables but no migration ledger. The runner deliberately fails closed
instead of replaying `000001` over them. For disposable development data,
remove and recreate the dedicated `cloud-postgres-data` volume through the
approved operator workflow. For data that must be retained, have an operator
first verify the schema matches all repository migration checksums, then restore
or adopt the matching ledger using a reviewed backup/recovery procedure; do not
manually mark unknown migrations as applied.

`docker compose up` runs the same runner after PostgreSQL becomes healthy and
before starting `cloud-api`. The published PostgreSQL host mapping remains
`127.0.0.1:15432:5432`.
