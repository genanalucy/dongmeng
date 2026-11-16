> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-review.md`；原始 SHA-256 `f61dcaa2ba28938c2c1e5859b633cedd7d54d9040826938e0dd9878d5264c931`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 独立高风险审查

## 结论

**不通过。** 实现具备 up-only CLI、显式 DSN、checksum ledger、普通迁移事务与 concurrent-index 分类等基础结构，且未修改既有 migrations；但当前仍存在可能误连宿主 `5432`、把失败的 concurrent index 标记为已应用，以及旧 Compose volume 无法升级的高风险路径。真实 PostgreSQL 验收本轮全部 skip，且现有测试没有在 PostgreSQL 上执行仓库的 `000001`–`000003`，不足以证明计划要求。

## Spec compliance

### 符合

- CLI 只接受显式 `up`；无命令及 `down` 均拒绝。
- DSN 仅来自 `--database-url` / `CLOUD_API_MIGRATE_DATABASE_URL`，没有回退到 `DATABASE_URL` 或默认连接串。
- runner 只发现 `.up.sql`，不会执行既有 `.down.sql`；diff 未修改任何 `migrations/*.sql`。
- migration 文件按文件名字典序排序，计算 SHA-256，并记录 `version/checksum/applied_at`。
- 已记录 checksum 在应用后续 migration 前统一校验；一致时重复运行跳过已记录 migration。
- 普通 migration 与 ledger insert 位于同一事务；仓库当前 `000001`、`000003` 的简单外层 `BEGIN/COMMIT` 形式可被剥离。
- `000002` 被静态分类为 transaction 外执行；注释和普通单引号字符串中的关键字不会触发误判。
- 错误信息有意做了泛化，当前 runner 返回路径未直接拼接 DSN；业务测试也未输出密码/JWT/DSN/原始 driver error。
- Compose host mapping 保持 `127.0.0.1:15432:5432`，Cloud API 等待 migrate 成功后启动。

### 不符合 / 未证明

- 全局约束要求 Cloud PostgreSQL 验证只允许 `127.0.0.1:15432`，但 migration CLI/runner 对显式 DSN 没有任何 host/port guard。
- brief 要求 PostgreSQL migration test 实际运行仓库 `000001`、`000002`、`000003`；当前 DB test 运行的是临时 synthetic SQL，仓库 SQL只做发现/分类单测。
- brief 规定仅在 dedicated test DSN **缺失**时 skip；当前 DSN 已设置但 malformed、host/port 错误或 database 缺失时仍 skip，导致 CI 误配置显示绿色。
- 本轮没有 dedicated DSN，migration 与 business lifecycle 的真实 PostgreSQL cases 均未运行；因此 ledger、真实 SQL 事务处理、concurrent index、生命周期均无真实 DB 验收证据。

## 分级发现

### Critical

#### C1. migration CLI 可直接连接并修改 `127.0.0.1:5432`

**位置：** `cloud-api/cmd/migrate/main.go` 的 `migrationConfig`；`cloud-api/internal/migrate/runner.go` 的 `Run`。

“显式 DSN”不等于“安全 DSN”。以下输入会被接受并立即建 schema/ledger、执行 migration：

```text
migrate up --database-url [历史开发连接已脱敏]
CLOUD_API_MIGRATE_DATABASE_URL=[历史开发连接已脱敏] migrate up
```

这直接违反本计划“严禁修改 `127.0.0.1:5432`”的高风险边界。测试 guard 只存在于 test helper，不能保护 CLI。Compose 内部 `postgres:5432` 是容器网络目标，应作为明确受控例外；loopback 目标必须只允许 `127.0.0.1:15432`（并谨慎处理 `localhost`/IPv6/Unix socket/service-file 等绕过形式）。拒绝错误应保持 DSN 脱敏。

#### C2. concurrent index 失败后可能被错误记为成功，造成永久 schema 漂移

**位置：** `cloud-api/internal/migrate/runner.go` 的 `apply` outside-transaction 分支。

`CREATE INDEX CONCURRENTLY` 与 ledger insert 无法原子提交。PostgreSQL 在 concurrent build 失败/取消时可能留下同名 `INVALID` index。当前 `000002` 使用 `IF NOT EXISTS`：下次运行会因同名对象存在而不重建，然后 runner 插入 ledger，最终把不可用的 invalid index 标记成已成功应用。之后 checksum/幂等逻辑会永久跳过它。

报告只讨论了“SQL 成功、ledger 前崩溃”的安全重试，没有覆盖更危险的“SQL 失败并留下 invalid index”。需要在记录 ledger 前验证目标 index 有效，或设计可恢复的 concurrent migration 协议；同时用真实 PostgreSQL 故障/恢复测试锁定语义。仅依赖 `IF NOT EXISTS` 不安全。

### Important

#### I1. 旧 Compose volume 无法从 entrypoint-init 方案升级

**位置：** `docker-compose.yml` 从 `/docker-entrypoint-initdb.d` 改为独立 `migrate` service。

已有 `cloud-postgres-data` volume 可能已经由旧 Compose 执行过 `000001`–`000003`，但没有 `schema_migrations` ledger。新 runner 会创建空 ledger 后从 `000001` 重跑，并在 `CREATE TABLE users` 等非幂等语句处失败；`cloud-api` 因 `service_completed_successfully` 永远无法启动。

这使“database setup repeatable”对现有开发环境不成立。应提供安全、可验证的 baseline/adoption 流程（核验完整 schema/checksum 后写 ledger），或明确、显著记录兼容边界与受控重建步骤；不能静默把已存在 schema 当作新库。

#### I2. 没有 migration 全局锁，并发 runner 会竞态失败或产生部分状态

**位置：** `Run` 在读取 ledger 和应用 migrations 之间没有 PostgreSQL advisory lock 或等价串行化机制。

两个部署实例同时启动时都可读到相同 ledger，然后重复执行同一 migration。普通 DDL 会使其中一个失败；concurrent migration 可能两次执行 SQL，随后在 ledger PK 上冲突。虽然通常至少一个实例成功，但 Compose/部署任务会出现不稳定失败，并扩大 C2 的部分执行窗口。应对整个校验+应用过程持有 session-level advisory lock，并测试并发调用。

#### I3. PostgreSQL migration 测试没有执行仓库 migrations，无法验证最关键兼容性

**位置：** `cloud-api/internal/migrate/runner_test.go`。

`TestRunRecordsChecksumsIsIdempotentAndFailsBeforeLaterMigration` 使用临时的三条简化 migration；`TestRepositoryMigrationsClassifyConcurrentIndexOutsideTransaction` 只读取真实文件并检查分类。因而没有证明：

- 真实 `000001`/`000003` 的 `BEGIN/COMMIT` 被安全处理；
- `000001 → 000002 → 000003` 在空 PostgreSQL 上可完整执行；
- `000002` 真实创建有效 index；
- 第二次运行真实 migrations 是 no-op；
- ledger 对真实文件记录正确 checksum。

这也是本轮无 DSN 时被 skip 后仍无法由其他测试弥补的核心缺口。

#### I4. DSN 已配置但不安全/无效时错误地 skip，掩盖 CI 配置事故

**位置：** `isolatedTestDSN`、`isolatedPostgresTestDSN`。

安全性上“不连接错误目标”是正确的；但测试结果语义错误。brief 明确只允许在环境变量缺失时 skip。环境变量存在却 malformed、不是 `127.0.0.1:15432`、或无 database，应在连接前 **fail**，并输出不含 DSN 的固定错误。当前行为会让错误的 `5432` 配置、拼错 DSN 或 CI secret 损坏显示为 PASS/SKIP，长期失去数据库验收。

#### I5. concurrent migration 的“成功 SQL + ledger insert”部分执行语义只靠当前 SQL 偶然幂等

**位置：** `apply` outside-transaction 分支。

若 SQL 成功后进程退出或 ledger insert 失败，下一轮必然重放整份 migration。当前 `000002` 因 `IF NOT EXISTS` 通常可恢复，但 runner 的发现规则允许未来任意包含 `CREATE INDEX CONCURRENTLY` 的多语句 migration；其中其他语句未必幂等。runner 会把整份文件置于事务外并整体重放，存在重复数据/对象漂移风险。至少应限制 concurrent migration 的可接受结构、明确可恢复契约并增加 failure-injection 测试。

#### I6. lifecycle 的 cross-user 测试可能只命中 usage 唯一约束，未证明 owner FK

**位置：** `cloud-api/integration/postgres_business_test.go`。

测试先为 `first.ID` 写入 usage，再用另一个 user 对同一 `first.ID` 写 usage。第二次写入可能首先因 `UNIQUE(session_id)` 失败，即使 cross-user owner 约束缺失也会通过测试。应使用一个尚无 usage 的 session 验证 cross-user association，再独立验证同 session 第二条 usage conflict。

#### I7. 外层事务剥离器过于脆弱，未来格式变化会造成“SQL 已提交、ledger 未记录”

**位置：** `transactionalSQL` 的正则 `^\s*BEGIN...COMMIT...$`。

它仅支持无前导注释/BOM、简单 `BEGIN; ... COMMIT;` 的完整 envelope。若 migration 增加合法前导注释、`BEGIN TRANSACTION`、或尾随注释，剥离会失效；runner 已开启 transaction 后再次执行 SQL 内的 `COMMIT`，可能提前提交 DDL，随后 ledger insert/`Commit` 失败，留下未登记 schema。当前真实文件恰好匹配，但没有真实 PostgreSQL 测试或拒绝机制保证未来安全。应可靠解析/明确拒绝带事务控制语句的未知形式，而不是原样执行。

## Compose 评价

- 健康检查与 `service_completed_successfully` 依赖方向正确；host 只发布到 `15432`。
- 但迁移兼容旧 volume 的问题（I1）会阻断现有环境启动。
- migration DSN 放在 command 参数中，虽然代码不打印，仍可由 Compose 配置和容器进程元数据看到完整 URL；当前是已提交的 dev-only credential，但该模式不应推广到真实 secret。优先使用专门 secret/env 注入并避免在验收日志执行会展开凭据的命令。
- `go run` 依赖首次拉取 module，离线/网络不稳定时 migration service 无法启动；若目标是可靠启动链，应使用预构建、固定 digest 的应用镜像，而不是运行时编译浮动 tag。

## 验证证据评价

- 报告列出的 `go test -count=1 ./...`、`go vet ./...`、两个 build、`git diff --check`、`docker compose config --quiet` 均称 exit 0；报告也如实说明真实 DB cases 被 skip。
- `go test ./...` 不包含带 `integration` build tag 的 lifecycle test；另一次 `-tags=integration` 也因无 DSN skip。
- 因无原始命令输出/CI artifact，静态审查只能确认报告陈述，不能独立复核 exit code。
- 最关键的 PostgreSQL migration/lifecycle 验收未发生，故不能将 Task 4 认定完成。

## Task quality

**优点：** 代码范围集中、无 ORM/新 migration dependency、错误输出重视脱敏、checksum fail-fast 设计合理、普通 migration 将 ledger 与 SQL 同事务提交、测试 fixture 唯一值总体良好、报告诚实披露 DB skip 和 outside-transaction 窗口。

**不足：** 安全 guard 只放在测试而非执行入口；测试以 synthetic SQL 替代计划指定的真实 migration；对 concurrent-index invalid 状态、并发执行、旧 volume adoption 和事务 envelope 异常缺少设计与测试；危险 DSN 配置被 skip 而非 fail。综合质量不足以承担高风险 migration runner。

## 必须修复后再验收

1. 在 migration 执行入口实现不可绕过且脱敏的目标 guard：loopback 只允许 `127.0.0.1:15432`；为 Compose 内部 `postgres:5432` 建立明确、最小化的受控策略。
2. 设计并测试 concurrent index 的 invalid-index 恢复与 ledger 一致性；不得仅依赖 `IF NOT EXISTS`。
3. 增加 advisory lock，覆盖两个 runner 并发启动。
4. 用空的 dedicated PostgreSQL 数据库真实执行仓库 `000001`–`000003`，验证 index 有效、ledger/checksum/order/幂等及 fail-fast。
5. DSN 缺失才 skip；DSN 已设置但不符合安全约束必须在连接前 fail。
6. 处理旧 Compose volume 的安全 adoption/明确兼容方案。
7. 修正 cross-user lifecycle case，使 owner FK 与 usage 唯一性分别被独立验证。
8. 修复后重新运行完整 Go gate、integration-tag tests、真实 migration tests、Compose config 检查，并保留脱敏证据。
