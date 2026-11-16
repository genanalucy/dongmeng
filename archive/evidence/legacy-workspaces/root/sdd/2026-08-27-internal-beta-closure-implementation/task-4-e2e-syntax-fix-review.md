> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-e2e-syntax-fix-review.md`；原始 SHA-256 `efb6dfec244481046ae5b752871cd25910a85a6b19b8b9fef96eaeb8bf4802c8`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4：PG17 E2E SQL 语法修复审查

## 审查结论

**APPROVED — 未发现 Critical 或 Important 问题。**

commit `17a05ad380f671598e1f019fc88d472402c87220` 对诊断中的两处 PostgreSQL 语法错误做了局部且一致的修复，提交边界仅包含：

- `cloud-api/internal/migrate/runner.go`
- `cloud-api/internal/migrate/runner_test.go`

该提交从静态审查角度已具备重跑真实 `127.0.0.1:15432` PostgreSQL 17 E2E 的条件。**可以重跑真实 15432 E2E**；但本次审查遵守只读约束，没有连接数据库，因此这表示“允许进入动态验证”，不表示真实 E2E 已通过。

## 审查约束

本次仅进行静态读取与不连接数据库的检查：

- 未连接任何数据库；
- 未读取或设置数据库 DSN；
- 未运行 Docker；
- 未修改 `[旧临时路径]` 中的源码、测试或配置；
- 唯一写入为本审查报告；
- 未在报告中记录口令、token、DSN 或其他 secret。

审查输入：

- commit `17a05ad`
- `task-4-e2e-syntax-diagnosis.md`
- `task-4-e2e-syntax-fix-report.md`
- 提交前后相关源码及完整 commit diff
- PostgreSQL 17 官方 `CREATE INDEX` 语法摘要

## 逐项检查

### 1. catalog alias `collation_oid` 引用一致

通过。

生产查询中的 alias 列表为：

```sql
AS key_column(attnum, opclass_oid, collation_oid, option, ordinality)
```

生产查询对该字段恰有两处引用，均已同步为：

```sql
key_column.collation_oid = 0
key_column.collation_oid = attribute.attcollation
```

在 `cloud-api/internal/migrate` 的 commit 快照中未发现遗留的生产引用 `key_column.collation`，也未发现 alias 列表继续声明裸 `collation`。测试中的旧字符串只出现在否定回归断言：

```go
strings.Contains(concurrentIndexDefinitionQuery, "key_column.collation =")
```

这不是不一致引用，而是防止旧语法回归的检查。查询提升为包级常量后，`concurrentIndexMatches()` 使用同一 `concurrentIndexDefinitionQuery`，未保留第二份可能漂移的 catalog SQL。

### 2. `NULLS NOT DISTINCT` fixture 符合 PostgreSQL 17

通过。

生成的 SQL 精确为：

```sql
CREATE UNIQUE INDEX events_email_idx ON "migration_test".events(email) NULLS NOT DISTINCT
```

其结构与 PostgreSQL 17 官方语法一致：

```text
CREATE [ UNIQUE ] INDEX ... ON table_name (...)
    [ INCLUDE (...) ]
    [ NULLS [ NOT ] DISTINCT ]
```

因此 `NULLS NOT DISTINCT` 正确位于索引键列表之后。fixture 同时保持：

- index name `events_email_idx` 未作 schema qualification；
- table name 使用经 `quoteIdentifier(schema)` 处理的 schema qualification；
- `NULLS NOT DISTINCT` 仅用于 unique index；
- 当前 runner 最低支持版本为 PostgreSQL 15，而该能力自 PG15 可用，PG17 路径正确。

新增离线测试固定了完整 SQL 文本，能够捕获子句位置和限定方式的回归。

### 3. 诊断与修复报告一致性

通过。

实现与 `task-4-e2e-syntax-diagnosis.md` 建议一致：

1. `collation` 改为 `collation_oid`，并同步两个字段引用；
2. fixture 改为 `CREATE UNIQUE INDEX ... (email) NULLS NOT DISTINCT`；
3. 未改迁移文件、runner 控制流、错误分类、查询列顺序或 `Scan` 顺序。

`task-4-e2e-syntax-fix-report.md` 对提交范围和未进行真实 PG17 动态验证的声明与 commit 内容一致。报告把 catalog query 称作“catalog CTE”不够精确（实际是普通查询常量），但不影响实现或验证结论，不构成 Important 问题。

### 4. Critical / Important 回归检查

未发现。

- 查询 SELECT 列数、顺序与 `Scan` 目标未变化；
- alias 重命名不改变 catalog 数据类型或业务判断；
- `sameConcurrentIndexDefinition()` 的 fail-closed 比较未改变；
- fixture helper 仅替换原测试 SQL，不进入生产执行路径；
- schema 仍通过现有 `quoteIdentifier()` 处理；
- commit 未修改迁移文件、数据库目标校验、ledger 行为或错误白名单；
- `git diff --check` 通过，未发现空白错误；
- worktree 中存在两份与本提交无关的未跟踪报告，但它们未包含在 `17a05ad`，不影响提交边界。

### 5. secret / 敏感输出检查

未发现该提交引入秘密输出。

- commit 仅新增静态 catalog SQL、fixture SQL helper 和断言；
- 新增错误消息只输出固定描述或生成后的无凭据 SQL；
- 未新增 DSN、用户名、口令、token、密钥、环境变量值或数据库错误原文输出；
- 生产 `databaseFailure` 白名单行为未被本提交改动；
- commit diff 的敏感模式检查未发现凭据或私钥材料。

仓库既有测试中的 `top-secret` 等均为既存测试 canary，不是本提交新增的真实 secret，也没有被提交改动。

## 验证记录与边界

已完成的非数据库检查：

- 查看 commit 元数据、文件范围和完整 diff；
- 对比 parent 与 `17a05ad` 的 alias 声明及引用；
- 检查提交内旧 alias 遗留；
- 检查 `NULLS NOT DISTINCT` 精确生成文本；
- 对照 PostgreSQL 17 官方 `CREATE INDEX` grammar；
- 检查 commit diff 的敏感信息模式；
- `git diff --check 17a05ad^ 17a05ad`：通过。

按用户约束未执行真实数据库测试，因此未独立复验修复报告中声称的 Go test、vet、build 或 Docker Compose 配置结果，也不将其作为本审查的动态通过证据。

## 真实 15432 E2E 重跑决定

**决定：可以重跑。**

建议由获准连接专用测试数据库的执行者，在确认环境变量仅指向隔离的 `127.0.0.1:15432` PostgreSQL 17 后运行：

```bash
go test ./internal/migrate -run 'TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration|TestRunRejectsMismatchedOrInvalidConcurrentIndexBeforeLedgerAndRecovers|TestRunRejectsNullsNotDistinctConcurrentIndexBeforeLedger' -count=1 -v
```

重跑前提：

1. `CLOUD_API_TEST_DATABASE_URL` 由安全渠道注入，日志和报告不得打印其值；
2. 目标必须通过现有 `ValidateDatabaseURL(..., false)`，即隔离的 `127.0.0.1:15432`；
3. 不得指向或影响无关的 `127.0.0.1:5432` 实例；
4. 测试将创建并清理随机 schema，需确保专用 PG17 实例允许该操作。

动态验收标准：三个 focused case 全部通过，且不再出现 catalog verification 或 fixture creation 的 SQLSTATE `42601`。若动态重跑仍失败，应记录安全的 context/category/SQLSTATE，不得复制底层数据库消息或 DSN。
