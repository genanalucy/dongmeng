# 账户中心设计

**日期：** 2026-09-02  
**状态：** 已确认，待实施

## 目标

登录后的“我的”以用户名为主身份，提供权益、使用情况、历史与服务设置入口，并允许用户以当前密码确认后修改用户名、邮箱、手机号。

## 非目标

- 不修改翻译 session、音频、Agent、JWT claim、Keystore token 存储或支付系统。
- 不实现短信/邮箱验证码、密码修改、账号删除或公开个人资料。
- 不记录完整手机号、明文密码、token、DSN 或验证码。

## Cloud API

### 账户概览

`GET /api/v1/account/overview`（需 access token）返回：

- `username`：必有；legacy 用户为安全固定显示名，不回显邮箱。
- `entitlement`：kind、starts_at、expires_at、active、remaining_seconds。
- `usage`：累计翻译秒数、会话次数、最近使用时间（可空）。

公开响应不返回 email、完整手机号或密码。

### 使用记录

`GET /api/v1/account/usage?limit=1..50&offset>=0` 返回当前用户自身的会话使用摘要：开始/结束时间、持续秒数、语言对（若已记录）。严格按 token subject 过滤。

### 账户身份更新

`PATCH /api/v1/account/identity`

```json
{"username":"alice_01","email":"alice@example.test","phone":"13800138000","current_password":"Aa123456"}
```

- 四字段必填；用户名、邮箱、手机号校验/规范化与注册一致，且全局唯一。
- 先按当前 access-token subject 读取密码 hash，验证 `current_password`，再在单一事务内更新三项身份。
- 密码错误、格式错误、账号不存在或 disabled 均为通用 `invalid_credentials` / `invalid_request`；唯一冲突为通用 `conflict`，不说明字段。
- 成功只返回安全公开用户资料；不吊销 refresh token（身份修改不改变会话主体）。
- 旧 legacy 用户可首次补全 username/phone；email 必须保留。

## 数据与使用计算

- 复用现有 translation sessions / usage records；不保存音频。
- 使用统计必须使用授权用户 ID 聚合，不接受客户端 user ID。
- 若现有表缺少可准确聚合的 duration/语言字段，新增非破坏性 migration；仅在 `127.0.0.1:15432` 受控库执行。

## Android

### 我的主页

- 顶部：用户名与“账户与权益”。不显示邮箱或完整手机号。
- 权益卡：试用/订阅状态、到期时间、剩余时长。
- 使用情况卡：累计时长、会话次数、最近使用。
- 入口：使用与权益、历史记录、账户设置、服务设置、退出登录。

### 使用与权益页

- 展示权益详情、使用统计和分页使用摘要；加载/空态/错误使用固定安全文案。

### 账户设置

- 输入用户名、邮箱、手机号、当前密码；初始值可展示 username/email，手机号仅掩码展示且修改需重新完整输入。
- 使用与注册一致的字段规则；提交前本地校验，无效不发请求。
- 成功刷新 overview，返回我的主页；不显示/持久化当前密码。

## 安全、兼容与验收

- 每个账户接口需 access token 且只访问 subject 自身数据。
- 旧邮箱用户、refresh token、管理员能力与现有 entitlement/translation session 不回归。
- 单测覆盖 owner isolation、聚合边界、更新事务回滚、密码错误、唯一冲突、敏感响应脱敏。
- Android 覆盖状态映射、无效零派发、身份更新 dispatch、Compose 语义、48dp 与 TalkBack 标签。
- 运行 Cloud/Android 测试、lint、Debug 构建；只有安全 DSN 时真实 PostgreSQL E2E；真机验收独立记录。
