> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-rereview-2.md`；原始 SHA-256 `0c60697dd7a7d4d5eb1e8769e629e65a4667618908db2c05b892b1a3821f9a58`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

Spec compliance: ❌
Task quality: Not approved

# Task 4 fix round 2 独立高风险复审

审查对象：`[旧临时路径]` commit `2b33e77`，修复范围 `82c10ad..2b33e77`。

## 结论

本轮解决了 lifecycle helper 的 DSN validator 复用、multi-host/override 拒绝、checksum mismatch case 的 false positive、关键 PostgreSQL recovery/lock case 缺失、verbose test name 泄露、常见 transaction-control 同义词漏检及成功路径 fixture cleanup。

但仍不能批准代码：concurrent-index catalog matcher 还不是“精确 index definition”验证。它只比较 table/schema、method、unique、聚合后的 attribute 名、predicate 和 `indisvalid`，没有区分 key columns 与 `INCLUDE` columns，也未比较 opclass、collation、排序方向和 NULL ordering。一个同名、valid、但定义不同的 index 仍可通过 matcher 并写入 ledger。另有一个 Important：外层事务 envelope 被识别后没有重新拒绝 envelope 内的额外 transaction control，仍可能提前提交 DDL 而不记 ledger。

本轮未连接 PostgreSQL、未启动/停止 Compose。新增 dedicated PostgreSQL cases 的设计有明显进展，但由于 `CLOUD_API_TEST_DATABASE_URL` 缺失，真实 `127.0.0.1:15432` migration/lifecycle E2E 仍未执行。即使静态问题修复并获得代码批准，Task 4 也只能在真实 PostgreSQL cases 新鲜通过后完成 E2E 验收。

## Round 1 剩余项逐项判定

| Finding | 判定 | 复审说明 |
|---|---|---|
| C1 lifecycle DSN fallback/override | ADDRESSED | `isolatedPostgresTestDSN` 在 `store.Open` / `pgxpool.New` 前调用 `validateIsolatedPostgresTestDSN`，后者直接复用 `migrate.ValidateDatabaseURL(dsn, false)`。URL multi-host、query `host`/`port`/`hostaddr`/`service`/`servicefile` override 均由同一 validator fail closed；缺失 DSN 才 skip。负向命令确认 multi-host case 在连接前失败，输出未包含 synthetic password。 |
| C2 精确 concurrent index catalog 验证 | NOT ADDRESSED | 已从“只查同名 index 的 `indisvalid`”提升到 table/schema/method/unique/columns/predicate 比较，但仍非精确定义。见 Critical C1。 |
| I1 checksum mismatch false positive | ADDRESSED | mutation 改为给普通 migration `000001_init.up.sql` 追加注释；该文件仍可 discovery，测试明确要求错误包含 `migration checksum mismatch for version 000001`，并断言 `000004` 未执行。 |
| I2 lock/legacy/invalid recovery case 缺失 | ADDRESSED BY TEST DESIGN; NOT EXECUTED | 已新增 advisory-lock 等待后 no-op、existing Cloud relation + empty ledger fail-closed、wrong valid same-name index、invalid same-name index、修复后恢复并仅记账一次等 PostgreSQL cases。因无 dedicated DSN，本轮全部未真实运行。锁 case 的固定 `100ms` 观察窗口还存在调度型假阳性风险，见 Minor。 |
| I3 transaction controls | PARTIALLY ADDRESSED | `END`、`ABORT`、`SAVEPOINT`、`RELEASE`、`PREPARE`、`SET` 已加入拒绝列表，dollar-quoted body 仍忽略；但完整 envelope 内额外 control 未被拒绝，见 Important I1。 |
| I4 verbose test 泄露 DSN/password | ADDRESSED | DSN 已从 `t.Run` 名称移到 case data，subtest 使用固定安全名称；错误使用固定文本/sentinel。此次 multi-host 负向验证输出未出现 DSN/password。 |
| I5 真实 PostgreSQL E2E | NOT ADDRESSED / DEFERRED | repository migrations、catalog verification、lock contention、legacy recovery、invalid-index recovery和 lifecycle 都有 case，但没有在真实 `127.0.0.1:15432` PostgreSQL 上执行。 |

## Spec compliance

### 静态符合 / 已有测试设计

- lifecycle PostgreSQL helper 真实复用 runner 的 `ValidateDatabaseURL`，没有保留较弱的独立 host/port parser。
- runner guard 仍位于 migration file discovery 与任何 pgx pool/network 操作之前；宿主仅允许精确 `127.0.0.1:15432`，Compose exception 仍限制为容器 marker + `/.dockerenv` + `postgres:5432`。
- URL multi-host 与 target override fail closed；无 DSN 才 skip，存在但非法的 DSN fail。
- repository `000001`、`000002`、`000003` 被真实 migration case 读取；case 设计覆盖顺序、事务外 concurrent index、ledger 数量、index valid、幂等 timestamp 和 checksum fail-fast。
- global session advisory lock 的实现范围覆盖 schema/ledger 建立、ledger 校验和全部 apply；新增 contention/no-op case。
- existing Cloud schema + empty ledger 继续 fail closed；新增真实 DB case 断言不重放后续 DDL。
- outside-transaction migration 仍被限制为单个受支持的 `CREATE [UNIQUE] INDEX CONCURRENTLY` statement；invalid/mismatch 时不写 ledger，修复后可重试的 case 已加入。
- ordinary migration SQL 与 ledger insert 仍由同一个 pgx transaction 管理。
- cross-user owner case 与同 session usage uniqueness 保持独立。
- test/subtest 名、runner/CLI 固定错误文本未发现 DSN、password、JWT 或原始 driver error 泄露。
- lifecycle 成功路径 cleanup 顺序为 redemption code → batch → users，随后由 FK cascade 清理 entitlement/device/session/usage；cleanup 在 raw pool 关闭前运行，不尝试修改 append-only audit log。

### 不符合 / 未证明

- progress ruling 要求“expected parsed index relation/definition”的精确 catalog 验证；当前 matcher 仍可接受近似但错误的 valid index。
- runner 对普通 migration 的 transaction-control fail-closed 仍有 envelope 内额外 control 绕过。
- brief/设计要求 Cloud API 在真实 PostgreSQL 上完成 migration 与 business lifecycle；本轮无真实 DB 执行证据。

## Critical

### C1. Catalog matcher 会把 key/INCLUDE 等定义不同的 valid index 当成精确匹配并写 ledger

位置：`cloud-api/internal/migrate/runner.go` 的 `concurrentIndexMatches` / `sameConcurrentIndexDefinition`。

查询对 `unnest(i.indkey)` 的全部 attribute 做 `array_agg`，但不读取或比较 `i.indnkeyatts`。PostgreSQL 的 `indkey` 同时包含 key attributes 与 included attributes。因此，对期望：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON events (email, id);
```

预先存在的错误 index：

```sql
CREATE INDEX idx ON events (email) INCLUDE (id);
```

会得到相同的 `Columns=[email,id]`、table、method、unique、predicate 与 `indisvalid=true`，从而通过当前 matcher；migration 中的 `IF NOT EXISTS` 跳过创建后，runner 会写 ledger。两者的排序/访问语义并不相同。

同类缺口还包括未比较 `indclass`（opclass）、`indcollation`、`indoption`（ASC/DESC、NULLS FIRST/LAST）。虽然受限 parser 不允许这些形式出现在期望 migration 中，但一个预先存在的同名实际 index 可以带这些差异；这正是 catalog validation 必须防御的 schema drift。

必须至少核对 key count 与 key/include 边界、每个 key 的 opclass/collation/options，并为“相同 attribute 列表但 INCLUDE 边界不同”添加真实 PostgreSQL case；否则不能称为 exact definition verification。

## Important

### I1. 外层事务 envelope 内的额外 transaction control 仍可绕过 fail-closed

位置：`cloud-api/internal/migrate/runner.go` 的 `transactionalSQL`。

当前逻辑只要整个文本匹配 `BEGIN ... COMMIT` envelope，就直接返回捕获的内部 SQL，没有再次扫描内部 SQL。以下结构会匹配以最后一个 `COMMIT` 结尾的 envelope：

```sql
BEGIN;
CREATE TABLE first_table(id integer);
COMMIT;
CREATE TABLE second_table(id integer);
COMMIT;
```

剥离后，runner-owned transaction 会执行内部的第一个 `COMMIT`，可能先提交 DDL；之后 ledger insert/`transaction.Commit` 失败，留下未登记 schema。嵌套 `BEGIN`、内部 `END`/`ABORT` 等也有同类风险。

本轮新增的同义词拒绝测试只覆盖无 envelope 的单条 case，没有覆盖 envelope 内额外 control。应在剥离后重新扫描 captured body，要求其中不存在任何 transaction control，并添加上述多-control regression cases。

### I2. Task 4 真实 `15432` PostgreSQL 验收仍未完成

修复报告明确说明 `CLOUD_API_TEST_DATABASE_URL` 缺失。以下行为只有代码/测试设计，没有运行证据：

1. repository `000001 → 000002 → 000003` 在空 PostgreSQL 上执行；
2. repository index 的真实 catalog 状态与 ledger/checksum/idempotence；
3. advisory-lock contention 后第二 runner no-op；
4. existing schema + empty ledger fail-closed；
5. wrong-valid/invalid same-name index 不记账及修复后恢复；
6. register/trial/redeem/session/usage/end/revoke/cross-user lifecycle；
7. fixture cleanup 在真实 FK/trigger 条件下完成。

此项是 E2E acceptance blocker，不等同于断言全部实现错误。静态 Critical/Important 修完后仍需在 controller-approved、专用、精确 `127.0.0.1:15432` DSN 上取得新鲜 exit-0 证据。

## Minor

### M1. Advisory-lock test 的固定时间窗口可能产生调度型假阳性

`TestRunSerializesAndRecoversDedicatedPostgresMigrations` 在启动 goroutine 后仅等待 `100ms`，若 goroutine因调度/负载尚未到达 lock acquisition，即使锁逻辑回归也可能在窗口结束后才执行，并在人工锁释放后成功。实现本身的 lock 范围静态正确，但 case 最好使用可观察的连接/锁等待状态或其他确定性握手，而非纯 sleep。

### M2. Lifecycle cleanup 注册偏晚，setup 中途失败仍可能遗留 fixtures

fixture cleanup 在 user/admin、batch 与 redemption code 都创建成功后才注册。若 admin role update、batch insert或 code insert 中途失败，已创建的 users/batch 可能遗留。成功执行路径的清理顺序正确且失败可见；建议在获得首个 fixture ID 后尽早注册统一 cleanup，并让零值/未创建对象安全跳过。

## Task quality

**优点：** 修复集中且未扩大依赖；DSN policy 通过同一 exported validator 复用；unsafe target 在连接前 fail；新增的 repository/lock/legacy/invalid recovery cases 对原审查缺口有针对性；checksum case 现在验证真实 ledger 分支；秘密脱敏和成功路径 cleanup 明显改善；报告诚实区分无 DSN 的静态验证与真实 E2E。

**不足：** “精确 index definition”仍只实现了部分 catalog 属性，且新增 wrong-index case过于粗粒度，未捕获 key/include 等近似定义；transaction envelope 的核心原子性绕过仍存在；关键 PostgreSQL tests 仍全部 deferred。高风险 migration runner 在这些边界修复并真实执行前不能批准。

## 只读验证记录

未连接数据库，未启动/停止 Compose，未修改 `[旧临时路径]`，未提交，未派遣子代理。

本轮执行：

```text
env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./internal/migrate
# exit 0；dedicated PostgreSQL cases skip

env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 ./integration
# exit 0；business PostgreSQL case skip，纯 validator case运行

env CLOUD_API_TEST_DATABASE_URL='<synthetic multi-host DSN>' \
  go test -tags=integration -run '^TestPostgresBusinessLifecycle$' -count=1 -v ./integration
# exit 1，连接前以固定安全文本失败；输出未包含 synthetic password

git diff --check 82c10ad..2b33e77
# exit 0

git status --short --branch && git rev-parse HEAD
# HEAD 2b33e777d8744bbdc5488b99115edd769fd03ba3；仅既有 untracked fix report
```

## 最终判定

- **Fix round 2 code approval：不批准。** C1 精确 index catalog 验证仍未完成，且 transaction envelope 内额外 control 仍可破坏 ledger 原子性。
- **Task 4 PostgreSQL E2E acceptance：未验收。** 真实 `127.0.0.1:15432` PostgreSQL cases 尚未运行。
