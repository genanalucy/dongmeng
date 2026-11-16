> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-brief.md`；原始 SHA-256 `d96fbf4255b9b6a39fbfcc04fc4606c2ca2ea8ef930942768a809ca3aa6eda56`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

## Task 4: Make Cloud API database setup repeatable and validate business lifecycle

**Files:**
- Create: `cloud-api/cmd/migrate/main.go` or `cloud-api/internal/migrate/runner.go` with a focused CLI entry point.
- Create: migration runner tests under `cloud-api/internal/migrate/`.
- Modify: `cloud-api/Makefile`, `cloud-api/README.md`, `docker-compose.yml` only to document/use the runner.
- Test: `cloud-api/integration/postgres_business_test.go` and new migration tests.

**Consumes:** `migrations/000001_init.up.sql`, `000002_refresh_tokens_expiry_idx.up.sql`, `000003_business_lifecycle.up.sql`.

**Produces:** repeatable, ordered, checksummed migration execution that supports `CREATE INDEX CONCURRENTLY` outside a transaction and does not use destructive down migrations for normal operation.

- [ ] **Step 1: Write a failing isolated PostgreSQL migration test**

Use an explicitly supplied test DSN (never default to production/local developer DSN). Test behavior:
1. runner creates a schema migration ledger with version, checksum and applied timestamp;
2. runs `000001`, `000002`, `000003` in lexical order;
3. executes `000002` outside a transaction;
4. repeated run is a no-op;
5. checksum mismatch fails before applying later migrations.

Skip with a clear message only when the dedicated test DSN is absent; normal unit tests must remain runnable without secrets.

- [ ] **Step 2: Run test to prove missing runner**

```bash
cd [旧临时路径]
go test ./internal/migrate -count=1
```

Expected: fail until runner exists, or compile failure identifying the missing package.

- [ ] **Step 3: Implement focused runner**

Implement a runner that:
- embeds or reads only repository migration files in lexicographic numeric order;
- computes SHA-256 checksum of each SQL file;
- uses a dedicated ledger table;
- wraps ordinary migration SQL in one transaction per migration;
- detects `CREATE INDEX CONCURRENTLY` and executes that migration outside a transaction;
- rejects every `.down.sql` in normal `up` command;
- accepts DSN only from an explicit environment variable/flag without logging it.

Do not introduce an ORM or a new migration dependency.

- [ ] **Step 4: Add business lifecycle PostgreSQL E2E cases**

Extend existing PostgreSQL integration coverage to prove:

```text
register → one 3-day trial
redeem normalized code → one 365-day stacked entitlement
create session → second active session returns conflict
write usage once → second usage for same session conflicts
end/revoke → next session can be created
cross-user usage/session association is rejected
```

All fixtures must generate unique email/install/session values and must not print passwords, JWTs, DSNs, or full SQL errors containing credentials.

- [ ] **Step 5: Run Cloud checks against isolated test setup**

```bash
cd [旧临时路径]
go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
go build ./cmd/migrate
git diff --check
```

Run PostgreSQL migration and business integration cases only after confirming their DSN targets the dedicated Cloud test database at port `15432`, never `5432`.

- [ ] **Step 6: Commit Cloud lifecycle/migration work**

```bash
cd [旧临时路径]
git add -- cloud-api migrations docker-compose.yml
git diff --cached --check
git commit -m "feat(cloud): add repeatable migration validation"
```
