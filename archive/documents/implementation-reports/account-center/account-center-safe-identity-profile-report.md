> 历史资料：保留当时的设计、实现和验证记录；旧路径、端点、命令与状态不代表当前项目。现行入口见[文档导航](../../../../docs/README.md)。

# Account Center 安全身份资料接口报告

## 实现

新增受 access token 保护的 `GET /api/v1/account/identity`。

- Store 仅按 JWT subject 查询启用用户。
- 返回 `username`、实际 `email` 和 `masked_phone`；完整手机号、密码、hash、token 均不会进入 DTO。
- 手机号掩码格式为 `+86138****8000`；legacy 无手机号时省略 `masked_phone`，用户名回退为 `旧版用户`。
- PATCH 身份更新接口保持完整四字段请求契约不变。

## 测试与验证

- RED：新 profile 测试在 domain `AccountIdentity`/Store 方法尚未定义时编译失败。
- GREEN：`go test ./internal/http -run 'TestAccountIdentityProfile'` 通过。
- 全量：`go test ./...` 通过。
- `git diff --check` 通过。

未连接数据库、未运行 migration 或服务，未 push。
