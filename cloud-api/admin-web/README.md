# Cloud API 管理控制台

独立的 React + TypeScript + Vite 管理界面，不修改仓库根目录的用户翻译 Web。

## 运行

```bash
npm install
npm run dev
```

默认 API Base URL 是 `http://127.0.0.1:8080`，可在“连接设置”中调整。需要跨域访问时，Cloud API 的精确 CORS allowlist 必须包含此页面的 Origin。

## 登录与安全边界

- 未登录时只显示中文管理员登录页。登录请求为 `POST /api/v1/auth/login`；账号和密码只存在于表单内存，并仅在请求正文中提交。
- 登录成功后，控制台立刻请求 `GET /api/v1/users/me`，仅在返回 `role: "admin"` 时进入管理界面；其他角色和验证失败都会清理本次会话。
- access token 与 refresh token 仅保存于内存和浏览器 `sessionStorage`；不使用 `localStorage`，不写入 URL、日志或代码。
- 受保护请求收到 401 时，控制台用 refresh token 调用 `POST /api/v1/auth/refresh` 自动轮换一次并重试原请求；失败后清理会话并要求重新登录。
- 点击“退出登录”会调用 `POST /api/v1/auth/logout`，随后无论服务响应如何都会清理本地会话。
- “连接设置”仅用于 API Base URL 诊断，不提供任何手动凭据输入。

开发服务器中，账号字段输入 `admin` 会映射到 `admin@123.com`。此映射仅在 Vite 开发模式生效；生产构建不会硬编码或启用该邮箱。

## 生产部署环境变量

生产环境**无需**配置开发别名变量。应保持 `VITE_ENABLE_ADMIN_DEV_ALIAS` 未设置或为 `false`；即使错误设为 `true`，生产构建也会因为 `import.meta.env.DEV` 为 `false` 而拒绝别名映射。

如果需要为本地开发覆盖默认映射，可在开发启动环境中设置：

```text
VITE_ENABLE_ADMIN_DEV_ALIAS=true
VITE_ADMIN_DEV_ALIAS_EMAIL=admin@123.com
```

不要在生产环境设置 `VITE_ADMIN_DEV_ALIAS_EMAIL`，也不要把账号、密码或任何登录凭据写入环境文件。

## 当前接口映射

| 界面 | 实际接口 | 当前行为 |
| --- | --- | --- |
| 登录 | `POST /api/v1/auth/login` | 取得短期访问凭据与刷新凭据 |
| 身份验证 | `GET /api/v1/users/me` | 仅允许 `role=admin` |
| 登录状态续期 | `POST /api/v1/auth/refresh` | 401 时只自动尝试一次 |
| 退出登录 | `POST /api/v1/auth/logout` | 撤销服务端会话并清理浏览器会话 |
| 概览：服务存活 | `GET /api/v1/health` | 显示真实 `status` |
| 概览：数据库就绪 | `GET /api/v1/ready` | 显示真实 `status` 或错误 |
| 概览：版本/环境 | `GET /api/v1/config` | 显示真实 `service`、`version`、`environment` |
| 用户 | `GET /api/v1/admin/users` | 当前 501；未来 `200` 时仅显示符合候选契约的真实用户列表 |
| 兑换码 | `POST /api/v1/admin/code-batches` | 当前 501，受控“接口待接入”状态 |
| 审计 | `GET /api/v1/admin/audit-logs` | 当前 501；未来 `200` 时仅显示符合候选契约的真实审计列表 |

权益授予/撤销、用户状态操作、翻译会话/用量读取、反馈列表/统计接口尚未定义或实现，因此不展示模拟数据、不提交业务操作。精确请求与响应字段、状态码处理和候选契约见 [`API_EXPECTATIONS.md`](./API_EXPECTATIONS.md)。

## 验证

```bash
npm test
npm run typecheck
npm run lint
npm run build
```
