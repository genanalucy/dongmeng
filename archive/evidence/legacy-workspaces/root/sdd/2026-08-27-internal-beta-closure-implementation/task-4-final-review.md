> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-final-review.md`；原始 SHA-256 `4da66c5600046715835f6cda9d98cf2bab6a140a6432248876e7df3adee6d8e8`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 最终只读复审

审查对象：`[旧临时路径]`，HEAD `00ebf68`；增量范围 `17a05ad..00ebf68`，重点提交 `ae6e3b1`、`00ebf68`。

约束：本轮未修改或提交目标 worktree，未启动/停止 Docker，未连接数据库。仅静态检查代码、提交差异、既有审查报告，并核对真实 PG17 日志 `[旧临时路径]`。本文不复述或输出任何密码、完整 DSN 或其他秘密。

## Spec compliance

### `ae6e3b1`：普通 DML `UPDATE ... SET` 与事务控制

符合。

- `hasTransactionControl` 不再把每个 `SET` token 一概视为事务控制，因此仓库 migration 中普通 DML `UPDATE ... SET ...` 可以进入 runner 管理的事务。
- 真实事务控制仍 fail closed：
  - `SET TRANSACTION ...` 被拒绝；
  - `SET SESSION CHARACTERISTICS AS TRANSACTION ...` 被拒绝；
  - `BEGIN`、`COMMIT`、`END`、`ROLLBACK`、`ABORT`、`START`、`SAVEPOINT`、`RELEASE`、`PREPARE` 的既有拒绝逻辑未削弱。
- token 来自 `sqlWords` 的可执行 SQL 词序列，注释、字符串及 dollar-quoted function body 不会被当成事务控制；既有 escape/Unicode-escape fail-closed 约束保持不变。
- 新增单测同时覆盖普通 `UPDATE ... SET` 接受和第二种真实 transaction `SET` 拒绝。更关键的是，PG17 证据中 repository migration 测试与 lifecycle 均通过，说明包含该 DML 的真实 repository migrations 已实际执行，而非仅通过词法单测。

### `00ebf68`：lifecycle migration 顺序、路径、cleanup 与 DSN 安全

符合。

- `TestPostgresBusinessLifecycle` 在任何 `store.Open` 之前调用 `migrate.Run`，且 migration 失败立即终止；业务 store 不可能先于 schema migration 打开。
- migration 目录由测试包工作目录按 `../../migrations` 求绝对路径，并在使用前 `os.Stat`；对 `cloud-api/integration` 测试包而言解析到 repository 根目录的 `migrations`，不是临时或 synthetic fixtures。
- migration 使用与 lifecycle 相同的已校验 DSN 和 `public` schema；未引入另一个连接目标。
- DSN 仍先经 `migrate.ValidateDatabaseURL(dsn, false)`：只接受隔离的 `127.0.0.1:15432` 目标，并继承此前已审查的 multi-host、query override、runtime parameter、service/socket/alias 等 fail-closed 防护。验证错误为固定文本，测试失败路径也不打印原始 DSN。
- cleanup 注册顺序与 Go `t.Cleanup` 的 LIFO 语义正确：fixture 数据清理先运行，随后关闭 raw pool，最后关闭 store pool。cleanup 使用独立的 background timeout context，不依赖主测试 context；batch/code 在 user 前删除，用户相关记录依赖 FK cascade 清理。迁移 schema/ledger 本身属于隔离数据库基础设施，不应由该业务测试删除。
- lifecycle 中 owner FK 与 session usage 唯一约束已用不同阶段验证：先对尚无 usage 的 owner session 尝试 cross-user 写入，再由 owner 写第一条 usage，最后验证第二条冲突，未退化为只命中唯一约束。

## Task quality

质量良好，变更集中且与根因一致：

- `ae6e3b1` 是最小词法分类修正，没有重新放宽未知事务 envelope，也没有绕开 runner transaction。
- `00ebf68` 复用生产 migration runner 和 repository migrations，没有复制 schema SQL 或添加测试专用 migration 路径。
- lifecycle 明确固定了 migration-before-store 的依赖顺序，并保持 DSN 脱敏及隔离目标约束。
- 测试证据同时包含 focused integration/migration cases 和 Cloud API 全量 Go gates；没有以 mock 或 synthetic-only 结果替代 repository migration 的真实 PostgreSQL 验收。

## Critical

无。

## Important

无。

未发现这两个提交重新引入此前已修复的宿主 `5432` 误连、DSN/runtime parameter 绕过、事务提前提交、concurrent index/ledger 不一致、旧 schema 静默 adoption、并发 runner 或 lifecycle owner-FK 覆盖缺口。

## Minor

无阻塞项。

补充说明：新增普通 DML 单测本身只验证 `transactionalSQL` 分类，没有单独断言 SQL 在数据库中的最终值；但真实 PG17 的 repository migration 与 lifecycle 通过，已为仓库当前 `UPDATE ... SET` migration 提供执行级证据，因此不构成 Important。

## 真实 E2E 是否可认定通过

**可以认定通过。**

依据 `[旧临时路径]` 的脱敏结果：

- `TestPostgresBusinessLifecycle` 在 disposable PostgreSQL 17 上通过；
- repository migration 的 checksum、幂等、fail-before-later-migration、并发串行化与恢复、existing-schema/empty-ledger 拒绝、concurrent-index 异常与恢复等真实数据库测试通过；
- migration package、integration package及 Cloud API 全量 Go gates 均通过；
- 日志最终明确记录 lifecycle、migration 和完整 Cloud API gates 已在 disposable PG17 上完成，且测试容器已移除。

该证据与 HEAD `00ebf68` 的代码顺序吻合，并实际覆盖了 repository migrations 先执行、随后 `store.Open` 和业务 lifecycle 的目标路径。没有 Critical 或 Important 阻止 Task 4 closure。

**最终结论：通过。真实 PG17 E2E 可认定通过，Task 4 可关闭。**
