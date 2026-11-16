# Task 1 Report: Cloud 手机号/用户名领域模型与安全 migration

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-01-phone-authentication-implementation/task-1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

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
