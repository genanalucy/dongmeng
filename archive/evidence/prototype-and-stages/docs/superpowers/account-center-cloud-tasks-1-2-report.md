# Account Center Cloud Tasks 1+2 报告

> **历史归档（任务 05）**：原路径 `docs/superpowers/account-center-cloud-tasks-1-2-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

日期：2026-09-02

## 范围

实现认证账户中心 Cloud API：

- `GET /api/v1/account/overview`
- `GET /api/v1/account/usage?limit=1..50&offset>=0`
- `PATCH /api/v1/account/identity`

未修改 Android、迁移或运行时数据库服务，未执行 migration、E2E、设备操作或推送。

## 实现

- 复用现有 `users`、`entitlements`、`translation_sessions` 和 `usage_records` schema；无需迁移。
- 概览按 access-token subject 聚合用量，返回安全用户名回退、最近权益及 active/remaining seconds。
- 使用记录严格按 subject 分页查询 session 摘要。
- 身份更新复用既有 username/email/phone/password 规范化；事务内锁定启用用户、验证当前密码并原子更新三项身份。凭据失败为 `invalid_credentials`，唯一约束冲突为通用 `conflict`，成功响应只含公开 user DTO。
- 未改变 refresh、session 或 admin 路由行为。

## 验证

- RED：`go test ./internal/http -run 'TestAccount'`，因新增 domain 类型未实现而失败。
- GREEN：`go test ./internal/http -run 'TestAccount'` 通过。
- 全量：`go test ./...` 通过。
- `git diff --check` 通过。

## 限制

未运行 PostgreSQL E2E 或 migration；本次 schema 已具备所需字段，按约束未启动数据库或迁移服务。
