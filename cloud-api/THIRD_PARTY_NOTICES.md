# Third-party notices

Runtime module dependencies of `cloud-api` and their licenses. Versions are
pinned in `go.mod` / `go.sum`; license texts live in the Go module cache or the
paths below.

## Direct dependencies

| Module | Version | License |
|---|---|---|
| `github.com/wenlng/go-captcha/v2` | v2.0.5 | Apache-2.0 |
| `github.com/wenlng/go-captcha-assets` | v1.0.7 | Apache-2.0 |
| `github.com/coder/websocket` | v1.8.14 | ISC |
| `github.com/golang-jwt/jwt/v5` | v5.2.1 | MIT |
| `github.com/google/uuid` | v1.6.0 | BSD-3-Clause |
| `github.com/jackc/pgx/v5` | v5.10.0 | MIT |
| `golang.org/x/crypto` | v0.36.0 | BSD-3-Clause |

`github.com/go-chi/chi/v5` is vendored through the `go.mod` `replace`
directive into `internal/http/third_party/chi`, which carries its own MIT
`LICENSE` file.

The slide captcha images served by `GET /api/v1/auth/captcha` are rendered by
`github.com/wenlng/go-captcha/v2` (Apache-2.0) from the photographed
backgrounds and tile overlays published by the same author in
`github.com/wenlng/go-captcha-assets` (Apache-2.0).

## Indirect dependencies (introduced by the captcha modules)

| Module | Version | License |
|---|---|---|
| `github.com/golang/freetype` | v0.0.0-20170609003504-e2365dfdc4a0 | Freetype License (FTL) or GPLv2, at your choice |
| `golang.org/x/image` | v0.16.0 | BSD-3-Clause |

Other indirect dependencies (`jackc/*`, `golang.org/x/sync`, `golang.org/x/sys`,
`golang.org/x/text`) follow the same MIT / BSD-3-Clause Go ecosystem licenses as
their upstream projects.
