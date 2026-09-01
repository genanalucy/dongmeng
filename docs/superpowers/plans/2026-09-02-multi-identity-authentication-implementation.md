# 多身份认证实施计划

**目标：** 注册使用用户名、邮箱、手机号、密码；登录自动识别邮箱/手机号/用户名。

**约束：** 仅中国大陆手机号；用户名不得纯数字；三身份唯一；不得泄露身份存在性或敏感数据；不改 token、会话、音频；数据库仅 `127.0.0.1:15432`。

## Task 1：Cloud 身份领域与存储

- 扩展领域身份识别：手机号优先、合法邮箱其次、其余合法非纯数字用户名。
- 注册输入加入 email；真实 email 写入用户，移除新注册保留 email 生成。
- Store 增加按用户名查询；保留历史 `UserByEmail`/refresh 兼容。
- 红绿测试：用户名纯数字拒绝、三身份规范化/冲突、身份识别优先级、legacy 邮箱兼容。

## Task 2：Cloud HTTP 契约与安全回归

- `/register` 接收 `{username,email,phone,password}`；`/login` 接收 `{identifier,password}`。
- 统一 `invalid_credentials`；登录 handler 根据身份类型使用对应 Store 查询。
- 公共响应保持不回显 email/phone；admin legacy email 契约不回归。
- 覆盖三种成功登录、未知/禁用/错误密码统一失败、未知字段/畸形输入、验证码端点无副作用。

## Task 3：Android 策略、API 与 UI

- 表单策略增加 email、禁止纯数字用户名、identifier 识别。
- Cloud API DTO 迁移为 register 四字段/login identifier；保持 Keystore 与 refresh 处理。
- 登录 UI 改标签“邮箱 / 手机号 / 用户名”；注册增加邮箱字段；无效不派发。
- 更新 Compose semantics/错误/无邮箱泄露约束测试。

## Task 4：受控 E2E 与验收

- 只在安全 `CLOUD_API_TEST_DATABASE_URL` 精确为 `127.0.0.1:15432` 时运行 migration up 与 PostgreSQL 测试；否则安全跳过并记录阻塞。
- Cloud `go test ./...`；Android `testDebugUnitTest`、`compileDebugAndroidTestKotlin`、`lintDebug`、`assembleDebug`。
- 更新真机清单：四字段注册、三身份登录、旧邮箱 refresh、TalkBack/大字体/横竖屏；不记录敏感信息。
