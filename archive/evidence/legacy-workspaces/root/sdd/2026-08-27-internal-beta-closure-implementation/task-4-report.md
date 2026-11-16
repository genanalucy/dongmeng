> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-report.md`；原始 SHA-256 `5d1f4514099e3e416d256f034aef790d9d0513bda529a45c360669c4054f78ba`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4：Cloud API repeatable migration 与业务生命周期报告

## 范围与提交边界

- 实施 worktree：`[旧临时路径]`
- 实施范围：`cloud-api/cmd/migrate/`、`cloud-api/internal/migrate/`、`cloud-api/integration/postgres_business_test.go`、`cloud-api/README.md`、`docker-compose.yml`。
- 未修改任何既有 `migrations/*.sql`，普通运行路径不调用 down migration。
- 未执行、停止、迁移或修改宿主 `127.0.0.1:5432`。Compose 的 host mapping 保持 `127.0.0.1:15432:5432`。

## TDD：RED → GREEN

### RED

先添加 focused CLI test 后运行：

```bash
cd [旧临时路径]
go test ./cmd/migrate -count=1
```

首次失败：`migrationConfig` 未定义，证明 explicit up-only CLI 尚未实现。

迁移 runner 的早期 RED 命令：

```bash
go test ./internal/migrate -count=1
```

首次失败：`discoverMigrations`、`Config`、`Run`、`quoteIdentifier` 未定义，证明 runner 尚未存在。

### GREEN

实现后重新运行：

```bash
go test ./cmd/migrate ./internal/migrate -count=1
go test -tags=integration ./integration -count=1
```

均 exit 0。无 DSN 时真实 PostgreSQL cases 按设计清晰 skip，不把 skip 伪称为数据库验收。

## Runner 架构

- `cmd/migrate up` 是唯一支持的命令；无命令、非 `up`（包括 `down`）均拒绝。
- DSN 仅从显式 `--database-url` 或 `CLOUD_API_MIGRATE_DATABASE_URL` 取得；无默认连接字符串，不打印该值。
- `internal/migrate` 无 ORM、无新的 migration dependency，仅使用已有 `pgx`。
- 只发现 `.up.sql`，按文件名的字典序执行；`.down.sql` 从 normal up 路径排除，CLI 也不支持 down。
- 在 `${schema}.schema_migrations` 记录 `version`、SHA-256 `checksum`、`applied_at`。每轮开始时先验证所有已记录版本 checksum；不匹配或未知 version 均在任何后续 migration 前失败。
- 普通 migration 由 runner 使用一个 transaction；现有 SQL 的外层 `BEGIN/COMMIT` 被移除后在 runner transaction 内执行。
- 解析 `CREATE [UNIQUE] INDEX CONCURRENTLY`（跳过注释和 string literal）后，将该 migration 在 transaction 外执行；其 ledger 写入随后单独完成。
- 重复 run 对已记录且 checksum 一致的 migration 不执行 SQL。
- Compose 不再通过 `docker-entrypoint-initdb.d` 隐式初始化，而是以一次性 `migrate` service 在 Postgres healthcheck 后执行同一 runner，再启动 Cloud API。

## DSN 安全策略

- 迁移 CLI 从不回退到 `DATABASE_URL` 或任意默认 DSN；DSN 只由上述 migration env/flag 显式输入。
- PostgreSQL migration 与 lifecycle test 只读取 `CLOUD_API_TEST_DATABASE_URL`；不加载 `.env`，不输出值、密码、JWT 或包含 URL 的 driver error。
- 测试在变量缺失时 skip；即使变量存在，也只有解析为 `127.0.0.1:15432` 才会连接，其他 host/port 清晰 skip。因此本轮没有连接 `127.0.0.1:5432`。

## PostgreSQL migration / lifecycle 验证

真实 DB 覆盖（在安全 DSN 可用时）：

- runner ledger version/checksum/applied timestamp、`000001→000002→000003` 字典序策略、`000002` concurrent index 事务外、幂等执行、checksum mismatch fail-fast；
- register 创建一个固定 3-day trial；
- canonical（lowercase + trim）redeem 生成从 trial expiry 开始的固定 365-day stacked entitlement；
- 单活 session 冲突；每 session 一条 usage 冲突；end/revoke 后均能创建下一 session；跨用户 usage/session association 被拒绝。

本轮环境中 `CLOUD_API_TEST_DATABASE_URL` 未设置。以下实际命令只产生设计预期的 skip，未执行真实 PostgreSQL：

```bash
cd [旧临时路径]
go test -tags=integration -count=1 -v ./integration
go test -count=1 -v ./internal/migrate
```

输出明确显示：`TestPostgresBusinessLifecycle` 与 `TestRunRecordsChecksumsIsIdempotentAndFailsBeforeLaterMigration` 因变量未设置而 `SKIP`。因此真实 database migration/lifecycle 仍需在显式、已核验的 `127.0.0.1:15432` test DSN 下执行。

## 全量验证命令

已执行且 exit 0：

```bash
cd [旧临时路径]
go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
go build ./cmd/migrate

cd [旧临时路径]
git diff --check
docker compose config --quiet
```

`go build` 的临时 `cloud-api` / `migrate` binaries 已删除。还执行了 forbidden-artifact 检查；未把 `.env*`、缓存、APK、binary、`local-reset-admin` 纳入暂存。

## Commit

已选择性暂存 Task4 的 7 个 Cloud/Compose 文件，并在提交前执行：

```bash
cd [旧临时路径]
git diff --cached --check
git commit -m "feat(cloud): add repeatable migration validation"
git show --check --stat --oneline HEAD
git status --short
```

提交：

```text
f03e9e7 feat(cloud): add repeatable migration validation
```

因将 DSN 非法值同样转为安全 skip 的最后测试调整，提交以 `--amend --no-edit` 更新为上列 hash。提交后 `git show --check` 无输出；`git status --short` 无输出。未暂存或提交报告文件本身（它位于 worktree 外的指定 SDD 记录路径）。

## 风险与关注项

1. 因无 dedicated test DSN，真实 PostgreSQL runner 与 lifecycle E2E 本轮是安全 skip，不能视为已通过实际数据库验收。
2. concurrent-index migration 本体成功后、ledger 写入前若进程异常，下次会重试该 SQL；现有 `000002` 使用 `IF NOT EXISTS`，故可安全重试。未来含 concurrent index 的 migration 应同样具备幂等 SQL。
3. Compose migration DSN 仅在 Docker service network 内使用 `postgres:5432`；宿主暴露仍固定为 `127.0.0.1:15432`。

## Fix round 1（C1/C2、I1–I7）

### 修复内容

- **C1 / I4（target guard 与测试 DSN 语义）：** `Run` 在读取 migration 文件、创建 pool 或发起网络连接前执行 fail-closed URL 校验。宿主仅允许精确 `127.0.0.1:15432`；拒绝 `localhost`、IPv6、socket/keyword DSN、multi-host、host/port/service query override、空 database 及其他 host/port。`postgres:5432` 仅在设置 Compose 标记且检测到容器 `/.dockerenv` 的 runner 进程内允许；宿主环境的标记本身不构成绕过。错误不包含 DSN 或凭据。`CLOUD_API_TEST_DATABASE_URL` 未设置才 skip；一旦设置为 malformed/不安全 target 则连接前 fail。
- **I2（并发 runner）：** 在 schema/ledger 创建、ledger checksum 检查和全部 apply/ledger 写入之前获取 session-level PostgreSQL advisory lock，并在连接释放前解锁；连接或锁获取失败立即失败，不能继续。
- **C2 / I5（concurrent index）：** 只接受一个非 quoted、单语句 `CREATE [UNIQUE] INDEX CONCURRENTLY [IF NOT EXISTS] name ON table(columns);` migration；其他混合语句/不确定结构在发现阶段 fail。执行 SQL 后、ledger insert 前从 `pg_catalog.pg_index` 验证目标 index 存在且 `indisvalid=true`；缺失/invalid/查询错误均不写 ledger、不继续。真实 PostgreSQL 的 concurrent failure/recovery 仍留待专用 DSN 阶段。
- **I1（legacy volume）：** 空 ledger 时检查 Cloud schema 的已知业务 relation；若已有 schema 则 fail closed，明确拒绝重放 `000001`。README 记录 disposable volume 的受控重建路径及保留数据时应通过审核的备份/ledger adoption 过程。
- **I3 / I6：** migration integration test 改为真实仓库 `000001`–`000003` 的发现、checksum、ledger、有效 concurrent index、幂等和 checksum fail-fast 路径；schema cleanup 在 connection close 前执行并报告清理错误。
- **I7：** 事务 envelope 支持带注释的 `BEGIN [TRANSACTION|WORK] ... COMMIT`，任何其他事务控制语句 fail closed；SQL word scanner 跳过注释、string 与 dollar-quoted body。
- **lifecycle review：** trial 查询精确断言一条；cross-user usage 使用尚无 usage 的 session，从而独立命中 owner FK；usage uniqueness 另行断言。
- **Compose / README：** migrate DSN 从 command argv 移到受控 `CLOUD_API_MIGRATE_DATABASE_URL`；command 不再含完整 DSN，保持 healthcheck → migrate success → Cloud API 依赖、host mapping `127.0.0.1:15432:5432`。

### TDD 证据

新增 DSN/runner/CLI 边界测试首先在中断修复前因已删除的 `Config.AllowComposeServiceTarget` 引用而编译失败，错误位于 `cmd/migrate/main_test.go:54`。接口一致性修复后执行：

```bash
cd [旧临时路径]
go test ./cmd/migrate ./internal/migrate -count=1
```

结果 exit 0。无 DB 测试覆盖精确 host policy、DSN 不泄露、`Run` 在 migration discovery/连接前拒绝 `127.0.0.1:5432` 和未受控 `postgres:5432`、concurrent migration 结构边界、事务控制边界和真实仓库 migration 发现路径。

### Fix round 1 验证

以下命令均已实际执行且 exit 0：

```bash
cd [旧临时路径]
env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
go build ./cmd/migrate
env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 -v ./internal/migrate ./integration

cd [旧临时路径]
docker compose config --quiet
git diff --check
git diff --cached --check
git ls-files | grep -E '(^|/)(\\.env($|\\.)|node_modules/|\\.gradle/|app/build/|local\\.properties$|local-reset-admin/|cloud-api/cloud-api$|.*\\.apk$)' && exit 1 || true
```

`go build` 后删除生成的 `cloud-api` 与 `migrate` 文件。integration-tag 输出中 `TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration` 与 `TestPostgresBusinessLifecycle` 因变量未设置而明确 `SKIP`，没有连接数据库。

安全负向验证（命令整体通过是因为外层 `test $? -ne 0` 断言内层必须失败）：

```bash
cd [旧临时路径]
CLOUD_API_TEST_DATABASE_URL='[历史开发连接已脱敏] \
  go test -tags=integration -count=1 ./internal/migrate ./integration; test $? -ne 0
```

内层测试以固定安全错误失败；没有连接、未打印 DSN。未启动、停止或连接 Compose/PostgreSQL。

### Commit

```text
82c10ad fix(cloud): fail closed migration runner
```

提交前已 `git reset` 清除旧暂存，并以白名单仅暂存 6 个 Cloud/Compose 源码、测试、README 文件；`git show --check --stat --oneline HEAD` 无检查错误，工作树干净。报告在 worktree 外，未纳入提交。

### 未运行的 DB 验收

专用 `CLOUD_API_TEST_DATABASE_URL` 未提供，未执行真实 PostgreSQL migration、advisory-lock contention、existing-schema empty-ledger、invalid concurrent-index recovery 或 lifecycle E2E。它们必须在后续经核验的精确 `127.0.0.1:15432` 专用 DB DSN 阶段执行，不能由本轮 skip 替代。

## Final E2E acceptance (2026-08-28)

After the migration runner and lifecycle test fixes, a disposable PostgreSQL 17 container was created with random in-process credentials and bound only to `127.0.0.1:15432`. The test process received its DSN through `CLOUD_API_TEST_DATABASE_URL`; neither the DSN nor the password was emitted. The container was automatically removed after the command.

The following completed with exit 0 (evidence: `[旧临时路径]`):

```bash
go test -tags=integration -count=1 -v ./integration
go test -count=1 -v ./internal/migrate
go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
go build ./cmd/migrate
```

This is real PostgreSQL evidence for repository migrations `000001→000003`, ledger/checksum/idempotence, advisory-lock serialization, existing-schema/empty-ledger fail-closed behavior, concurrent-index mismatch/invalid recovery, `NULLS NOT DISTINCT` mismatch rejection, and the business lifecycle: trial, normalized redemption/stacked entitlement, active session, one usage record, terminal-session release, and cross-user rejection. The final independent review is `task-4-final-review.md` (Critical=0, Important=0; approved).
