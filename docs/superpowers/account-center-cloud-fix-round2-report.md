# Account Center Cloud 修复报告（Round 2）

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
