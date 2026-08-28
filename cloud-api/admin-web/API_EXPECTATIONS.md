# 管理控制台 API 契约

本文件记录管理控制台与 Cloud API 的稳定读取契约。所有业务响应使用 `Cache-Control: no-store`，错误统一为 `{ "error": "code", "request_id": "..." }`，响应包含 `X-Request-ID`。

所有管理员请求使用 `Accept: application/json` 和 `Authorization: Bearer <access-token>`。access token 失效返回 `401`，有效但非管理员返回 `403`；UI 不以客户端角色判断代替后端授权。

## 用户列表

`GET /api/v1/admin/users?q=<email>&limit=<n>&offset=<n>`

- `q` 可选，按邮箱搜索，最多 254 个字符；空值等同未提供。
- `limit` 可选，默认 `50`，最大 `100`；无效或超范围值使用默认值。
- `offset` 可选，默认 `0`；负值按 `0` 处理。
- 成功响应为 object envelope：

```json
{"users":[{"id":"uuid","email":"admin@example.com","role":"admin","created_at":"2026-01-02T03:04:05Z"}]}
```

每项包含非空字符串 `id`、`email`、`role`、`created_at`。

## 审计日志

`GET /api/v1/admin/audit-logs?limit=<n>&offset=<n>`

- `limit` 默认 `50`，最大 `100`；`offset` 默认 `0`，负值按 `0` 处理。
- 成功响应为 object envelope：

```json
{"audit_logs":[{"id":"uuid","admin_id":"uuid","action":"user.disabled","target_type":"user","target_id":"uuid","metadata":{},"created_at":"2026-01-02T03:04:05Z"}]}
```

`target_id` 可选。HTTP 边界将存储层开放 `metadata` 投影为固定安全对象 `{}`，不会将原始 metadata、secret 或任意嵌套值发送到浏览器。

## 前端行为

控制台固定每页请求 `limit=50`；用户页面提供提交式邮箱搜索，提交或清空会将 `offset` 重置为 `0`。用户与审计页面均提供上一页/下一页；没有 total 时，返回条数小于 50 会禁用下一页，整页后的空页会恢复上一页而不会卡死。401 显示登录失效，403 显示权限不足，其他错误显示受控重试状态。

平台探针 `/api/v1/health`、`/api/v1/ready`、`/api/v1/config` 的契约见 `cloud-api/API.md`。
