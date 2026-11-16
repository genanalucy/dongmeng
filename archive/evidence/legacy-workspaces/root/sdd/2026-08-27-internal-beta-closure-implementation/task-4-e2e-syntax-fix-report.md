> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-e2e-syntax-fix-report.md`；原始 SHA-256 `ad50f40035f0260073779633e58a5127613d9f777abc9a1409fa81ef0b645d5a`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4：PG17 E2E SQL 语法最小修复报告

## 范围

- Worktree：`[旧临时路径]`
- 仅修改：`cloud-api/internal/migrate/runner.go`、`cloud-api/internal/migrate/runner_test.go`
- 未启动 Docker、未连接数据库、未设置或使用数据库 DSN、未派遣子代理。

## 修复

1. catalog CTE 的 `key_column` 列别名由 PostgreSQL parser keyword `collation` 改为 `collation_oid`，并同步两个 collation 比较引用。
2. `NULLS NOT DISTINCT` fixture 使用正确的 PG15+ `CREATE INDEX` 子句顺序：
   ```sql
   CREATE UNIQUE INDEX events_email_idx ON <qualified-table>(email) NULLS NOT DISTINCT
   ```
   index name 保持未 schema-qualified，table name 保持 schema-qualified。
3. 新增纯离线回归断言，覆盖非关键字 catalog alias、旧 alias 不再引用，以及 fixture SQL 的精确语法与限定边界。

## TDD 与验证

- 红阶段：新增离线断言后，因 `concurrentIndexDefinitionQuery` 与 `nullsNotDistinctFixtureSQL` 尚不存在而编译失败，确认测试能检测缺失修复。
- 绿阶段：目标离线测试通过。
- 以下命令均以未设置 `CLOUD_API_TEST_DATABASE_URL` 运行；数据库依赖用例仅走既有 skip 路径：
  ```text
  go test ./internal/migrate -run '^TestPG17SyntaxFixturesAvoidReservedAliasAndPlaceNullsClauseAfterKeys$' -count=1
  go test ./... -count=1
  go test -tags=integration -count=1 ./internal/migrate ./integration
  go vet ./...
  go build ./cmd/cloud-api
  go build ./cmd/migrate
  docker compose config -q
  git diff --check
  ```

未执行真实 PostgreSQL E2E；因此本报告不声称已动态验证 PG17 server parser。

## 提交边界

仅暂存并提交两个源测试文件。此报告及 worktree 中既有两份未跟踪报告不提交；构建产生的 `cloud-api/cloud-api` 与 `cloud-api/migrate` 已清理。
