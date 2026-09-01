# Cloud API

Base URL: `http://127.0.0.1:8080`. All JSON errors are `{ "error": "code", "request_id": "..." }`; every response has `X-Request-ID`. Business responses use `Cache-Control: no-store`. Bearer credentials, refresh tokens, request bodies, query strings and client IPs are excluded from access logs.

## Public

| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/v1/auth/register` | `email`, `password` | `201 user, trial_entitlement`; creates the user and fixed 3-day trial atomically |
| POST | `/api/v1/auth/login` | `email`, `password` | access/refresh token pair |
| POST | `/api/v1/auth/refresh` | `refresh_token` | rotated pair; replay revokes the complete refresh family |
| GET | `/healthz`, `/api/v1/health` | | liveness |
| GET | `/readyz`, `/api/v1/ready` | | PostgreSQL readiness |
| GET | `/api/v1/config` | | non-secret service metadata |

## Authenticated user

All endpoints below require exactly one `Authorization: Bearer <access JWT>`.

| Method | Path | Body / Result |
|---|---|---|
| POST | `/api/v1/auth/logout` | `refresh_token`; revokes its family |
| GET | `/api/v1/users/me` | current user |
| GET | `/api/v1/users/me/devices` | devices observed from persisted `install_id` values |
| GET | `/api/v1/account/overview` | safe username, latest entitlement status and caller-owned aggregate usage; never exposes email or phone |
| GET | `/api/v1/account/usage?limit=1..50&offset>=0` | caller-owned paged session summaries; both pagination parameters are required |
| PATCH | `/api/v1/account/identity` | `username`, `email`, `phone`, `current_password`; canonicalizes identities, requires the current password, and returns only the public user profile |
| GET | `/api/v1/entitlements/current` | active, non-revoked entitlement |
| POST | `/api/v1/redemptions` | `code`; one-time canonicalized code redemption creates a fixed 365-day entitlement |
| POST/GET | `/api/v1/translation-sessions` | POST requires an opaque client `install_id`; one active session per user; GET lists caller-owned sessions |
| POST | `/api/v1/translation-sessions/{sessionID}/end` | ends caller-owned session |
| POST | `/api/v1/translation-sessions/{sessionID}/revoke` | revokes caller-owned session |
| POST | `/api/v1/usage-records` | `session_id`, non-negative `audio_seconds`, `characters`; owner FK prevents cross-user writes |
| POST | `/api/v1/feedback/consents` | `granted` |
| POST | `/api/v1/feedback/artifacts` | `consent_id`, `object_key`; consent ownership is enforced, retention is exactly 14 days |
| GET | `/api/v1/feedback/artifacts/{artifactID}` | caller-owned artifact only |

## Admin

管理员端点需要有效 access JWT 且 JWT `role=admin`。缺少、无效或已禁用用户的 token 返回 `401`；有效但非管理员 token 返回 `403`。所有 mutation 追加不可变 audit record。

### 列表读取

`GET /api/v1/admin/users?q=<email>&limit=<n>&offset=<n>` 返回：

```json
{"users":[{"id":"uuid","email":"admin@example.com","role":"admin","created_at":"2026-01-02T03:04:05Z"}]}
```

`q` 可选（最多 254 字符）；`limit` 默认 `50`、最大 `100`，无效值使用默认；`offset` 默认 `0`，负值按 `0` 处理。

`GET /api/v1/admin/audit-logs?limit=<n>&offset=<n>` 返回：

```json
{"audit_logs":[{"id":"uuid","admin_id":"uuid","action":"user.disabled","target_type":"user","target_id":"uuid","metadata":{},"created_at":"2026-01-02T03:04:05Z"}]}
```

审计 `limit`/`offset` 使用与用户列表相同的默认和上限。`target_id` 可选。服务端 HTTP DTO 固定把审计 `metadata` 投影为 `{}`；绝不把存储层的开放 metadata、secret 或任意嵌套值暴露给浏览器。

### 其他管理员端点

- `POST /api/v1/admin/users/{userID}/disable`
- `GET /api/v1/admin/users/{userID}/translation-sessions?limit=&offset=`
- `GET /api/v1/admin/users/{userID}/usage-records?limit=&offset=`
- `POST /api/v1/admin/users/{userID}/entitlements` — grant a stacked 365-day entitlement.
- `POST /api/v1/admin/users/{userID}/entitlements/{entitlementID}/revoke`
- `POST /api/v1/admin/code-batches` — `{ "name": "…", "count": 1..1000 }`; response includes plaintext codes once only.

## Translation JWT / main Agent contract

`POST /api/v1/translation-sessions` returns a short-lived token signed with the distinct session HMAC key:

- `alg=HS256`, `typ=translation_session`, one configured `iss` and `aud`;
- `sub == user_id`, plus `user_id`, `session_id`, `install_id`, `entitlement_id`, `scope=translation`, `iat`, `nbf`, `exp`, `jti`;
- `install_id` is provided by the client and stored on the session/device; **it is never an entitlement ID**.

Use it only in the Agent WebSocket protocols `translation.v1` and `translation.jwt.<token>`, with matching `start.userId`, `start.sessionId`, and `start.installId`.

## Runtime configuration

`TOKEN_ISSUER`, `ACCESS_TOKEN_AUDIENCE`, `TRANSLATION_SESSION_AUDIENCE`, `ACCESS_TOKEN_HS256_KEY`, and `TRANSLATION_SESSION_HS256_KEY` are mandatory. Audiences and keys must be distinct; both keys require at least 32 bytes. Production secret injection must supply the keys—do not commit or log them.
