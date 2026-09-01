# Task 1 Report: Cloud 手机号/用户名领域模型与安全 migration

## 实施

- 新增 `domain.Username`：去除首尾空白、规范为 ASCII 小写，限制为 3–32 个 ASCII 字母、数字或下划线。
- 新增 `domain.Phone`：接受 11 位大陆手机号或已规范的 `+86` 格式，统一为 `+86` + 11 位。
- 新增 `domain.PhoneCredentialsInput` 与 `ParsePhoneCredentials`：密码要求至少 8 bytes，且包含 ASCII 大写字母、小写字母和数字。
- 保持既有 `ParsePassword` 的长度策略不变。此规则是兼容性裁定：现有 email 注册/登录仍使用 `ParseCredentials`；强密码策略只用于新增手机号凭据，避免本任务改变 legacy 行为。
- 新增 `000004_phone_authentication` up/down migration：为 `users` 增加可空 `username` 与 `phone`，以规范化约束和仅非空的唯一索引保护新数据；不重写 email 或历史数据。down 仅移除此 migration 的索引、约束与列。
- migration runner 测试更新为发现版本 `000004`。runner 的既有 host guard 已验证只允许 `127.0.0.1:15432`（或受控 Compose 显式 opt-in）；未运行 migration 或连接数据库。

## TDD

RED：`go test ./internal/domain ./internal/migrate` 因缺少 `ParsePhoneCredentials`、`ParsePhone`、`ParseUsername` 和 `000004` migration 失败。

GREEN：实现后 focused 测试通过。

## 验证

在 `cloud-api` 目录执行：

```text
go test ./internal/domain ./internal/migrate
ok   github.com/dngmeng/cloud-api/internal/domain
ok   github.com/dngmeng/cloud-api/internal/migrate

go test ./...
ok   github.com/dngmeng/cloud-api/cmd/migrate
ok   github.com/dngmeng/cloud-api/integration
ok   github.com/dngmeng/cloud-api/internal/auth
ok   github.com/dngmeng/cloud-api/internal/config
ok   github.com/dngmeng/cloud-api/internal/domain
ok   github.com/dngmeng/cloud-api/internal/http
ok   github.com/dngmeng/cloud-api/internal/migrate
ok   github.com/dngmeng/cloud-api/internal/store
```

`git diff --check` 通过。未执行数据库 migration、down migration 或外部服务。
