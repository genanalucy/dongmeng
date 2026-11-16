# Account Center Cloud 修复报告（Round 2）

> **历史归档（任务 05）**：原路径 `docs/superpowers/account-center-cloud-fix-round2-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## 修复

- 响应契约测试现为逐路径递归 allowlist：每个对象与 array element 都声明允许键，任何未知嵌套键均失败；保留 email、phone、password、hash、current_password、access/refresh token、audio/audio_url、transcript、object_key、artifact 等实际 JSON 拼写的显式拒绝。
- Store 定义 legacy 谓词为 `username` 和 `phone` 均尚未填充的既有账户。首次补全时保留原 email（即使完整请求提供另一个有效 email），仅填充 username/phone；已完成身份的账户可以正常更新 email。
- 受控 PostgreSQL 集成测试请求不同 legacy email 并断言持久化原 email 保持不变，另验证 non-legacy email 更新。

## 验证

- RED：递归未知嵌套键测试在旧 helper 下失败。
- GREEN：`go test ./internal/http -run 'TestAccountResponseSchemaRejectsUnknownNestedKeys|TestAccountResponsesUseOnlySafeJSONKeys'` 通过。
- 全量：`go test ./...` 通过。
- `git diff --check` 通过。
- 未运行带 `integration` tag 的 PostgreSQL 路径：未设置安全 `CLOUD_API_TEST_DATABASE_URL`，因此未连接 DB、未执行 migration 或服务。
