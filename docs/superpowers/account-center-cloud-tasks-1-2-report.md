# Account Center Cloud Tasks 1+2 报告

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
