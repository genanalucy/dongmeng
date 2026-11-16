> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-fix-round-3-report.md`；原始 SHA-256 `0f173bd7338b44448a8e6ae821f1f943a464fb89be540d3f42fc8454cd2bc9d2`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 3 报告

日期：2026-08-28
工作树：`[旧临时路径]`
基线：`2b33e77 fix(cloud): verify concurrent migration targets`

## 范围与约束

本轮只修改以下三处工作树内源码/测试：

- `cloud-api/internal/migrate/runner.go`
- `cloud-api/internal/migrate/runner_test.go`
- `cloud-api/integration/postgres_business_test.go`

未派遣子代理，未启动或停止 Compose，未连接任何数据库。已有未跟踪文件 `task-4-fix-round-2-report.md` 未修改、未暂存、未提交。本报告按要求位于工作树外，未纳入提交。

## 修复内容

### Critical：精确 concurrent-index catalog 匹配

受限 migration grammar 现在只允许默认选项的 btree `CREATE [UNIQUE] INDEX CONCURRENTLY`，并拒绝 predicate、`INCLUDE`、表达式、非 btree method、排序/null-order、collation 等复杂形式。

在 concurrent DDL 执行后、ledger 写入前，catalog 检查 fail closed 地验证：

- index `indisvalid`；
- schema、index name、table、unique、access method（严格 btree）；
- `indnkeyatts == 预期 columns 数`；
- `indnatts - indnkeyatts == 0`，即无 included attributes；
- 每个 key attribute 按序等于预期；
- 每个 key 使用默认 opclass；
- 每个 key 使用其 attribute 的 default collation 或不适用 collation；
- 每个 key 的 `indoption == 0`，即 ASC/默认 NULL ordering；
- `indpred IS NULL`。

任何缺行、未知或不匹配 catalog 状态都会阻止 ledger 写入。Dedicated-DSN case 用同名 valid index 的 `ON events(email) INCLUDE(id)` 对抗期望的 `ON events(email, id)`，明确覆盖相同 aggregate attribute 但 key/include 边界不同的场景。

### Important：transaction envelope 控制语句

`transactionalSQL` 剥离完整外层 `BEGIN … COMMIT` 后，重新使用 token scanner 检查 body。body 内任意 `BEGIN`、`COMMIT`、`END`、`ROLLBACK`、`ABORT`、`START`、`SAVEPOINT`、`RELEASE`、`PREPARE` 或 `SET` 都 fail closed。因此普通 migration DDL 与 runner 写入 ledger 仍由同一个 runner-owned transaction 原子提交。

回归覆盖：额外 `COMMIT` 的双 DDL、嵌套 `BEGIN`、envelope 内 `END`、envelope 内 `ABORT`，以及既有未 envelope 控制语句。dollar-quoted function body 保持不被误判。

### Minor：测试确定性与 cleanup

- advisory lock test 从固定 100ms sleep 改为轮询 `pg_catalog.pg_locks` 中可观察的未授予等待锁；有 5 秒明确超时。
- lifecycle fixture cleanup 在首个 fixture 创建前注册；未创建的 UUID 会安全跳过，已创建资源按 redemption code、batch、users 清理。

## 验证

全部在未设置 `CLOUD_API_TEST_DATABASE_URL` 且不连接数据库的条件下执行：

```text
env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./...
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go vet ./...
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go build ./cmd/cloud-api
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go build ./cmd/migrate
# exit 0

env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 ./integration
# exit 0；PostgreSQL lifecycle case 按设计 skip

env CLOUD_API_TEST_DATABASE_URL='[历史开发连接已脱敏] \
  go test -tags=integration -run '^TestPostgresBusinessLifecycle$' -count=1 -v ./integration
# 预期 exit 非 0；连接前以固定安全文本拒绝 multi-host target，输出不含 top-secret

docker compose config
# exit 0；仅解析配置，未启动/停止服务

gofmt -l <三处文件>
git diff --check
# 无输出 / exit 0
```

构建产生的本地二进制 `cloud-api/cloud-api` 与 `cloud-api/migrate` 已删除；最终 artifact 检查仅保留这三处改动和既有未跟踪 round-2 报告。

## 提交

仅白名单暂存三处源码/测试改动后已提交：

```text
48e8aea fix(cloud): fail closed concurrent migration validation
```

提交后工作树仅保留既有未跟踪 `task-4-fix-round-2-report.md`；本 round-3 报告位于工作树外，未提交。

## Deferred E2E

未执行真实 PostgreSQL dedicated-DSN 验收：repository migrations、精确 catalog 匹配、INCLUDE mismatch、invalid index recovery、advisory contention、legacy ledger recovery 与 business lifecycle。原因是本轮明确禁止连接数据库、启动/停止 Compose。该项仍需在 controller-approved 的专用精确 `127.0.0.1:15432` DSN 上新鲜执行后，才能作为 Task 4 PostgreSQL E2E acceptance 证据。
