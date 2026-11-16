> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-e2e-failure-diagnosis.md`；原始 SHA-256 `f7cc411933b4f6c8d88962ddcff05ceaab4de86fa6bbc621476affbc97193770`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 真实 PostgreSQL E2E 失败只读诊断

## 诊断范围与约束

- 目标 worktree：`[旧临时路径]`，当前 HEAD `4016483`。
- 证据：`[旧临时路径]`、`cloud-api/internal/migrate/runner.go`、`runner_test.go`、仓库 `migrations/*.up.sql`、`docker-compose.yml` 以及本地 pgx v5.10.0 源码。
- 本次未修改目标 worktree，未提交，未启动或停止 Docker，未连接 PostgreSQL，未派遣子代理。
- 唯一写入是本诊断报告。

## 结论摘要

三个失败不是连接失败，也不是同一个根因：

1. `TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration` 已成功连接并执行完 `000001`，在 `000002` 阶段失败。仓库 `000002` 本身在 PostgreSQL 17 上语法正确，runner 也确实在事务外执行它；静态缺陷位于随后用于验证 index 的 catalog SQL：它对 `pg_node_tree` 类型的 `i.indpred` 做 `GROUP BY`。标准 PostgreSQL 没有该类型的 equality operator，因此查询会失败。当前 runner 把底层错误完全替换为 `apply migration 000002`，所以日志看不到预计的 server SQLSTATE `42883`。
2. `TestRunRejectsMismatchedOrInvalidConcurrentIndexBeforeLedgerAndRecovers` 在进入 runner 前就失败。fixture 使用了 PostgreSQL 不接受的 schema-qualified index name：`CREATE UNIQUE INDEX "schema".events_email_id_idx ...`。index 名不能在 `CREATE INDEX` 的 name 位置带 schema；index 会自动创建在被索引表的 schema 中。预计为 SQLSTATE `42601`。
3. `TestRunRejectsNullsNotDistinctConcurrentIndexBeforeLedger` 的 `SHOW server_version_num` 返回 `text`，却直接 `Scan(&int)`。pgx v5.10 的 `TextCodec` 不把 text 扫描到 `*int`，因此这是 client-side result decode/scan 错误，没有 SQLSTATE。修复这一点后，当前 NULL fixture 还会因两个 SQL 语法问题失败：同样 schema-qualified 了 index name，并把 `NULLS NOT DISTINCT` 放在了列列表之后；PostgreSQL 15+ 的正确位置是 `CREATE UNIQUE NULLS NOT DISTINCT INDEX ...`。

因此，本轮输出证明真实连接成功，但不能仅凭现有日志读取精确 server error；精确错误被实现主动丢弃。上述根因由静态语法/catalog/API 行为确定，建议先加脱敏结构化诊断，再做一次受控 E2E 来确认预计 SQLSTATE。

## 逐项根因

### 1. `apply migration 000002`

失败日志：

```text
runner_test.go:283: first repository migration run: apply migration 000002
```

执行路径：

```text
Run
  -> apply(candidate 000002)
     -> connection.Exec(CREATE INDEX CONCURRENTLY ...)
     -> concurrentIndexMatches(...)
        -> catalog QueryRow(...)
     -> ledger INSERT
```

`000002_refresh_tokens_expiry_idx.up.sql` 为：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS refresh_tokens_expiry_idx
    ON refresh_tokens (expires_at, id);
```

这段 SQL 对当前 Compose 声明的 `postgres:17-alpine` 是合法的：

- 只有一个 concurrent index statement；
- runner 将其分类为 `OutsideTransaction=true`；
- `search_path` 被设置到测试 schema，因此未限定 schema 的表和 index 会落到该 schema；
- session advisory lock 不等于 SQL transaction，不违反 `CREATE INDEX CONCURRENTLY` 的事务限制。

真正的静态缺陷在 `concurrentIndexMatches`：

```sql
...
       i.indpred IS NULL
...
GROUP BY ..., i.indpred
```

`pg_index.indpred` 是 `pg_node_tree`。该内部表示类型没有可供 `GROUP BY` 使用的 equality operator。预计服务端错误类别是 `postgres-server`、SQLSTATE `42883`（undefined function/operator），典型语义为无法识别 `pg_node_tree` 的 equality operator。

安全最小修正方向：不要 group 原始 `pg_node_tree`。例如让 SELECT 与 GROUP BY 都只使用布尔表达式：

```sql
(i.indpred IS NULL)
...
GROUP BY ..., (i.indpred IS NULL)
```

或者将 key-column 聚合放入独立 lateral aggregate，使外层完全不需要 `GROUP BY`。前者改动最小；后者结构更清晰但范围更大。

失败发生在 concurrent index DDL 成功之后也是可能且最符合静态路径的状态：index 已存在，但 ledger 尚未写入。由于 migration 使用 `IF NOT EXISTS`，修正 catalog 查询后重跑可验证现有 index 并补 ledger；这正是 runner 预期的 crash-recovery 路径。测试 cleanup 会按 schema `CASCADE` 清掉该状态。

### 2. `create same-attribute INCLUDE boundary mismatch index`

失败日志：

```text
runner_test.go:465: create same-attribute INCLUDE boundary mismatch index
```

实际拼接结果等价于：

```sql
CREATE UNIQUE INDEX "migration_test_...".events_email_id_idx
ON "migration_test_...".events(email) INCLUDE (id)
```

问题在 index name，而不是 `INCLUDE`：PostgreSQL 的 `CREATE INDEX` name 不能写成 `schema.index_name`。应只限定 table：

```sql
CREATE UNIQUE INDEX events_email_id_idx
ON "migration_test_...".events(email) INCLUDE (id)
```

index 会自动处于 table 所属 schema，测试 schema 的随机化仍可隔离并发测试。当前失败预计为 server SQLSTATE `42601`（syntax_error）。

同一测试稍后的“制造 invalid concurrent unique index”也有相同问题：

```sql
CREATE UNIQUE INDEX CONCURRENTLY "schema".events_email_id_idx ...
```

该语句当前确实会返回 error，但只是 name 语法错误，因此 `err != nil` 会形成假阳性，根本不会留下测试所需的 invalid index。修正为未限定 index name 后，重复 email 应触发 SQLSTATE `23505`，并留下 same-name invalid concurrent index，runner 才真正覆盖 `indisvalid=false` 的恢复语义。

runner 的 constrained parser 也错误地接受可选的 schema-qualified index name，并包含“index schema 必须匹配 table schema”的分支。该语法形态 PostgreSQL 本身不接受。parser 应只允许未限定 index name；若 table 是 schema-qualified，则 expected index schema 从 table schema 推导，否则从 runner 的 default schema/search path 推导。

### 3. `read PostgreSQL server version`

失败日志：

```text
runner_test.go:500: read PostgreSQL server version
```

当前代码：

```go
var serverVersion int
err := conn.QueryRow(ctx, "SHOW server_version_num").Scan(&serverVersion)
```

`SHOW`/`current_setting` 的结果类型是 PostgreSQL `text`。pgx v5.10.0 的 `TextCodec.PlanScan` 支持 string/bytes/text scanner，不支持把 text 直接 scan 到 Go `*int`；因此这不是 `*pgconn.PgError`，没有 SQLSTATE。安全分类应为：

```text
context=fixture.read-server-version category=result-decode sqlstate=none
```

最小修正是让 PostgreSQL 显式返回 integer：

```sql
SELECT current_setting('server_version_num')::integer
```

然后继续 scan 到 `int`。也可以先 scan 到 string 再 `strconv.Atoi`，但会多一个 client parse 分支。

修复 scan 后，当前 fixture SQL 仍不合法：

```sql
CREATE UNIQUE INDEX "schema".events_email_idx
ON "schema".events(email) NULLS NOT DISTINCT
```

需同时修正为 PostgreSQL 15+ 语法：

```sql
CREATE UNIQUE NULLS NOT DISTINCT INDEX events_email_idx
ON "schema".events(email)
```

否则下一次会得到 server SQLSTATE `42601`，仍无法进入 runner 的 `indnullsnotdistinct` mismatch 检查。

## 错误为何被泛化吞掉

当前存在两层主动丢失：

### Runner 层

`Run` 在多个 pgx 边界使用固定 `errors.New(...)` 替换原错误，例如：

- `open migration database`；
- `acquire migration database connection`；
- `acquire migration advisory lock`；
- `create migration schema` / `create migration ledger`；
- `read migration ledger`；
- `inspect existing cloud schema`。

最关键的是 apply loop：

```go
if err := apply(...); err != nil {
    return fmt.Errorf("apply migration %s", candidate.Version)
}
```

这里既没有 `%w`，也没有保存结构化 category/SQLSTATE。`apply` 返回的 `*pgconn.PgError`、`pgx.ScanArgError`、context/timeout 或 driver error 全部不可再由 `errors.As/Is` 取得。

这种做法避免直接打印可能含敏感字段的原始错误，但过度泛化：它同时丢掉了本来安全且用于定位所必需的 SQLSTATE 和操作阶段。

### Test helper 层

真实 DB helpers 也普遍使用固定文本：

```go
t.Fatal("create same-attribute INCLUDE boundary mismatch index")
t.Fatal("read PostgreSQL server version")
```

它们同样没有 `%v`，所以安全但完全不可诊断。另一方面，直接改成 `t.Fatalf("...: %v", err)` 又会把 `ConnectError`、`PgError.Message/Detail/Hint/Where` 等未经审查地写进日志，不应这样修。

## 最小安全诊断方案

### 1. 只输出白名单字段

增加一个小型、结构化、脱敏的 DB failure 表示；输出仅包含：

- `context`：代码中固定枚举/常量，不接受 DSN、SQL、schema UUID、query args 或 server message；
- `category`：固定枚举；
- `sqlstate`：仅当 `errors.As(err, *pgconn.PgError)` 成功时，输出经过 `^[0-9A-Z]{5}$` 校验的 `PgError.Code`；否则明确为 `none` 或省略。

建议 category 最少覆盖：

```text
postgres-server
result-decode
connect
network
context-canceled
context-deadline
timeout
no-rows
driver
```

其中 `pgx.ScanArgError` 应归类为 `result-decode`；`pgconn.Timeout(err)`、`context.Canceled`、`context.DeadlineExceeded` 应先于 generic driver 分类；server SQLSTATE 只读 `PgError.Code`，绝不输出整个 `PgError`。

安全输出示例：

```text
context=runner.verify-concurrent-index migration=000002 category=postgres-server sqlstate=42883
context=fixture.create-include-boundary-index category=postgres-server sqlstate=42601
context=fixture.read-server-version category=result-decode sqlstate=none
```

这些字段不包含 DSN、host/user/database、SQL 文本、schema、table、column、constraint、Message、Detail、Hint、Where、InternalQuery、File 或 Routine。

### 2. Runner 不应再丢失结构化原因，也不应拼接原始原因

不建议简单改为：

```go
fmt.Errorf("apply migration %s: %w", version, err)
```

因为其 `Error()` 会拼入底层原始文本，现有 `t.Fatalf("...: %v", err)` 可能直接泄露未经审查的 server/connection detail。

更安全的最小方案是在每个 pgx 边界立即把原错误投影成脱敏值，例如：

```go
type databaseFailure struct {
    Context  string
    Category string
    SQLState string
}
```

`databaseFailure.Error()` 只格式化这三个白名单字段；分类完成后不把原始 `err` 保存进返回值。测试通过 `errors.As` 或只读取该安全错误验证 category/SQLSTATE。这样即使调用者 `%v` 打印，也不会带出原始错误。

至少给 `apply` 拆出以下固定 context，才能区分 `000002` 的 DDL、catalog 验证和 ledger 写入：

```text
runner.execute-concurrent-index
runner.verify-concurrent-index
runner.insert-concurrent-ledger
runner.execute-transactional-migration
runner.insert-transactional-ledger
runner.commit-transactional-migration
```

migration version 由已验证的纯数字文件 version 提供，可作为单独白名单字段；不要把 migration SQL 或文件内容放进错误。

### 3. 测试 helper 统一安全失败格式

直接 fixture 操作使用类似 helper：

```go
fatalPostgres(t, "fixture.create-include-boundary-index", err)
fatalPostgres(t, "fixture.read-server-version", err)
```

helper 只调用上述 classifier 并打印白名单结果，绝不出现 `%v`/`%+v` 原错误。`Run` 返回的 `databaseFailure` 也用同一格式断言。

应先用完全离线的 synthetic errors 测 classifier：

- 构造 `pgconn.PgError{Code: "42601"}`，并在 Message/Detail/Hint 中放入假 DSN/secret，断言输出只有 category/SQLSTATE/context；
- 构造 `pgx.ScanArgError`，断言 `result-decode` 且没有伪造 SQLSTATE；
- 覆盖 timeout/context/connect/default driver 分支；
- 断言输出不包含 `postgres://`、password、测试 secret、SQL、server message/detail/hint。

这组测试无 DSN、无数据库即可运行，并能先证明诊断格式不会泄密。

### 4. 一次最小受控复现

完成诊断投影但尚未修 SQL 时，在已由控制器批准、环境变量已注入的 `127.0.0.1:15432` 专用数据库上，仅运行三个 focused cases；命令本身不要展开或打印变量值：

```bash
cd [旧临时路径]
go test -count=1 -v ./internal/migrate \
  -run '^(TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration|TestRunRejectsMismatchedOrInvalidConcurrentIndexBeforeLedgerAndRecovers|TestRunRejectsNullsNotDistinctConcurrentIndexBeforeLedger)$'
```

预期第一轮安全输出：

| case | context | category | SQLSTATE |
|---|---|---|---|
| repository `000002` | `runner.verify-concurrent-index`, migration `000002` | `postgres-server` | 预计 `42883` |
| INCLUDE fixture | `fixture.create-include-boundary-index` | `postgres-server` | 预计 `42601` |
| version read | `fixture.read-server-version` | `result-decode` | `none` |

只有该次受控输出才能把“预计 SQLSTATE”升级为实际观测；当前日志因错误丢弃不能做到这一点。

修完静态问题后再重跑同一 focused 命令，额外要求：

- duplicate fixture 的 intended failure 必须是 `postgres-server/23505`，而不是任意 `err != nil`；
- invalid index 必须确实存在且 `indisvalid=false`，然后 runner 拒绝 ledger；
- NULLS NOT DISTINCT fixture 必须创建成功，runner 因 definition mismatch 拒绝 ledger；
- repository run 应通过 `000001`–`000003`、幂等和 checksum fail-fast。

## PostgreSQL 版本与 catalog 假设审查

### 明确存在的版本假设

- Compose 固定 `postgres:17-alpine`，所以仓库当前环境目标是 PostgreSQL 17。
- `pg_index.indnullsnotdistinct` 只存在于 PostgreSQL 15+。runner 的 catalog query 无条件引用它，因此 runner 实际已经隐式要求 PostgreSQL 15+。
- `runner_test.go` 虽试图在 NULL fixture 前读取版本并 skip `<150000`，但这不能保护 runner 本身：repository test 会更早执行包含 `indnullsnotdistinct` 的 catalog query。若专用 DSN 指向 PostgreSQL 14 或更低，runner 将在 parse catalog query 时以预计 SQLSTATE `42703`（undefined_column）失败。
- `indnkeyatts`/INCLUDE catalog 语义也要求较新的 PostgreSQL，但对声明的 PG17 无问题。

最小安全选择二选一：

1. 如果产品明确只支持 PG15+：在 runner 建连接后、执行 schema/migration 前，用 `SELECT current_setting('server_version_num')::integer` 做 capability gate，并返回脱敏 `unsupported-server-version`；同时在 README/测试写明最低版本。
2. 如果要支持 PG14 及更早：不能在同一静态 SQL 中直接引用不存在的 `indnullsnotdistinct`；需按已读取的 server version 选择不同 catalog query，或采用兼容的 catalog 表示方式，并把旧版本实际值视为 `false`。

仅依赖测试 skip 不足以解决 runner 的版本兼容性。

### 当前 catalog 定义校验的其他假设

在 PG15+ 上，下列约束方向合理且 fail closed：

- `indisvalid=true`；
- `indnkeyatts == expected columns` 且 include count 为 0；
- default btree opclasses/default collations/default sort order；
- 无 predicate；
- `indnullsnotdistinct`、`indimmediate` 与 expected 一致；
- index/table schema、table、method、uniqueness、column order 精确匹配。

当前主要 catalog SQL 错误就是对 `pg_node_tree` 的 group；多数组件 `unnest` 的 vector cast/ordinality 方案在目标 PG17 上是可行的。

## 仓库 migration SQL 审查

- `000001_init.up.sql`：日志已到达 `000002`，说明本轮真实执行中 `000001` 及其 `pgcrypto`、tables、indexes、PL/pgSQL trigger function 已成功。未见目标 PG17 语法问题。
- `000002_refresh_tokens_expiry_idx.up.sql`：目标 PG17 语法正确，且必须在 transaction 外；runner 的分类正确。失败更符合执行后的 catalog verification query，而非 migration 文件本身。
- `000003_business_lifecycle.up.sql`：尚未被第一个 case 执行到；静态看其事务 envelope、`ADD COLUMN IF NOT EXISTS`、constraints、tables 与普通 indexes 对 PG17 合法。当前日志不能宣称其真实执行已通过。
- runner constrained parser 的 schema-qualified index-name 分支与 PostgreSQL grammar 不一致，应移除，避免未来 migration 在 discovery 阶段被错误接受、到 server 才以 `42601` 失败。

## 建议修复顺序

1. 先实现并离线测试脱敏 `databaseFailure`/test helper，确保无 DSN、无原始 server 文本。
2. 受控 focused E2E 一次，记录 category/context/SQLSTATE，确认 `42883`、`42601`、scan/no-SQLSTATE 推断。
3. 修 catalog query，不再 group `i.indpred`。
4. 修 fixture index name；并让 intended failed concurrent build 明确断言 `23505`，防止“任意错误即通过”的假阳性。
5. 修 server version query 的类型，并修正 `CREATE UNIQUE NULLS NOT DISTINCT INDEX` 语法。
6. 移除 runner parser 对 schema-qualified index name 的错误支持。
7. 明确 PG15+ gate，或实现低版本 catalog 分支。
8. 重跑三个 focused cases，再跑完整 migration/integration checks；任何 category/SQLSTATE 不符时停止，不以固定泛化错误继续猜测。

## 风险边界

- 当前日志没有原始错误，故 `42883` 是高置信静态推断而不是已观测值；报告未把它伪称为实际输出。
- 直接把原始 pgx error 加到测试日志不是安全诊断方案。
- 在 catalog query 修正前，真实 schema 可能处于“concurrent index 已创建、ledger 未记录”的可恢复中间态；不得手工补 ledger，必须让修正后的 runner 精确验证后再记录。
- 在 fixture SQL 修正前，invalid-index recovery case 存在假阳性路径，不能作为恢复语义已覆盖的证据。
