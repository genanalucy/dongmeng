> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-e2e-syntax-diagnosis.md`；原始 SHA-256 `004e0e8652e53fd37bc0196f0563a434e119759625176bc3436d39bc030b11de`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4：PG17 E2E SQLSTATE 42601 静态诊断

## 结论

三个 focused 失败不是同一个测试数据问题，而是两处独立的 PostgreSQL 语法错误：

1. 两个 `runner.verify-concurrent-index` 失败均来自 `runner.go` 的 catalog query：`unnest(...) ... AS key_column(...)` 的列别名列表把 PostgreSQL 关键字 `collation` 当作未引用的 `ColId` 使用，查询在解析阶段报 `42601`。
2. `fixture.create-nulls-not-distinct-index` 失败来自 `runner_test.go` 的 fixture SQL：`NULLS NOT DISTINCT` 被放在 `UNIQUE` 与 `INDEX` 之间，不符合 PostgreSQL `CREATE INDEX` 语法，解析阶段报 `42601`。

这两处各做一个局部修复即可；无需修改迁移文件、runner 控制流或错误分类。

## 输入与约束

静态读取：

- `[旧临时路径]`
- `[旧临时路径]`
- `[旧临时路径]`
- commit `a9af5c63266d9f82b94e54d1ba40a156d239df96` 及其 parent 对应 diff

未连接数据库，未运行 Docker，未执行测试，未派遣子代理，未修改实现或测试代码。本文不包含 DSN、口令或其他 secret。

## 失败逐项定位

### 1. Repository runner：verify migration `000002`，SQLSTATE `42601`

日志：

```text
first repository migration run: context=runner.verify-concurrent-index migration=000002 category=postgres-server sqlstate=42601
```

调用顺序表明：

1. `000002` 被识别为 `OutsideTransaction`；
2. `candidate.SQL` 已通过 `runner.execute-concurrent-index`，否则 context 不会是 `runner.verify-concurrent-index`；
3. 失败发生在 `concurrentIndexMatches()` 调用 `QueryRow(indexDefinition, ...)` 时；
4. `42601` 是 SQL 解析错误，因此问题在 catalog query 本身，而不是 catalog 内容不匹配或 `Scan`。

具体错误位于 `runner.go` 的 table-function alias：

```sql
LEFT JOIN LATERAL unnest(
  i.indkey::smallint[],
  i.indclass::oid[],
  i.indcollation::oid[],
  i.indoption::smallint[]
) WITH ORDINALITY
AS key_column(attnum, opclass_oid, collation, option, ordinality) ON true
```

其中 `collation` 是 PostgreSQL parser keyword（`TYPE_FUNC_NAME_KEYWORD`），不能在该 `name_list`/`ColId` 位置作为未引用列别名。parser 在 `AS key_column(... collation ...)` 处终止并返回 `42601`。多数组 `unnest`、`LATERAL` 和 `WITH ORDINALITY` 组合本身是 PostgreSQL 支持的 FROM 语法，不是根因。

后续两处引用也使用了该别名：

```sql
key_column.collation = 0
key_column.collation = attribute.attcollation
```

#### 最小修复

将 alias 列名 `collation` 改成非关键字且语义明确的 `collation_oid`，并同步改两处引用：

```sql
AS key_column(attnum, opclass_oid, collation_oid, option, ordinality) ON true
```

```sql
key_column.collation_oid = 0
key_column.collation_oid = attribute.attcollation
```

不需要改 select 列数、`Scan` 顺序、聚合或结构体字段。

### 2. Mismatched recovery runner：verify migration `000001`，SQLSTATE `42601`

日志：

```text
runner did not recover after concurrent index repair: context=runner.verify-concurrent-index migration=000001 category=postgres-server sqlstate=42601
```

这是与第 1 项相同的根因，而不是 recovery fixture 的 repaired index 仍然不匹配：

- 最终 `Run(ctx, config)` 已越过 `runner.execute-concurrent-index`；
- 随后调用同一个 `concurrentIndexMatches()`；
- 同一个 `indexDefinition` 在 parser 阶段因 alias 列名 `collation` 返回 `42601`；
- 因查询未执行到结果比较，`sameConcurrentIndexDefinition()` 尚无机会判断 repaired index。

#### 最小修复

与第 1 项共用同一处修复：把 catalog query alias `collation` 改为 `collation_oid` 并同步两处引用。无需针对 migration `000001` 或 recovery 流程另写修复。

### 3. NULLS fixture create：SQLSTATE `42601`

日志：

```text
context=fixture.create-nulls-not-distinct-index category=postgres-server sqlstate=42601
```

当前测试 SQL：

```sql
CREATE UNIQUE NULLS NOT DISTINCT INDEX events_email_idx
ON <schema>.events(email)
```

PostgreSQL 的语法顺序是：

```text
CREATE [ UNIQUE ] INDEX ... ON ... ( ... ) [ INCLUDE (...) ] [ NULLS [ NOT ] DISTINCT ] ...
```

因此 `NULLS NOT DISTINCT` 不能出现在 `UNIQUE` 与 `INDEX` 之间；它必须位于索引键列表之后（若有 `INCLUDE`，则位于 `INCLUDE` 之后）。当前语句会在执行 fixture create 时直接解析失败，尚未进入 runner。

#### 最小修复

保留未限定的 index name，并仅把子句移到键列表后：

```go
"CREATE UNIQUE INDEX events_email_idx ON "+quoteIdentifier(schema)+".events(email) NULLS NOT DISTINCT"
```

对应 SQL：

```sql
CREATE UNIQUE INDEX events_email_idx
ON <schema>.events(email)
NULLS NOT DISTINCT
```

index name 应继续保持不带 schema；table name 继续显式限定 schema。这样同时避开 PostgreSQL 不接受 schema-qualified index name 的问题。

## commit `a9af5c6` 关联分析

commit：

```text
a9af5c63266d9f82b94e54d1ba40a156d239df96
fix(cloud): harden concurrent migration diagnostics
```

与本次失败相关的变化：

1. 新增安全的 `databaseFailure` 分类与 SQLSTATE 输出，使原本被泛化隐藏的 parser 错误现在明确显示为 `42601`；这是暴露根因，不是两个 runner 语法错误的来源。
2. catalog query 的 `AS key_column(... collation ...)` 在该 commit 的 parent 中已经存在。该 commit 只把 `GROUP BY ... i.indpred` 改为 `GROUP BY ... (i.indpred IS NULL)`；这个改动不是当前 `42601` 的来源。
3. NULLS fixture 在 parent 中为：

   ```sql
   CREATE UNIQUE INDEX <schema>.events_email_idx
   ON <schema>.events(email)
   NULLS NOT DISTINCT
   ```

   其 `NULLS NOT DISTINCT` 位置正确，但 index name 被错误地 schema-qualified。`a9af5c6` 正确移除了 index name 的 schema qualification，却同时把 `NULLS NOT DISTINCT` 错移到了 `INDEX` 前，直接引入当前 fixture `42601`。

## 最小补丁范围建议

仅需修改两个局部位置：

1. `cloud-api/internal/migrate/runner.go`
   - `key_column` alias：`collation` → `collation_oid`；
   - 同步更新该字段的两处引用。
2. `cloud-api/internal/migrate/runner_test.go`
   - 将 `CREATE UNIQUE NULLS NOT DISTINCT INDEX ... ON ... (email)` 改为 `CREATE UNIQUE INDEX ... ON ... (email) NULLS NOT DISTINCT`；
   - 保持 index name 不带 schema，保持 table name 带 schema。

不建议顺带重写 catalog query、迁移 parser 或错误封装；这些都超出解除三个 focused `42601` 的最小范围。

## 修复后验证建议

由于本次被限定为纯静态诊断，以下命令未执行。实现上述最小修复后，应在真实 PG17 环境重新运行三个 focused case：

```bash
go test ./internal/migrate -run 'TestRunRecordsRepositoryChecksumsIsIdempotentAndFailsBeforeLaterMigration|TestRunRejectsMismatchedOrInvalidConcurrentIndexBeforeLedgerAndRecovers|TestRunRejectsNullsNotDistinctConcurrentIndexBeforeLedger' -count=1 -v
```

预期：

- repository migration `000002` 的 catalog verification 可执行并完成 ledger 写入；
- mismatched/invalid fixture 仍在 ledger 前 fail closed，repair 后 `000001` 可恢复并只记录一次；
- `NULLS NOT DISTINCT` fixture 创建成功，runner 将其识别为定义不匹配并拒绝 ledger，而不是 fixture 自身报 `42601`。

## 静态诊断边界

本结论能确定三个 `42601` 的具体 parser 来源及最小语法修复。由于按要求未连接 PG17、未执行 E2E，不能在本文中宣称修复后测试已经通过；动态验证仍需在应用补丁后完成。
