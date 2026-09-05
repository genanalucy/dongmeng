module github.com/dngmeng/cloud-api

go 1.25.0

require (
	github.com/coder/websocket v1.8.14
	github.com/go-chi/chi/v5 v5.3.0
	github.com/golang-jwt/jwt/v5 v5.2.1
	github.com/google/uuid v1.6.0
	github.com/jackc/pgx/v5 v5.10.0
	github.com/wenlng/go-captcha-assets v1.0.7
	github.com/wenlng/go-captcha/v2 v2.0.5
	golang.org/x/crypto v0.36.0
)

require (
	github.com/golang/freetype v0.0.0-20170609003504-e2365dfdc4a0 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	golang.org/x/image v0.16.0 // indirect
	golang.org/x/sync v0.17.0 // indirect
	golang.org/x/sys v0.31.0 // indirect
	golang.org/x/text v0.29.0 // indirect
)

replace github.com/go-chi/chi/v5 => ./internal/http/third_party/chi
