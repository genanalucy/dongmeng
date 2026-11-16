> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-e2e-fix-review.md`；原始 SHA-256 `c489b2e75138bb4e2bbd686000241ff7355e904d72870570cfa624f14ca439f7`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 E2E failure fix review

## 审查范围与约束

- 只读审查 commit `a9af5c63266d9f82b94e54d1ba40a156d239df96`（父提交 `40164839efc94258bc9a0d93923ba25800369670`）。
- worktree：`[旧临时路径]`。
- 阅读了 `task-4-e2e-failure-fix-report.md`、当前 `cloud-api/internal/migrate/runner.go`、`runner_test.go`、integration tests、实际 migration SQL 与 Compose 配置。
- 未修改被审查源码，未连接数据库，未运行 Docker。
- commit 仅包含：
  - `cloud-api/internal/migrate/runner.go`
  - `cloud-api/internal/migrate/runner_test.go`
- worktree 中两份未跟踪报告不属于该 commit。

## 结论

**未发现 Critical 或 Important 问题。**

静态代码与离线测试证据支持本次诊断修复；commit 可以进入真实 PostgreSQL E2E 复验。真实数据库行为尚未在本次只读审查中观测，因此这不是“E2E 已通过”的结论。

## 逐项核验

### 1. catalog 不再对 `pg_node_tree` 做 `GROUP BY`

通过。

`concurrentIndexMatches` 仍用 `i.indpred IS NULL` 判断是否无 predicate，但 `GROUP BY` 已从 `i.indpred` 改为完全相同的布尔表达式 `(i.indpred IS NULL)`。因此不再要求 `pg_node_tree` 的 equality operator，同时保留 predicate fail-closed matcher。

### 2. parser 拒绝 schema-qualified index name

通过。

受限正则只允许 `INDEX [CONCURRENTLY ...] <identifier> ON [<schema>.]<table>`：index name 只有一个未限定 identifier，而 table 仍可 schema-qualified。`concurrentIndexTarget` 的捕获组映射已同步调整。

离线测试 `TestConcurrentIndexTargetRejectsSchemaQualifiedIndexName` 明确验证 `app.events_email_idx` 被识别为 concurrent-index 语句但解析失败；合法的 `events_active_idx ON app.events` 仍被接受。

### 3. PostgreSQL 15 gate

通过。

`Run` 在 acquire connection 后、advisory lock、schema/ledger DDL 和 migration 执行前运行：

```sql
SELECT current_setting('server_version_num')::integer
```

版本 `< 150000` 返回固定 `context=runner.check-server-version category=unsupported-server-version sqlstate=none`。只有 gate 通过后才会执行引用 PG15 字段 `pg_index.indnullsnotdistinct` 的 catalog 查询。

覆盖限制：现有离线测试只验证固定错误投影，未模拟/实测 PG14 runner 路径；不过控制流顺序和阈值本身清晰、正确，不构成 Important 阻塞。

### 4. fixture SQL 与失败断言

通过。

- fixture index name 改为未限定形式，table 保持 schema-qualified，符合受限 parser 与 PostgreSQL “index 位于其 table schema” 的行为。
- NULLS NOT DISTINCT fixture 使用 PG15+ 正确语序：

```sql
CREATE UNIQUE NULLS NOT DISTINCT INDEX events_email_idx ON "schema".events(email)
```

- server version fixture read 使用 integer cast，避免把 `SHOW` 的 text 直接 Scan 到 integer。
- duplicate-data 下的 `CREATE UNIQUE INDEX CONCURRENTLY` 不再接受任意错误：必须投影为 `postgres-server/23505`，随后查询 catalog 并要求留下同名 `indisvalid=false` index。这能防止由语法错误、连接错误等产生假阳性。

### 5. `databaseFailure` 脱敏与 SQLSTATE

通过。

生产 DB 边界均不再返回/格式化底层数据库错误。`databaseFailure` 只存储：

- 固定调用点提供的 `Context`；
- 数字 migration version（适用时）；
- 白名单 `Category`；
- 经 `^[0-9A-Z]{5}$` 校验的 `PgError.Code`，否则为 `none`。

`Error()` 只格式化这些字段；未保存 DSN、SQL、`PgError.Message/Detail/Hint`、连接错误文本或 generic driver error。离线测试覆盖合法 SQLSTATE、malformed SQLSTATE、result decode 与 generic driver error，并使用含 secret 的底层错误验证输出不泄露。合法五位 SQLSTATE（例如 `42883`、fixture 的 `23505`）得到保留。

## 验证证据

本次在未设置 `CLOUD_API_TEST_DATABASE_URL`、不连接数据库的条件下执行并通过：

```text
go test -count=1 ./internal/migrate
go test -tags=integration -count=1 ./internal/migrate ./integration
go vet ./internal/migrate ./integration
```

另观察到 `go test -count=1 ./...` 所有列出的 package 均通过；真实 PostgreSQL cases 因无 DSN 只走 skip/离线路径。`git diff a9af5c6^ a9af5c6 --check` 通过。

## 是否可以重新运行真实 `127.0.0.1:15432` E2E

**可以，且应当重新运行；但须满足运行前置条件。** 本次审查未连接该地址，不能确认服务当前是否存在或就绪。

前置条件：

1. `127.0.0.1:15432` 是获批的专用测试 PostgreSQL；
2. server version 为 PostgreSQL 15+；
3. `CLOUD_API_TEST_DATABASE_URL` 是该精确 host/port 的合法 DSN；
4. 对 `integration/TestPostgresBusinessLifecycle`，目标数据库 public schema 已应用当前 repository migrations；migration runner tests 自己使用随机隔离 schema。

建议复验命令：

```bash
CLOUD_API_TEST_DATABASE_URL='<approved-postgres-dsn-for-127.0.0.1:15432>' \
  go test -tags=integration -count=1 ./internal/migrate ./integration
```

判定标准：不能只看 package exit code；应确认 dedicated PostgreSQL cases 实际运行而非因 DSN 缺失而 skip，尤其包括 invalid concurrent index 的 `23505`、`indisvalid=false`、catalog matcher、repair/recovery 与 lifecycle case。

## Findings

- Critical：无。
- Important：无。
- Remaining validation gap：真实 PG15+ E2E 尚未执行；这是下一步验证要求，不是本 commit 中已确认的代码缺陷。
