> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-rereview-3.md`；原始 SHA-256 `de82a3cf21de8f91d3718cd19fb5ed149afec1a1d34413e607931d75b193f486`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 3 只读复审

审查对象：`[旧临时路径]` commit `48e8aea4a5b7988010696784bb4ed4a91bef2ab0`，修复范围 `2b33e77..48e8aea`。

## 结论

本提交确实修复了上一轮指出的大部分 concurrent-index 属性比较：key/`INCLUDE` 边界、key 顺序、默认 opclass、默认 collation、`indoption == 0`（默认 ASC/NULL ordering）以及无 predicate 都会被检查；外层 `BEGIN … COMMIT` 被剥离后，也会重新扫描 body。

但仍有两个高风险缺口，不能批准：catalog matcher 没有比较 unique index 的 NULL 等价语义和约束即时性，仍可把定义不同的同名 valid index 当成匹配并写 ledger；transaction-control scanner 不是 PostgreSQL 词法分析器，合法 escape string 可使其漏掉 body 内的 `COMMIT`。

**最终判定：不可以进入真实 `127.0.0.1:15432` DB 测试。** 应先修复以下静态 blocker，并增加对应 regression tests；之后才能进行专用真实 PostgreSQL 验收。

## Critical

### C1. Exact index matcher 仍接受 NULL 等价语义或约束即时性不同的同名 valid unique index

位置：`cloud-api/internal/migrate/runner.go:280-302`、`cloud-api/internal/migrate/runner.go:317-327`。

新增 catalog 查询比较了 `indisunique`，但没有读取或比较 PostgreSQL 17 `pg_index.indnullsnotdistinct` 与 `pg_index.indimmediate`。

因此，对期望 migration：

```sql
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS events_email_idx
ON events (email);
```

预先存在的同名 valid index：

```sql
CREATE UNIQUE INDEX events_email_idx
ON events (email) NULLS NOT DISTINCT;
```

会通过当前 matcher：schema/table/method/key、default opclass/collation/order、predicate、`indisunique` 和 `indisvalid` 全部相同，但它把多个 `NULL` 视为冲突；期望的默认 `NULLS DISTINCT` 则允许多个 `NULL`。`IF NOT EXISTS` 跳过创建后，`apply` 会在 `runner.go:254` 写入 ledger，错误确认了不同的约束语义。

同理，由 `DEFERRABLE` unique constraint 支撑、`indimmediate=false` 的同名 valid index，也可能被当成期望的即时 unique index。当前测试只覆盖 `INCLUDE` 边界和聚合 boolean mutation，没有覆盖这两个真实 catalog 差异。

必须把受限 grammar 的期望值明确建模为 `indnullsnotdistinct=false`、`indimmediate=true`，在 catalog 查询和 matcher 中 fail closed 比较，并至少添加真实 PostgreSQL 的 `NULLS NOT DISTINCT` 同名 valid index case，断言 migration 失败且 ledger 保持空。

## Important

### I1. Envelope body 复扫仍可被 PostgreSQL escape string 绕过并漏检内部 `COMMIT`

位置：`cloud-api/internal/migrate/runner.go:378-397`、`cloud-api/internal/migrate/runner.go:469-515`。

`transactionalSQL` 现在会对捕获的 body 调用 `sqlWords`，这修复了普通文本中的额外 `COMMIT`。但 `sqlWords` 遇到单引号时只理解 doubled quote，不理解 PostgreSQL `E'...'` 的反斜杠转义。它会把 escape string 内的 `\'` 错当成字符串结束，再把真正的结束引号错当成下一段字符串开始，从而可能吞掉后续真实 transaction control。

例如完整 envelope body 可包含：

```sql
BEGIN;
SELECT E'foo\'bar';
COMMIT;
CREATE TABLE committed_without_ledger(id integer);
COMMIT;
```

PostgreSQL 将 `\'` 解释为 escape string 内的引号；第一个 `COMMIT` 是真实事务控制。当前 scanner 会在该转义引号处提前结束字符串、随后从真正的结束引号开始错误跳过余下 body，因而 body 复扫可返回“无 transaction control”。这重新打开了 runner-owned transaction 被提前提交、后续 DDL/ledger 不再原子的问题。

必须使用能与 PostgreSQL lexical rules 对齐的 scanner/parser，或进一步收紧普通 migration grammar，使无法精确解析的 quoting/comment 形式 fail closed；并添加 envelope + `E'foo\'bar'` + 内部 `COMMIT` 的回归测试。

## 其余指定检查

以下项目未发现新的 Critical/Important：

- **DSN target validation：** `ValidateDatabaseURL` 仍在 migration discovery、pgx config/pool 和 lifecycle `store.Open`/`pgxpool.New` 前执行；精确限制 `127.0.0.1:15432`，拒绝 multi-host 与 `host`/`port`/`hostaddr`/`service`/`servicefile` query override，错误文本不含 DSN。
- **Ledger 写入时机：** concurrent DDL 后先做 catalog validation，只有 matcher 返回 true 才在 `runner.go:254` 写 ledger；mismatch/error 不写 ledger。普通 migration 的 DDL 与 ledger insert 仍放在 runner-owned transaction 中，但 I1 会破坏该保证。
- **测试 DSN 泄露：** DSN 只作为 case data/环境值使用；subtest 名称和失败文本是固定安全文本，未发现把 DSN、password 或底层连接错误打印出来的路径。

## 只读验证记录

未修改 `[旧临时路径]`，未连接数据库，未运行 Docker/Compose 或任何数据库命令。

执行：

```text
git rev-parse HEAD
# 48e8aea4a5b7988010696784bb4ed4a91bef2ab0

git status --short --branch
# 仅既有 untracked task-4-fix-round-2-report.md

git diff --check 2b33e77..48e8aea
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./internal/migrate
# exit 0；dedicated PostgreSQL cases skip

env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 ./integration
# exit 0；PostgreSQL lifecycle case skip
```

## 最终判定

- **Fix round 3 code approval：不批准。** C1 与 I1 均可导致错误 schema 状态被确认或 migration/ledger 原子性失效。
- **进入真实 `15432` DB 测试：不可以。** 先修复静态 blocker，再运行专用 PostgreSQL 的 repository migration、catalog mismatch/recovery、lock、legacy ledger 与 business lifecycle cases。
