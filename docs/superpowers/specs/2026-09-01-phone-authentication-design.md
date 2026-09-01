# 手机号认证迁移设计

**日期：** 2026-09-01  
**状态：** 已确认，待实施  
**范围：** Cloud API、PostgreSQL、Android Debug 内测认证界面

## 目标

将内测注册改为“用户名 + 中国大陆手机号 + 密码 + 确认密码”，将登录改为“手机号 + 密码”。当前不发送或校验短信验证码，但保留稳定的验证码 API 形状，后续可启用而不迁移用户数据或更改客户端手机号字段。

## 非目标

- 不做短信供应商接入、验证码存储、发送限流或手机号验证。
- 不支持国际号码、邮箱登录、用户名登录或账号找回。
- 不修改翻译 session、JWT claim、Agent、音频、Cloud 权益或既有 token 加密存储。
- 不清除旧用户或旧邮箱数据；不操作非受控数据库。

## 数据模型与迁移

新增不可逆 up migration：

```sql
ALTER TABLE users ADD COLUMN username text;
ALTER TABLE users ADD COLUMN phone text;
```

- `username`：新注册必填；去除首尾空白；长度 3–32；允许 ASCII 字母、数字、下划线；以规范化小写值唯一。
- `phone`：新注册必填；仅接受大陆 11 位手机号，首位 `1`、第二位 `3–9`；服务器将其规范化成 `+86` + 11 位；唯一。
- 旧记录的 `username` / `phone` 暂为 null。部分唯一索引允许历史 null；新注册路径由领域校验保证非空。
- 旧 `email` 列、唯一约束、历史用户与 refresh token 保留，不做数据回填。新注册写入一个由服务端生成、不可登录且不暴露的内部保留 email，以满足现有非空 schema；后续单独迁移才能移除 email 依赖。
- 只在受控内测 PostgreSQL `127.0.0.1:15432` 运行 migration；绝不连接 `5432`。

## Cloud API 契约

### 注册

`POST /api/v1/auth/register`

请求：

```json
{"username":"alice_01","phone":"13800138000","password":"Aa123456"}
```

- 不接受/不依赖 email。
- 密码最少 8 位，至少一个 ASCII 大写字母、小写字母和数字。后端仍是最终校验者。
- 成功时原子创建用户、3 天试用权益并返回现有安全 user/entitlement 响应；手机只以必要的掩码或不显示方式返回，不回显密码。
- 重复用户名或手机号返回稳定、无枚举性/敏感细节的冲突错误。

### 登录

`POST /api/v1/auth/login`

请求：

```json
{"phone":"+8613800138000","password":"Aa123456"}
```

- 仅通过手机号查找用户；同一手机号的不同可接受输入形式均先规范化为 `+86`。
- 成功后的 refresh/access token、会话授权和设备处理保持现有契约。

### 验证码预留

```text
POST /api/v1/auth/phone-verifications
POST /api/v1/auth/phone-verifications/confirm
```

两端都接受规范化前的 `phone`，并执行格式校验；当前始终返回固定 `verification_not_enabled`（建议 HTTP 503）。不得发送短信、保存验证码、暴露供应商信息或要求注册页面调用这些端点。未来启用时，注册在调用 `/register` 前/中要求 confirm 成功即可。

## Android 体验

- 未登录时默认显示“手机号登录”：手机号、密码、登录按钮、可达的“注册账户”文本按钮。
- 注册页面仅显示用户名、手机号、密码、确认密码及“注册并登录”。不显示邮箱或验证码输入。
- 手机号以大陆号码格式输入；本地校验与 Cloud 一致。密码隐藏显示，确认密码必须相等；每个 field 使用固定中文错误提示，错误时不发网络请求。
- 注册只调用现有等价的 `AccountViewModel.register` 新签名；成功自动登录并进入现有用户导航。密码不得进入 UI 状态持久化、日志、错误文案或 analytics。
- 48dp 触控目标、中文 contentDescription、已有 token-safe 账户摘要继续适用。

## 错误、安全与兼容性

- 服务器对格式、唯一性、密码复杂度、disabled user 和认证失败保持权威；客户端校验只改善反馈。
- API 日志、Cloud 日志、Android UI 和验收记录不得出现明文密码、refresh/access/session token、DSN、验证码或完整手机号。
- 手机号/用户名冲突的显示文案固定为“该手机号或用户名暂不可用，请更换后重试。”；登录失败固定为“手机号或密码错误。”。
- 保持请求频率限制、argon2 密码哈希、Keystore refresh token 存储、JWT audience/issuer 和 translation session 验证不变。

## 验收

1. 有效用户名、11 位大陆手机号与合规密码可注册，并在受控测试库创建用户/试用权益及自动登录。
2. 已规范化的手机号可登录；邮箱和用户名不能替代手机号登录。
3. 非法手机号、重复用户名/手机号、短/弱密码、确认不一致不会提交注册。
4. 验证码两个接口验证手机号格式后返回 `verification_not_enabled`，无短信或验证码记录。
5. 旧邮箱用户和 refresh-token 流程不被迁移破坏；受影响 Cloud/Android 测试、lint、构建和受控 PostgreSQL 集成测试通过。
