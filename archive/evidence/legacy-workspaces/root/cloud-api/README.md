> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `cloud-api/README.md`；原始 SHA-256 `f5f77a5dce3cae74b93fe581a9618745ce7e2bec8ec04173f00fdeab365263cf`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# cloud-api

Independent Go 1.23 HTTP API for accounts, entitlements, translation-session authorization, usage metadata, feedback metadata, and minimal administration. It uses chi v5, pgxpool, Argon2id, and JWT v5. It does **not** proxy PCM/WebSocket traffic and contains no provider API credentials.

## Development

```bash
cp .env.example .env
# replace both JWT secrets
set -a; . ./.env; set +a
make db-up
make run
```

The compose database applies `migrations/000001_initial.up.sql` only on first volume creation. For an existing database run `make migrate-up` with `DATABASE_URL` exported. Down migration is explicit via `make migrate-down` and is destructive.

## Verification

```bash
make fmt
make tidy
make test
make vet
```

Unit and HTTP tests use `internal/store.Memory`; they do not require PostgreSQL. Production startup requires `DATABASE_URL`, pings PostgreSQL before serving, and shuts down gracefully on SIGINT/SIGTERM.

## Security model

- Passwords use Argon2id with random 16-byte salt and encoded parameters; verification uses constant-time comparison.
- Refresh tokens and redemption codes are random, returned in plaintext only at issuance, and persisted as SHA-256 hashes.
- Refresh rotation detects reuse and revokes the token family.
- Registration and redemption are transactional in PostgreSQL. Code redemption locks the row before consuming it.
- CORS is an exact allowlist and rejects wildcard/unknown origins.
- Logs contain method, route template, status, duration, request ID, and IP only—never request bodies, Authorization, passwords, tokens, or codes.
- Feedback stores only `object_key`, never audio content; database and API enforce a maximum 14-day expiry.
- Admin bootstrap is manual; there is no default password.

See [docs/API.md](docs/API.md) for endpoint details.

## Migrations

The initial migration creates: `users`, `user_identities`, `password_credentials`, `refresh_tokens`, `device_installations`, `entitlements`, `redemption_code_batches`, `redemption_codes`, `translation_sessions`, `usage_records`, `feedback_consents`, `feedback_artifacts`, and `admin_audit_logs`, with UUID keys, constraints, indexes, and timestamps.
