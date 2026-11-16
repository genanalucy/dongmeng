> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-fix-round-5-report.md`；原始 SHA-256 `1eaac61b8c265ad24a3925b952a08e4bdf68b62234c91bcf13dba286a1e06227`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 5 report

## Scope

- Worktree: `[旧临时路径]`
- Baseline: `b1aa16d75ff66e6a80ac708dc5e183da6e156f65`
- No database connection was made.
- No Compose service was started or stopped. `docker compose config` was used only for static configuration validation.
- No subagent was used.

## Root cause

`ValidateDatabaseURL` previously rejected only selected connection-target query keys. pgx accepts unrecognised URL keys as startup `RuntimeParams`; consequently `standard_conforming_strings=off` could alter PostgreSQL ordinary-string lexical semantics after the migration scanner had accepted the migration.

## Changes

- Restricted migration DSNs to the audited minimal URL-query set: only exactly one `sslmode=disable` is accepted; all other query keys and values are rejected.
- Parsed the DSN with `pgx.ParseConfigWithOptions` and `pgconn.ParseConfigOptions.ConnStringAllowedKeys` restricted to URL structural keys plus `sslmode`.
- After parsing, fail closed unless the effective main connection is exactly `127.0.0.1:15432`, or the controlled Compose target `postgres:5432`; required zero fallbacks and zero runtime parameters.
- Rechecked the parsed `pgxpool` configuration immediately before pool construction using the same target/runtime constraints.
- Added no-DB regressions for `standard_conforming_strings=off`, `options`, an unknown runtime key, and `PGOPTIONS`; all assert the unsafe sentinel without exposing the secret.
- Added the required pre-connection regression fixture containing a normal backslash string and internal `COMMIT`; the DSN with `standard_conforming_strings=off` is rejected before the migration is read or any connection is opened.
- Extended integration lifecycle validator tests, which reuse `migrate.ValidateDatabaseURL`, with the same unsafe DSNs.

## Verification

Passed with `CLOUD_API_TEST_DATABASE_URL` unset:

```text
go test -count=1 ./...
go test -tags=integration -count=1 ./integration
go vet ./...
go build ./cmd/migrate
go build ./cmd/cloud-api
```

Additional focused RED/GREEN evidence:

```text
# Before implementation:
go test -count=1 ./internal/migrate -run '^TestValidateDatabaseURLFailsClosed$'
# failed: standard_conforming_strings, options, and unknown query cases were accepted

# After implementation:
go test -count=1 ./internal/migrate -run '^(TestValidateDatabaseURLFailsClosed|TestValidateDatabaseURLRejectsRuntimeParametersFromEnvironment|TestRunRejectsUnsafeTargetBeforeReadingMigrationsOrConnecting)$'
# passed
```

Static checks passed:

```text
docker compose config
git diff --check
```

The Go build commands temporarily produced `cloud-api/cloud-api` and `cloud-api/migrate`; both were removed before the final artifact/status check. The pre-existing untracked `task-4-fix-round-2-report.md` was not modified or staged.
