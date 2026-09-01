# Account Center Cloud 修复报告（Round 1）

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
