> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-review-retry.md`；原始 SHA-256 `b0f9aac737b292f0e01fabdfb158b40ddcdec89bca9ae7b9d56a8f1498756f6b`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 独立高风险代码审查（retry）

审查对象：`[旧临时路径]` commit `f03e9e7`（基线 `9417753`）

Spec compliance: ❌
Task quality: Not approved

## 结论

当前实现具备 up-only CLI、字典序发现、SHA-256 ledger、普通 migration 单事务、`CREATE INDEX CONCURRENTLY` 事务外执行、Compose 启动依赖以及脱敏错误等基础能力，但仍存在一个可直接违反数据库安全边界的 Critical 问题，以及并发 migration、事务外 migration 部分失败恢复和 PostgreSQL 验收测试可信度方面的 Important 问题。不能批准。

## Critical

### C1. Migration runner 未拒绝不安全 host/port，可直接连接并修改 `127.0.0.1:5432`

位置：
- `cloud-api/cmd/migrate/main.go:42-59`
- `cloud-api/internal/migrate/runner.go:51-66`

CLI 只要求显式提供非空 DSN；runner 只调用 `pgxpool.ParseConfig`，没有任何 host/port allowlist 或对 `5432` 的拒绝。因此以下形式会被接受并继续创建 schema/ledger、执行 migration：

```text
migrate up --database-url [历史开发连接已脱敏]
```

测试中的 `127.0.0.1:15432` 检查只保护测试，不能保护真实 CLI。它违反 approved constraints 中 Cloud PostgreSQL 仅允许宿主 `127.0.0.1:15432`、禁止迁移 `127.0.0.1:5432` 的硬边界。Compose 内部 `postgres:5432` 是已批准的容器网络例外，但当前实现没有建立“宿主 CLI 仅 15432 / Compose 内部服务例外”的可验证策略。

必须在任何网络连接和任何 DDL 前 fail closed，并添加 CLI/runner 测试证明 `127.0.0.1:5432`、`localhost:5432`、其他未批准 host/port 均被拒绝。

## Important

### I1. `CREATE INDEX CONCURRENTLY` 部分失败后可能把无效索引标记为已迁移

位置：`cloud-api/internal/migrate/runner.go:116-123`

事务外 migration 先执行 SQL，再独立写 ledger。若 `CREATE INDEX CONCURRENTLY` 被取消、崩溃或因资源问题失败，PostgreSQL 可能留下同名 `INVALID` index。重试当前 `000002` 时，`IF NOT EXISTS` 可因同名对象存在而跳过重建；runner 随后写入 ledger，最终把无效索引视为成功 migration。

实现报告只讨论了“SQL 成功、ledger 写入前崩溃”的重试，没有覆盖 PostgreSQL concurrent-index 失败留下 invalid index 的语义。应在记账前验证目标索引有效，或采用明确、可恢复的 concurrent-index 策略，并用真实 PostgreSQL 故障/重试测试锁定行为。

### I2. Runner 没有 migration 级互斥锁，并发启动会竞态执行同一 migration

位置：`cloud-api/internal/migrate/runner.go:78-110`

两个 runner 可同时读取同一份 ledger，然后同时执行 pending migration。普通 migration 可能因重复 DDL 失败；事务外 concurrent index 可能互相阻塞/失败；即使 SQL 都成功，ledger insert 也会发生主键竞争。Compose 单实例启动不能替代 runner 自身的部署安全性。

应在读取 ledger 前获得 PostgreSQL advisory lock（或等价的数据库级互斥），并持续持有至全部 migration 和 ledger 操作结束。

### I3. 核心 PostgreSQL migration 测试没有运行 repository 的 `000001`、`000002`、`000003`

位置：
- `cloud-api/internal/migrate/runner_test.go:32-105`
- `cloud-api/internal/migrate/runner_test.go:107-119`

真实数据库测试使用三份 synthetic SQL，而不是 Task brief 要求的 repository migration。另一个静态测试只检查文件顺序和 `OutsideTransaction` 布尔值。因此当前测试不能证明：

- 三份真实 migration 可在空 PostgreSQL 上依次成功；
- `000001`/`000003` 的既有 `BEGIN/COMMIT` 经 runner 处理后可执行；
- `000002` 在真实 schema 上事务外成功；
- 三个真实 checksum/ledger 条目完整且重复执行为 no-op。

业务 lifecycle 测试又假设 migration 已事先应用，没有通过 runner 建库。报告也确认本轮无 DSN，真实 database migration/lifecycle 均未运行，所以不能视为 PostgreSQL 验收完成。

### I4. 配置了无效或不安全测试 DSN 时测试会 SKIP，掩盖 CI/操作者错误

位置：
- `cloud-api/internal/migrate/runner_test.go:120-141`
- `cloud-api/integration/postgres_business_test.go:127-138`

Task brief 要求“仅在 dedicated test DSN 缺失时 skip”。当前代码对格式错误、错误 host、错误 port、空 database 也执行 `t.Skip`。实际验证确认：提供 `127.0.0.1:5432` 时 migration test 以 PASS+SKIP 退出；提供 `localhost:15432` 时 lifecycle test 同样 PASS+SKIP。

缺失变量可以清晰 skip；变量一旦存在但不安全/无效，应在连接前明确 FAIL，既保证绝不连接 5432，又避免错误配置产生绿色 CI。

### I5. 跨用户 usage/session 测试可能因重复 usage 唯一约束而假阳性

位置：`cloud-api/integration/postgres_business_test.go:90-104`

测试先为 `first.ID` 写入 usage，再验证同 session 第二次写入冲突，随后仍用该 `first.ID` 做跨用户写入，只断言“有任意 error”。该请求即使没有 `(session_id,user_id)` 所有权约束，也会因 `usage_records_session_unique` 失败。因此它不能证明跨用户 association 被拒绝。

应创建一个尚无 usage 的目标用户 session，再让另一用户写入，并断言期望的 domain error/约束路径。

### I6. Migration 测试 cleanup 在连接关闭后执行，临时 schema 很可能无法删除

位置：`cloud-api/internal/migrate/runner_test.go:40-50`

`defer conn.Close(ctx)` 在测试函数返回时先执行，`t.Cleanup` 随后使用已关闭的 `conn` 执行 `DROP SCHEMA`，并静默丢弃错误。重复测试会在 dedicated database 中遗留 `migration_test_*` schema，削弱测试隔离性和可重复性。

### I7. Compose 将含密码的 DSN 放进命令参数

位置：`docker-compose.yml:21-25`

新增 migrate service 的 `-database-url [历史开发连接已脱敏]` 会出现在容器 command、进程参数和 `docker compose config` 输出中。即使当前值标注为 development-only，这种接线模式不满足“不输出 secret”的安全模式，也会诱导真实部署把凭据放入 argv。应通过受控环境变量/secret 注入传入 DSN，并避免在 command 中展开。

## Minor

### M1. Lifecycle 测试没有数据库级证明“恰好一个 trial”

位置：`cloud-api/integration/postgres_business_test.go:42-51`

测试验证返回的 trial 是 3 天，并验证重复 email 注册冲突，但没有查询该 user 的 trial 数量。若 `Register` 意外额外插入 trial，该测试仍可能通过。应查询并断言恰好一条 trial entitlement。

### M2. SQL envelope/parser 策略对未来 migration 较脆弱

位置：
- `cloud-api/internal/migrate/runner.go:179-189`
- `cloud-api/internal/migrate/runner.go:195-259`

`transactionalSQL` 仅识别从文件首尾严格包裹的 `BEGIN; ... COMMIT;`；尾随注释等常见形式不会剥离。`sqlWords` 也不是完整 PostgreSQL lexer（例如 dollar-quoted body）。当前三份 migration 恰好适配，但未来 SQL 可能被误分类或形成嵌套事务语义。至少应补充明确约束和边界测试。

## 已确认符合的部分

- `cmd/migrate` 只接受显式 `up`，无命令及 `down` 均拒绝；normal path 只发现 `.up.sql`，不会执行 `.down.sql`。
- migration 按文件名字典序排列，版本重复会失败；使用原始文件内容计算 SHA-256。
- ledger 包含 `version`、`checksum`、`applied_at`；已记录 checksum 在任何 pending migration 前统一校验，unknown version 也失败。
- 普通 migration 的 SQL 与 ledger insert 同处一个 transaction；当前 `000001`、`000003` 的外层 `BEGIN/COMMIT` 可被剥离。
- 当前 `000002` 被识别为事务外 migration；重复 run 对 checksum 一致的已记账版本为 no-op。
- 错误文本未携带 DSN、密码或完整 driver error；lifecycle 测试错误信息也做了脱敏。
- Compose 中 postgres healthcheck → migrate completed successfully → cloud-api 的依赖顺序正确；宿主映射仍为 `127.0.0.1:15432:5432`，旧的 entrypoint init mounts 已移除。
- commit 未修改任何既有 `migrations/*.sql`。对 `9417753` 与 `f03e9e7` 的三份 up migration SHA-256 逐一比较完全一致，migration 文件树也一致。
- fixtures 的 email、install ID、session ID 使用 UUID，满足唯一性要求。

## 只读验证记录

未执行数据库迁移、未连接数据库、未启动或停止 Compose service。

已执行并通过：

```text
git status --short --branch
git rev-parse HEAD
git show --stat f03e9e7
git diff --check 9417753..f03e9e7
git diff --exit-code 9417753..f03e9e7 -- migrations
三份既有 up migration 的基线/目标 SHA-256 对比
docker compose config --quiet
go test -count=1 ./cmd/migrate ./internal/migrate
go vet ./cmd/migrate ./internal/migrate
go build ./cmd/migrate
env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 -v ./integration
```

安全负向验证（均未发起数据库连接）：

```text
CLOUD_API_TEST_DATABASE_URL=...@127.0.0.1:5432/... go test -v ./internal/migrate
# 结果：测试 SKIP，命令整体 PASS

CLOUD_API_TEST_DATABASE_URL=...@localhost:15432/... go test -tags=integration -v ./integration
# 结果：lifecycle 测试 SKIP，命令整体 PASS
```

`CLOUD_API_TEST_DATABASE_URL` 未设置，因此真实 PostgreSQL migration 和 business lifecycle 行为尚无运行证据。
