# 多身份认证设计

**日期：** 2026-09-02  
**状态：** 已确认，待实施  
**范围：** Cloud API、PostgreSQL、Android Debug 内测认证界面

## 目标

注册统一要求**用户名、邮箱、中国大陆手机号、密码、确认密码**；三项身份均全局唯一。登录使用一个“邮箱 / 手机号 / 用户名”输入框，服务端自动识别身份类型后以密码认证。

## 非目标

- 不接入短信、验证码、账号找回或国际手机号。
- 不修改翻译 session、JWT claim、Agent、音频、权益、Keystore token 存储。
- 不删除旧用户、旧邮箱或 refresh token；不操作非受控数据库。

## 身份规则

- **用户名**：trim 后 ASCII 小写；3–32 位 ASCII 字母、数字、下划线；不得纯数字；唯一。
- **邮箱**：沿用现有邮件规范化/校验；唯一。
- **手机号**：仅大陆 11 位、`1[3-9]` 开头；接受裸号码或 `+86`，统一存为 `+86` + 11 位；唯一。
- **登录识别顺序**：可规范化为大陆手机号则手机号；否则合法邮箱则邮箱；否则合法用户名则用户名；其余为无效凭据。
- 不存在、密码错误、disabled 或身份格式不合法均返回同一认证失败结果；不得透露身份类别或账号是否存在。

## 数据与兼容性

- `users.username`、`users.phone` 保持 nullable，以兼容历史邮箱用户；部分唯一索引保持。
- 新注册必须写入真实、已验证语法的 email，**不再生成内部保留 email**。
- 既有历史邮箱用户可继续邮箱登录和 refresh 恢复；无 username/phone 的历史记录无需回填。
- migration 仅在受控 PostgreSQL `127.0.0.1:15432` 执行；严禁连接或操作 `5432`。

## Cloud API

### 注册

`POST /api/v1/auth/register`

```json
{"username":"alice_01","email":"alice@example.test","phone":"13800138000","password":"Aa123456"}
```

服务端是最终校验者；密码须为有效 UTF-8 的 8–256 bytes，至少一个 ASCII 大写、小写、数字。用户名、邮箱或手机号冲突统一为安全冲突结果。公共响应不回显 email 或完整 phone/密码。

### 登录

`POST /api/v1/auth/login`

```json
{"identifier":"alice@example.test","password":"Aa123456"}
```

服务端识别 `identifier`，统一失败为 `401 invalid_credentials`。成功后的 token、refresh rotation、session 授权契约不变。

### 验证码预留

`POST /api/v1/auth/phone-verifications` 与 `/confirm` 继续仅校验 phone 格式，固定返回 `503 verification_not_enabled`；不发送、不存储、不要求 Android 调用。

## Android

- 登录：标签“邮箱 / 手机号 / 用户名”与密码。
- 注册：用户名、邮箱、手机号、密码、确认密码，按钮“注册并登录”。
- 本地识别/校验与 Cloud 一致；无效输入不派发网络请求。客户端登录只做 identifier 非空与可识别格式检查，密码只要求非空。
- 错误固定且不含输入值：登录“账号或密码错误。”；注册冲突“该用户名、邮箱或手机号暂不可用，请更换后重试。”。
- 不显示/调用验证码；密码不写入持久 UI 状态、日志或错误文案。

## 安全与验收

- 不记录完整手机号、邮箱、明文密码、token、DSN 或验证码。
- 新注册四字段缺失/非法/冲突均不可创建用户或权益。
- 手机号、邮箱、用户名三种 identifier 均可登录；交叉类型、未知和错误密码都得到相同失败文案。
- 旧邮箱用户可邮箱登录并通过 refresh 恢复；公开 user 响应不泄露 email/phone。
- 运行 Cloud/Android 测试、lint、构建；仅在安全 DSN 提供时运行真实 PostgreSQL E2E；真机/辅助功能验收单独记录。
