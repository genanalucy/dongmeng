> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `cloud-api/docs/API.md`；原始 SHA-256 `95f2891e0142b39bfea6a6104458b38d3ba69ff8cba98bc52dd0046151d16c06`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Cloud API v1

All responses are JSON except successful `204` responses. Errors use:

```json
{"error":{"code":"invalid_request","message":"..."}}
```

Authenticated routes require `Authorization: Bearer <access_token>`. Browser requests must use an exact configured Origin.

## Authentication

- `POST /v1/auth/register` — `{ "email", "password" }`; creates account plus 3-day trial atomically and returns access/refresh tokens.
- `POST /v1/auth/login` — `{ "email", "password" }`.
- `POST /v1/auth/refresh` — `{ "refresh_token" }`; rotates the token. Reuse of a rotated token revokes the entire family.
- `POST /v1/auth/logout` — `{ "refresh_token" }`; idempotently revokes it.
- `POST /v1/auth/sms/verify` — `{ "phone", "code" }`; development-only fixed code `000000`. Returns `501` outside `APP_ENV=development`. It does not create a production phone identity.

Refresh tokens are random bearer secrets returned only when issued; only SHA-256 hashes are persisted.

## Entitlements and translation

- `POST /v1/redemptions` — `{ "code" }`; consumes a code once, atomically, and creates a 365-day package entitlement.
- `POST /v1/translation-sessions` — `{}`; requires an active entitlement and returns a ~5-minute JWT. Claims include `iss`, `aud`, `sub`, `jti`, `session_id`, `entitlement_id`, and `scope=translation`.
- `POST /v1/usage-records` — `{ "translation_session_id", "audio_seconds", "characters" }`; the session must belong to the authenticated user.

The API only issues authorization metadata. It never proxies PCM, audio, WebSocket traffic, or provider credentials.

## Feedback

- `POST /v1/feedback/consents` — `{ "granted": true|false }`.
- `POST /v1/feedback/artifacts` — `{ "consent_id", "object_key", "expires_at" }`; requires a granted consent owned by the user. `object_key` is a relative safe key and expiration is at most 14 days.
- `GET /v1/feedback/artifacts/{uuid}` — only the owner can read metadata.

Artifacts store object keys only; no audio bytes are accepted or stored.

## Administrator

Requires current persisted `users.role=admin` (not merely a stale JWT claim).

- `POST /v1/admin/redemption-code-batches` — `{ "name", "count" }`, count 1–1000. Returns plaintext codes exactly once; stores only hashes; duration is fixed at 365 days; writes an audit log in the same transaction.
- `GET /v1/admin/users?limit=50&offset=0`.
- `GET /v1/admin/audit-logs?limit=50&offset=0`.

There is no default administrator or password. Bootstrap one manually after a normal registration:

```sql
UPDATE users SET role='admin', updated_at=now() WHERE id='<user uuid>';
```

## Operational

- `GET /healthz`
- Maximum JSON request body defaults to 1 MiB.
- JSON decoding rejects unknown fields and trailing JSON values.
- Request IDs are returned as `X-Request-ID`.
- In-memory route/IP window limiting is process-local and intentionally a replaceable skeleton; use a shared limiter at larger scale.
