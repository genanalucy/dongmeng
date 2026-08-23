# Translation session WebSocket authorization

Session authorization is optional at the Agent boundary. A nil `server.Options.SessionVerifier` preserves the current loopback development behavior. Production callers enable it by constructing `sessionauth.Verifier` and injecting it explicitly.

## Browser-safe token transport

When authorization is enabled, clients connect with two values in `Sec-WebSocket-Protocol`:

```text
translation.v1, translation.jwt.<compact-JWT>
```

Browser example:

```js
new WebSocket(url, ["translation.v1", `translation.jwt.${token}`]);
```

The Agent extracts the credential before upgrade, removes the credential-bearing value from the request, and negotiates only `translation.v1`. It never echoes the JWT subprotocol. Tokens must not be placed in the URL or logged; reverse proxies and tracing systems must redact the entire incoming `Sec-WebSocket-Protocol` header.

With authorization enabled, the first `start` message must include the context bound by the signed claims:

```json
{
  "type": "start",
  "sessionId": "123e4567-e89b-12d3-a456-426614174000",
  "userId": "user-123",
  "installId": "install-456",
  "mode": "s2s",
  "sourceLanguage": "zh",
  "targetLanguage": "en",
  "targetAudioFormat": "pcm",
  "targetAudioRate": 16000
}
```

The Agent verifies `sub/user_id/session_id/install_id` against this message before starting the Provider session. Missing protocol credentials fail the HTTP upgrade with `401`; invalid or mismatched JWTs return `TRANSLATION_AUTH_INVALID` and close without touching the Provider.

## Runtime configuration

Authorization defaults to disabled. To enable it, the process caller supplies:

| Name | Required | Meaning |
|---|---:|---|
| `TRANSLATION_SESSION_AUTH_ENABLED` | yes | Exact value `true` enables verification |
| `TRANSLATION_SESSION_HS256_KEY` | yes | HS256 key, at least 32 bytes |
| `TRANSLATION_SESSION_ISSUER` | yes | Exact expected issuer |
| `TRANSLATION_SESSION_AUDIENCE` | yes | Exact sole expected audience |
| `TRANSLATION_SESSION_CLOCK_SKEW_SECONDS` | no | Non-negative; default `30` |
| `TRANSLATION_SESSION_MAX_LIFETIME_SECONDS` | no | Positive; default `300` |

The Agent does not provide defaults for trust identity or key material. Incomplete enabled configuration fails startup. Secret values must be supplied by the deployment secret mechanism and must never be committed or printed.
