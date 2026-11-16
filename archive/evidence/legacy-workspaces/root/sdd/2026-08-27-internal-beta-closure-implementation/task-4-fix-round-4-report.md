> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-fix-round-4-report.md`；原始 SHA-256 `b70b45b1029331b052b6d11eaa5e9cd28b564d8770863ea5abcb902767ecb819`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 4 report

工作树：`[旧临时路径]`

提交：`b1aa16d fix(cloud): reject ambiguous migration SQL`

## 修复

- 受限 concurrent-index grammar 明确建模 `NullsNotDistinct=false` 与 `Immediate=true`。
- catalog 查询读取 `pg_index.indnullsnotdistinct`、`pg_index.indimmediate`，并在 exact matcher 中 fail-closed 比较。
- 增加 dedicated PostgreSQL case：预先建立同名 valid `NULLS NOT DISTINCT` unique index 时 migration 必须失败且 ledger 保持空。测试先读取 `server_version_num`；低于 PostgreSQL 15 时以 capability 原因明确 skip，未以迁移成功替代 skip。
- `sqlWords` 改为 `([]string, error)`；普通单引号、双引号、嵌套块注释、完整 dollar quote 仍按安全路径跳过。
- 遇到 token-boundary 的 `E'`/`e'` escape string 或 `U&'`/`u&'` Unicode escape form 时返回安全错误，拒绝 migration；未闭合 quoted/comment/dollar quote 也拒绝。
- 外层 transaction envelope 的 body 使用同一错误路径重新扫描；`BEGIN; SELECT E'foo\\'bar'; COMMIT; ...; COMMIT;` 回归断言被拒绝且不返回可执行 body。
- 添加 identifier 边界回归，确认 identifier 内 `e` 或 `$tag$` 不误判为 string prefix/dollar quote。
- non-concurrent migration 在建立 runner-owned transaction 前完成 `transactionalSQL` 预检，因此被拒绝的 ambiguous SQL 不会执行 migration statement。

## 无 DB 验证

均以 `CLOUD_API_TEST_DATABASE_URL` 未设置执行；未连接数据库。

- `env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./internal/migrate`：通过。
- `env -u CLOUD_API_TEST_DATABASE_URL go test -count=1 ./...`：通过。
- `env -u CLOUD_API_TEST_DATABASE_URL go test -tags=integration -count=1 ./integration`：通过。
- `go vet ./...`：通过。
- `go build ./cmd/migrate ./cmd/cloud-api`：通过。
- targeted unsafe-DSN + scanner tests：通过；dedicated `NULLS NOT DISTINCT` case 因 DSN 未设置明确 skip。
- `docker compose config --quiet`：通过，仅配置解析，未启动或停止服务。
- `git diff --check`：通过。
- 白名单检查：提交仅含 `cloud-api/internal/migrate/runner.go` 与 `cloud-api/internal/migrate/runner_test.go`。

## 约束与剩余风险

- 未启动或停止 Compose，未连接 PostgreSQL，未执行真实 DB/integration 验收。
- 因真实 DB 禁止，`NULLS NOT DISTINCT` 的 dedicated case 尚未在 PostgreSQL 15+ 实例上实际执行；其 capability skip 行为已由测试实现并在无 DSN gate 中显示。
- 工作树原有未跟踪文件 `task-4-fix-round-2-report.md` 未修改、未暂存、未提交。
