# Account Center Cloud 修复报告（Round 1）

> **历史归档（任务 05）**：原路径 `docs/superpowers/account-center-cloud-fix-round1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## 修复

- `current_password` 仅要求非空，交由 Store 读取 subject 对应 hash 并 bcrypt 验证；不再套用注册密码强度规则。
- HTTP 测试覆盖弱 legacy 当前密码可进入身份更新，空密码仍为 `invalid_request`。
- 概览、用量、身份成功响应改以递归 JSON 解码 key allowlist 校验，禁止 email、phone、password、password_hash、current_password、token、audio、transcript、object_key、artifact 等键。
- 新增受控 PostgreSQL Store 集成测试：subject usage 聚合/分页、mixed owner 行、弱 legacy 密码、错误密码无变更、三种唯一冲突、被禁用/缺失用户和触发器强制写失败的原子回滚。

## 验证

- `go test ./...`：通过。
- `git diff --check`：通过。
- Store 集成测试带 `integration` build tag，并由 `CLOUD_API_TEST_DATABASE_URL` 的既有 `127.0.0.1:15432` 隔离 DSN 校验门控；本次没有设置该变量，因此没有连接数据库、没有运行 migration 或服务。

## 未覆盖说明

真实 PostgreSQL integration path 未在本次环境运行；测试已编写为安全环境显式启用时运行。
