> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-rereview-4.md`；原始 SHA-256 `83d039bad08867041c90645f272202db59d86fe4f32d8c4f42646b93a9b952de`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 4 只读复审

审查对象：`[旧临时路径]` commit `b1aa16d75ff66e6a80ac708dc5e183da6e156f65`，修复范围 `48e8aea..b1aa16d`。

## 结论

上轮 catalog blocker 已修复：受限 grammar 将期望语义建模为 `NullsNotDistinct=false`、`Immediate=true`，catalog 查询读取 `pg_index.indnullsnotdistinct` 与 `pg_index.indimmediate`，exact matcher 对两者执行 fail-closed 比较。新增的 `NULLS NOT DISTINCT` dedicated PostgreSQL case 也断言 mismatch 时 ledger 为空。

上轮点名的显式 `E'...'` / `U&'...'` 绕过已修复：scanner 在 token boundary 遇到 `E/e` escape string 或 `U/u&` Unicode escape form 即报错，outer-envelope body 使用同一路径复扫；回归用例覆盖了 `E'foo\\'bar'` 后存在真实 `COMMIT` 的情形。

但是仍存在一个与 DSN validation 交互的等价绕过：允许的 DSN query 参数可以把 PostgreSQL session 的 `standard_conforming_strings` 设为 `off`，使普通 `'...'` 重新具有反斜杠转义语义，而 scanner 仍按 doubled-quote-only 规则接受并扫描普通字符串。这样无需使用已被拒绝的 `E'...'`，仍可令 scanner 漏掉 body 中的 transaction control。

**最终判定：不可以进入 controller 创建的真实 `127.0.0.1:15432` 测试阶段。** 应先关闭以下静态 blocker，再进行真实 PostgreSQL 验收。

## Important

### I1. DSN 可关闭 `standard_conforming_strings`，把已修复的 E-string 绕过重新变成普通字符串绕过

位置：`cloud-api/internal/migrate/runner.go:82-102`、`cloud-api/internal/migrate/runner.go:129-134`、`cloud-api/internal/migrate/runner.go:490-536`、`cloud-api/internal/migrate/runner.go:557-569`。

`ValidateDatabaseURL` 只拒绝 `host`、`port`、`hostaddr`、`service`、`servicefile` query override，其余 query key 会继续交给 `pgxpool.ParseConfig`。pgx 将未归类的连接参数放入 startup `RuntimeParams`，因此下列仍通过当前 target validation：

```text
[历史开发连接已脱敏]
```

在该 session 中，PostgreSQL 会把普通字符串里的反斜杠引号当作转义。于是 envelope 可改写为：

```sql
BEGIN;
SELECT 'foo\'bar';
COMMIT;
CREATE TABLE committed_without_ledger(id integer);
COMMIT;
```

PostgreSQL 看到的第一个 `COMMIT` 是真实 transaction control；当前 `sqlWords` 却不会把普通字符串 fail-closed，也不理解反斜杠转义。它会在 `\'` 处提前结束字符串，再从真正的结束引号开始跳过后续内容，可能漏掉 body 中的真实 `COMMIT`。这与上轮 E-string blocker 的后果相同：runner-owned transaction 可被提前提交，后续 DDL 与 ledger insert 不再原子。

必须让连接配置对影响 SQL lexical semantics 的参数 fail closed。优先对 DSN 使用显式 allowlist，并在 pgx parse 后再次验证实际 `Host`/`Port`、fallbacks 与 `RuntimeParams`；至少拒绝 `standard_conforming_strings` 和可注入 server options 的 `options`。另一种安全做法是在受控连接配置中强制 `standard_conforming_strings=on` 且禁止 DSN 覆盖，同时增加上述 DSN + 普通反斜杠字符串 + 内部 `COMMIT` 的 regression test。仅拒绝 `E'...'` / `U&'...'` 不足以保证 fail-closed。

## 其余指定检查

未发现其他 Critical/Important：

- **Catalog 默认 NULL 语义与 immediate：** `indnullsnotdistinct`、`indimmediate` 均被查询、扫描和 exact compare；受限 grammar 的期望分别固定为 `false`、`true`。mismatch 返回 false，不会写 ledger。
- **显式 E/U escape forms：** token-boundary 的 `E/e'...'`、`U/u&'...'`/quoted identifier 均被拒绝；outer body 复扫错误也转为拒绝，点名回归不能再绕过 transaction-control 检查。
- **Ledger 时机：** concurrent DDL 后先做 catalog validation，matcher 为 true 后才 insert ledger；普通 migration 在 runner-owned transaction 内执行 DDL 与 ledger insert。本报告 I1 是仍会破坏普通 migration 原子性的唯一 Important。
- **秘密输出：** `Run` 的 parse/open/acquire/apply 错误仍被替换为固定文本，unsafe target 使用不含 DSN 的 sentinel；新增测试名称及失败文本不输出 DSN/password，未发现新秘密输出路径。

## 只读验证记录

未修改 `[旧临时路径]`，未运行 Docker/Compose，未连接或操作数据库。

执行：

```text
git rev-parse HEAD
# b1aa16d75ff66e6a80ac708dc5e183da6e156f65

git status --short --branch
# ## internal-beta-integration
# ?? task-4-fix-round-2-report.md

git diff --check 48e8aea..b1aa16d
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./internal/migrate
# ok；exit 0；dedicated PostgreSQL cases因 DSN 未设置而 skip
```

## 最终判定

- **Fix round 4 code approval：不批准。** 上轮两个具体实现缺口本身已修复，但 I1 保留了等价 transaction-control 绕过。
- **进入 controller 创建的真实 `127.0.0.1:15432` 测试阶段：不可以。** 先收紧 DSN/runtime parameter validation 或固定并验证 lexical GUC，再运行真实 PostgreSQL 测试。
