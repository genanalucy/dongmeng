# 管理控制台 API 对齐说明

本文件记录管理控制台可发送的精确请求，以及 UI 会验证的响应字段。它**不是对 Go 服务的实现声明**：`cloud-api/internal/http/router.go` 当前只实际实现平台探针；下述管理读取端点均可能返回 `501 {"error":"not_implemented","request_id":"…"}`。页面会显示受控待接入状态，不会虚构、缓存或降级展示业务数据。

所有请求：

- API Base URL 由当前会话配置，例如 `http://127.0.0.1:8080`。
- `Accept: application/json`。
- 配置过令牌时：`Authorization: Bearer <admin-access-token>`；令牌仅在 `sessionStorage` 和内存中保存。
- 错误统一读取 `{"error":"<code>","request_id":"<id>"}` 与 `X-Request-ID`。
- `401` 显示“需要管理员令牌”；`403` 显示“权限不足”；`404` 或 `501` 显示“接口待接入”；其他/网络错误可重试。

## 当前已实现并展示

### `GET /api/v1/health`

```json
{"status":"ok","service":"cloud-api"}
```

UI 使用 `status`；服务端的有效值为 `ok`。

### `GET /api/v1/ready`

```json
{"status":"ready","service":"cloud-api"}
```

UI 使用 `status`；服务端的有效值为 `ready`。服务未就绪时为 `503` 错误响应。

### `GET /api/v1/config`

```json
{"environment":"test","service":"cloud-api","version":"test-version"}
```

UI 要求并显示三个非空字符串字段：`environment`、`service`、`version`。

## 已注册、当前仍为 501 的管理读取候选契约

这些数组形状来自 `internal/domain.Store` 的 `ListUsers(context.Context, limit, offset) ([]User, error)` 和 `ListAuditLogs(context.Context, limit, offset) ([]AuditLog, error)`，而不是声称当前 router 已实现。控制台在状态码为 `200` 时会严格验证以下形状；字段缺失或类型不匹配时显示 `invalid_response_contract`，避免误展示。

### `GET /api/v1/admin/users`

候选成功响应为裸数组：

```json
[
  {
    "id":"00000000-0000-0000-0000-000000000001",
    "email":"admin@example.com",
    "role":"admin",
    "created_at":"2026-01-02T03:04:05Z"
  }
]
```

每项必需字段：`id`、`email`、`role`、`created_at`（均为非空字符串）。UI 不会猜测分页 query、包装对象或筛选参数；后端若需要分页，应先把最终契约写入 `cloud-api/API.md` 后再接入。

### `GET /api/v1/admin/audit-logs`

候选成功响应为裸数组：

```json
[
  {
    "id":"00000000-0000-0000-0000-000000000010",
    "admin_id":"00000000-0000-0000-0000-000000000001",
    "action":"code_batch.created",
    "target_type":"code_batch",
    "target_id":"00000000-0000-0000-0000-000000000020",
    "metadata":{},
    "created_at":"2026-01-02T03:04:05Z"
  }
]
```

每项必需字段：`id`、`admin_id`、`action`、`target_type`、`metadata`（对象）、`created_at`；可选 `target_id`（字符串）。UI 有意不展开 `metadata`，避免在未设计脱敏规则前展示任意审计细节。

## 未定义管理员读取契约（保持待接入）

下列区域**不发送业务请求**：

| 区域 | 现状 | 需要后端先定义的读取/操作契约 |
| --- | --- | --- |
| 权益 | 仅有用户自身 `GET /api/v1/entitlements/current` 预留路由 | 管理员权益列表/详情、授予、撤销、审计语义 |
| 兑换码批次 | 仅有 `POST /api/v1/admin/code-batches` 预留创建路由 | 批次列表/详情、创建请求体、有效期/数量、失效/撤销语义 |
| 翻译会话与用量 | 只有客户端写入预留路由 | 管理员会话读取、用量聚合、时间范围与分页 |
| 反馈 | 只有写入及按工件 ID 读取预留路由 | 管理员反馈列表、访问控制、脱敏与统计字段 |

后端代理应先将最终端点、认证要求、分页与响应 envelope 写入 `cloud-api/API.md`；前端再将受控状态替换为已验证的 typed client 调用和组件。
