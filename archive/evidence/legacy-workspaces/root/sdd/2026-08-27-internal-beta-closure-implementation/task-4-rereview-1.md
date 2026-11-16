> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-rereview-1.md`；原始 SHA-256 `6415776a88b89925726f7ecb73b79d6ceed6b35f8ab6fd5f74b3eb867ebfd04f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

Spec compliance: ❌
Task quality: Not approved

# Task 4 fix round 1 独立高风险复审

审查对象：`[旧临时路径]` commit `82c10ad`，修复范围 `f03e9e7..82c10ad`。

## 结论

不能批准本轮修复。CLI/runner 的主 target guard、脱敏错误、advisory lock 实现、legacy empty-ledger fail-closed、单 concurrent statement 限制、cross-user case、migration schema cleanup 与 Compose argv 修复均有实质进展；但仍有两个 Critical：business lifecycle 测试允许带不安全 fallback host 的 DSN，并可能在首选 `15432` 失败后连接宿主 `5432`；concurrent index 只验证同名索引的 `indisvalid`，未验证它就是 migration 要求的准确索引，却可写入 ledger。

此外，仓库 migration 的 checksum mismatch 测试实际上在文件发现阶段失败，未测试 checksum fail-fast；advisory-lock contention、legacy empty-ledger、invalid-index recovery 均没有真实 PostgreSQL 测试 case；事务控制拒绝仍漏掉 PostgreSQL 的 `END`/`ABORT` 等同义控制语句；一个新增测试还会在 `go test -v` 的 subtest 名中输出完整 synthetic DSN/password。

本轮没有连接 PostgreSQL、没有运行/停止 Compose。报告所述真实 PostgreSQL cases 仍为 skip。即使下述静态问题全部修复，也只能批准“代码修复”；在精确 `127.0.0.1:15432` dedicated DSN 上实际跑通 repository migrations、锁竞争、invalid-index recovery、existing-schema/empty-ledger 和 lifecycle 后，Task 4 才能完成真实 E2E 验收。

## 原审查逐项判定

### `task-4-review.md`

| Finding | 判定 | 复审说明 |
|---|---|---|
| C1 target guard | ADDRESSED | `Run` 在读取 migration directory、解析 pgx pool config、创建 pool/连接前调用 `ValidateDatabaseURL`；宿主 runner 只允许精确 `127.0.0.1:15432`，Compose 例外要求 marker、`/.dockerenv` 与精确 `postgres:5432`。错误不回显 DSN。注意 lifecycle test 仍有独立的 Critical 绕过，见下文。 |
| C2 invalid concurrent index 被记账 | NOT ADDRESSED | `indisvalid=false` 现在会阻止 ledger insert，但没有验证同名 valid index 的 table/columns/access method/predicate/uniqueness 等准确结构，也没有 invalid-index failure/recovery DB test。一个同名但定义错误的 valid index仍会被记账。 |
| I1 legacy Compose volume / empty ledger | ADDRESSED | 空 ledger 后检查已知 Cloud relations；检测到既有 schema 时拒绝重放，并在 README 记录 disposable rebuild 与 reviewed adoption 边界。真实 DB case 尚未验收。 |
| I2 缺少全局锁 | NOT ADDRESSED | session-level advisory lock 已覆盖 schema/ledger/checksum/apply 的代码路径，但没有并发 runner contention test；原 finding 要求的并发验证仍缺失，且本轮未真实运行。 |
| I3 未测试 repository migrations | NOT ADDRESSED | 测试已改为读取真实 `000001`–`000003`，但本轮未运行 PostgreSQL；更关键的是 checksum mismatch 子例因 concurrent 文件尾加 comment 被结构 regex 拒绝，未进入 ledger checksum 校验。 |
| I4 unsafe/invalid DSN 被 skip | NOT ADDRESSED | 明显 malformed/单 host 错误配置已由 skip 改为 fail；但 lifecycle helper 只检查 pgx 的首选 `Host/Port`，不检查 `Fallbacks`，仍会接受含 `127.0.0.1:5432` fallback 的不安全 DSN。 |
| I5 concurrent migration 部分执行语义 | ADDRESSED | discovery 仅允许一个受限、带终止分号的 unquoted `CREATE [UNIQUE] INDEX CONCURRENTLY` statement，混合/多语句结构 fail closed；SQL 成功、ledger 前中断可借当前 `IF NOT EXISTS` 重试。准确索引验证的独立 Critical 仍未解决。 |
| I6 cross-user 假阳性 | ADDRESSED | cross-user 写入改用尚无 usage 的 owner session，之后才独立验证同 session usage uniqueness。 |
| I7 transaction envelope 脆弱 | NOT ADDRESSED | comments、`BEGIN TRANSACTION/WORK`、dollar quotes 有改进，但 fail-closed 检测只识别 `BEGIN/COMMIT/ROLLBACK/START`，漏掉可结束事务的 `END`、`ABORT` 等形式。 |

### `task-4-review-retry.md`

| Finding | 判定 | 复审说明 |
|---|---|---|
| C1 runner 未拒绝不安全 target | ADDRESSED | CLI/runner 主路径 guard 位于任何 migration 文件读取及网络连接之前；精确宿主 target 与容器例外策略已实现。 |
| I1 invalid concurrent index | NOT ADDRESSED | invalid bit 会阻止记账，但“准确目标索引”未核验，且无 failure/recovery DB test。 |
| I2 无 migration 互斥锁 | NOT ADDRESSED | 锁实现存在且静态持锁范围正确，但要求的并发调用测试不存在、真实 contention 未运行。 |
| I3 未运行 repository migrations | NOT ADDRESSED | repository SQL 已接入测试设计，但真实 PostgreSQL 未运行，checksum mismatch case 还是 discovery false positive。 |
| I4 unsafe DSN 被 skip | NOT ADDRESSED | skip/fail 表面语义已改，但 lifecycle multi-host/fallback DSN 仍被接受并可连接不安全 target。 |
| I5 cross-user 假阳性 | ADDRESSED | owner FK 与 usage unique 已分离。 |
| I6 migration schema cleanup 顺序 | ADDRESSED | cleanup 注册顺序使 `DROP SCHEMA` 在 `conn.Close` 前执行，且 drop error 不再静默丢弃。 |
| I7 Compose DSN 位于 argv | ADDRESSED | migrate command argv 已不含 DSN；DSN 通过 service environment 注入。当前值仍会出现在 Compose/container environment metadata，但不再进入 argv。 |

Retry 的 Minor：M1 trial count 已 ADDRESSED；M2 parser robustness 仅部分改善，因 `END`/`ABORT` 漏检仍属 NOT ADDRESSED，并在本报告列为 Important。

## Critical

### C1. Lifecycle DSN guard 接受不安全 fallback，可能连接 `127.0.0.1:5432`

位置：`cloud-api/integration/postgres_business_test.go` 的 `isolatedPostgresTestDSN`。

该 helper 使用 `pgx.ParseConfig(url)` 后只检查：

```go
config.Host == "127.0.0.1" && config.Port == 15432 && config.Database != ""
```

pgx 对 multi-host URL 会把第一个地址放入 `Host/Port`，其余地址放入 `Fallbacks`。因此如下 DSN 可通过 helper：

```text
[历史开发连接已脱敏]
```

当 `15432` 不可连接时，`store.Open` 和 `pgxpool.New` 可继续尝试 fallback `5432`。这不是 skip，而是错误地接受并可能发起禁止的网络连接，直接违反 Global Constraints。query 参数形式的 multi-host override 也存在同类风险。

必须让 lifecycle test 复用同一个 fail-closed `ValidateDatabaseURL(..., false)`，并在创建任何 store/pool 前拒绝 multi-host、fallback、host/port/service override。增加不发起连接的负例，至少覆盖 URL multi-host 与 query override；错误不得包含 DSN。

### C2. 同名但定义错误的 valid index 会被当作 migration 成功并写 ledger

位置：`cloud-api/internal/migrate/runner.go` 的 `concurrentIndexIsValid` / `apply`。

当前验证只按 schema + index name 查询 `pg_index.indisvalid`。若 schema 内已存在一个同名、`indisvalid=true` 但属于错误 table、错误 columns、错误 predicate/access method/uniqueness 的索引，repository 的 `CREATE INDEX CONCURRENTLY IF NOT EXISTS` 会跳过创建；随后当前查询返回 true，runner 写入 `000002` ledger。此后 checksum/idempotence 路径永久跳过 migration，形成被 ledger 掩盖的 schema drift。

这不满足 progress ruling 的“exact, valid index state”。记账前必须验证 index 的准确目标关系与完整定义（例如基于受限 parser 的 expected structure 检查 catalog/`pg_get_indexdef`），而不只是 name + `indisvalid`；并增加“invalid 同名索引”和“valid 但定义错误的同名索引”两类真实 PostgreSQL case，均不得写 ledger。

## Important

### I1. Repository checksum mismatch 测试是 discovery false positive

位置：`cloud-api/internal/migrate/runner_test.go` 的 `TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration`。

测试通过在 `000002` 文件终止分号后追加：

```sql
-- checksum changed
```

但 `concurrentIndexStatement` 只允许 statement 后有 whitespace，禁止尾注释。因此 `discoverMigrations` 先返回 `invalid concurrent migration`，`Run` 根本没有加载既有 ledger，也没有执行 checksum mismatch fail-fast。`must_not_exist` 不存在只能证明 discovery 失败得早，不能证明 checksum ledger 语义。

应修改一份仍满足 parser 结构的已应用 migration（例如在普通 transaction migration 的合法 envelope 内作无害文本变化），并断言错误确为对应 version 的 checksum mismatch，且 later migration 未执行。

### I2. 三条关键 PostgreSQL 恢复/互斥路径没有测试 case

当前 test suite 没有设计以下真实 DB case：

1. 两个 runner 同时启动，第二个确实等待 advisory lock，并在首个完成后 no-op 成功；
2. 既有 Cloud relation + empty ledger 明确 fail，且不重放 `000001`；
3. 同名 invalid concurrent index 重试不写 ledger，以及受控修复后可恢复。

这与“测试已存在但本轮因 DSN 缺失而 skip”不同：这些 case 本身尚未编写。应先补测试，再在 dedicated DSN 上运行。当前 advisory lock 与 empty-ledger 实现可静态理解，但没有回归证据。

### I3. 事务控制 fail-closed 列表不完整

位置：`cloud-api/internal/migrate/runner.go` 的 `transactionalSQL`。

PostgreSQL 接受 `END [WORK|TRANSACTION]` 作为 `COMMIT`，接受 `ABORT [WORK|TRANSACTION]` 作为 `ROLLBACK`。当前 scanner 只把 `BEGIN`、`COMMIT`、`ROLLBACK`、`START` 视为 transaction control。未来 migration 中的 `END` 可在 runner-owned transaction 内提前提交 DDL，破坏“普通 SQL + ledger insert 同一事务”的保证；`ABORT` 等也不会按声明 fail closed。

应覆盖 PostgreSQL transaction-control 同义形式，或使用更明确的受限 SQL policy；至少增加 `END`、`ABORT`、`SAVEPOINT`/`RELEASE`、`PREPARE TRANSACTION` 的拒绝测试。

### I4. 新增负向测试会把完整 DSN/password 放进 verbose test output

位置：`cloud-api/internal/migrate/runner_test.go` 的 `TestRunRejectsUnsafeTargetBeforeReadingMigrationsOrConnecting`。

代码直接执行 `t.Run(databaseURL, ...)`，而 `databaseURL` 包含 `cloud:top-secret@...`。`go test -v` 会打印 subtest 名，从而输出完整 synthetic DSN 和密码。即使它不是生产 secret，也违反 Task brief 的“测试不打印 passwords/DSNs”，并建立了危险测试模式。

应使用不含凭据的固定 case name，将 DSN 仅保存在 case data 中。

### I5. Task 4 真实 PostgreSQL E2E 尚未验收

修复报告明确说明 `CLOUD_API_TEST_DATABASE_URL` 缺失，以下关键行为均未实际发生：真实 `000001→000002→000003`、index validity、ledger/checksum/idempotence、advisory contention、legacy empty-ledger、invalid-index recovery、business lifecycle。

这项是验收阻塞，不等同于断言所有实现均错误。静态问题修复后，可以先批准代码；但在安全 dedicated PostgreSQL 上获得新鲜 exit-0 证据前，不能把 Task 4 标记 complete，也不能宣称真实 PostgreSQL lifecycle 已通过。

## Minor

### M1. Business lifecycle fixtures 未清理

`TestPostgresBusinessLifecycle` 每次运行都会保留 user、trial/package entitlement、device、sessions、usage、code batch/redemption code 和 audit log。UUID/email 唯一避免直接冲突，但 dedicated DB 会持续积累跨用户与 trial 数据，削弱长期隔离性并污染后续 admin/数量类验收。

应采用可安全回滚/隔离的 fixture 策略，或提供明确、失败可见的 cleanup；不能静默忽略 audit append-only 与 FK 对 cleanup 顺序的影响。

### M2. Compose 改为 environment 后仍不是 production secret 注入

本轮要求的“DSN 不在 argv”已满足。当前 dev-only DSN 仍可通过 `docker compose config` 与 container environment metadata 查看；README 已警告不要把真实 DSN 放 argv，但正式部署仍应使用 secret mechanism。这不是本轮新的阻断项。

## 已确认的正向修复

- CLI 仍是 explicit up-only；runner 只发现 `.up.sql`，既有 migrations 未修改。
- runner host target validation 位于 migration 文件访问、pgx config/pool 与网络连接之前。
- runner/CLI 错误路径不拼接真实 DSN 或 driver error。
- advisory lock 的静态持锁范围覆盖 schema/ledger 创建、checksum 验证、全部 migration apply 与 ledger insert。
- ordinary migration 与 ledger insert 仍由同一 pgx transaction 管理；当前 repository `000001`/`000003` envelope 可被识别。
- concurrent migration 被限制为单个受限 statement；invalid/missing index 不写 ledger。
- empty ledger + 已知 Cloud schema fail closed；README 明示 legacy volume 不能静默重放。
- migration integration test 已改为读取 repository `000001`–`000003`，schema cleanup 顺序已修复。
- cross-user owner FK 与 usage uniqueness case 已分离；trial 数量精确断言为 1。
- Compose command argv 不再包含 DSN，依赖仍为 postgres healthy → migrate success → cloud-api，host mapping 保持 `127.0.0.1:15432:5432`。

## 验收边界

- **Fix round 1 code approval：不批准。** 需先修复两个 Critical 与上述 Important 静态/测试设计问题。
- **Task 4 PostgreSQL E2E acceptance：未验收。** 即使代码复审转为 Approved，也必须另外在精确、专用的 `127.0.0.1:15432` 数据库上运行并记录真实 migration/lifecycle 与高风险恢复测试，才能完成 Task 4。
