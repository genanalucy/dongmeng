# 手机号认证迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让内测用户以用户名与中国大陆手机号注册，并仅用手机号和密码登录，同时预留未启用的手机号验证码 API。

**Architecture:** Cloud API 的领域层负责用户名/手机号/强密码的规范化和最终校验；PostgreSQL migration 添加兼容旧 email 用户的 nullable、部分唯一字段。注册和登录 HTTP 契约改为手机号身份，Android 仅通过纯表单策略做早期反馈，继续使用现有自动登录与 Keystore refresh-token 机制。

**Tech Stack:** Go、chi、pgx/PostgreSQL 17、Go migrations、Kotlin、Android Compose、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-01-phone-authentication-design.md`

## Global Constraints

- 仅支持中国大陆 11 位手机号；规范化为 `+86` + 11 位。
- 新注册需要用户名、手机号、密码与确认密码；登录仅手机号与密码。
- 密码最少 8 位，含 ASCII 大写、小写和数字；后端为最终校验者。
- 验证码 API 只预留，当前固定返回 `verification_not_enabled`，不得发送短信或保存验证码。
- 旧 email 用户/refresh token 必须保持可读；新注册不接受邮箱登录。
- 测试数据库和 migration 仅使用 `127.0.0.1:15432`，严禁 `5432`。
- 不记录/提交密码、token、DSN、完整手机号、APK 或构建产物。
- 不改 Agent、PCM、Coordinator、translation session JWT 或 Keystore token 存储。

---

### Task 1: Cloud 手机号/用户名领域模型与安全 migration

**Files:**
- Create: `migrations/000004_phone_authentication.up.sql`
- Create: `migrations/000004_phone_authentication.down.sql`
- Modify: `cloud-api/internal/domain/domain.go`
- Modify: `cloud-api/internal/domain/domain_test.go`（或专注新测试文件）
- Modify: `cloud-api/internal/migrate/runner_test.go`

**Interfaces:**
- Produces `domain.Username`, `domain.Phone`, `domain.PhoneCredentialsInput`。
- `ParsePhone` 接受 11 位大陆输入或已规范 `+86` 格式并返回规范值；`ParseUsername` 返回规范小写用户名。
- `ParsePhoneCredentials(phone,password)` 使用强密码规则；不复用 email credentials。

- [ ] 写失败测试：合法/非法大陆手机号、`+86` 规范化、用户名字符/长度、密码缺大写/小写/数字、migration 仅允许 `127.0.0.1:15432`。
- [ ] 使用 `go test ./internal/domain ./internal/migrate` 确认失败原因是缺少新解析器/migration。
- [ ] 新建 up migration：添加 nullable `username`、`phone`；创建部分唯一索引（仅非 null）；不修改旧 email 数据。
- [ ] 实现规范解析与强密码规则；错误只包装 `domain.ErrInvalid`，不回显输入。
- [ ] 写 down migration，仅删除本 migration 创建的索引/列；不在运行中执行 down。
- [ ] 重跑 focused 测试并确认通过。
- [ ] Commit: `feat(cloud): add phone authentication domain model`

### Task 2: Cloud 注册、手机号登录与验证码预留路由

**Files:**
- Modify: `cloud-api/internal/domain/domain.go`
- Modify: `cloud-api/internal/auth/authorization.go`
- Modify: `cloud-api/internal/auth/authorization_test.go`
- Modify: `cloud-api/internal/store/business.go`
- Modify: `cloud-api/internal/store/*_test.go`
- Modify: `cloud-api/internal/http/business.go`
- Modify: `cloud-api/internal/http/router.go`
- Modify: `cloud-api/internal/http/router_test.go`

**Interfaces:**
- `RegisterParams` 改为 `Username`, `Phone`, `PasswordHash`, `Now`。
- Store 提供 `UserByPhone(context.Context, phone string)`；注册在单事务内创建 user 与 trial entitlement。
- `POST /api/v1/auth/register`: `{username, phone, password}`。
- `POST /api/v1/auth/login`: `{phone, password}`。
- `POST /api/v1/auth/phone-verifications` 与 `/confirm` 验证 phone 格式后返回固定 `verification_not_enabled`。

- [ ] 写 HTTP/auth/store 红测：手机号注册原子创建 3 天 trial；规范手机号可登录；邮箱/用户名不可登录；重复 username/phone 冲突；旧 email 用户不被 migration 破坏；两个预留端点不发送/持久化验证码且返回固定状态。
- [ ] 运行 focused `go test`，确认失败是旧 email 契约。
- [ ] 更新 Store SQL。新 user 写入服务端生成、不暴露且不可登录的内部保留 email，以满足旧 schema 非空约束；查询/响应不泄露它。
- [ ] 更新 AuthorizationService、登录 handler 与注册 handler；保持 argon2、refresh rotation、JWT/entitlement 逻辑。
- [ ] 添加预留端点及固定安全错误映射；路由限流/CORS/auth 边界不变。
- [ ] 运行 focused 和 `go test ./...`。
- [ ] Commit: `feat(cloud): support phone registration and login`

### Task 3: 受控 PostgreSQL migration 与 Cloud 端到端验证

**Files:**
- Modify: `cloud-api/integration/postgres_business_test.go`
- Modify: `cloud-api/contracttest/*`（仅受影响测试）
- Modify: `docs/reviews/android-core-translation-ui-redesign-acceptance.md`

**Interfaces:**
- 依赖 Task 1/2 的 migration、HTTP 契约和 `127.0.0.1:15432` 保护。

- [ ] 写 PostgreSQL 集成红测：迁移后手机号注册、手机号登录、trial 与 translation-session 创建；旧 email 行仍可读取；手机号重复失败。
- [ ] 仅设置 `CLOUD_API_TEST_DATABASE_URL` 指向 `127.0.0.1:15432` 并确认红测失败原因正确。
- [ ] 最小修复任何真实 migration/store 差异，不绕过 host/port guard。
- [ ] 运行 migration up、聚焦集成测试、`go test ./...`；不执行 down。
- [ ] 更新 token-safe 受控真机清单：手机号注册/登录、无验证码预期、不能记录完整手机号。
- [ ] Commit: `test(cloud): verify phone authentication migration`

### Task 4: Android 手机号认证纯表单策略

**Files:**
- Create: `android/app/src/main/java/com/verba/interpretation/ui/account/PhoneAuthenticationFormPolicy.kt`
- Create: `android/app/src/test/java/com/verba/interpretation/ui/account/PhoneAuthenticationFormPolicyTest.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/cloud/CloudApi.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/cloud/CloudApiTest.kt`（或现有契约测试）
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/AccountViewModel.kt`

**Interfaces:**
- `PhoneAuthenticationFormPolicy.register(username, phone, password, confirmation)` 返回规范手机号、固定字段错误及 `isValid`。
- `PhoneAuthenticationFormPolicy.login(phone,password)` 返回规范手机号与固定错误。
- Cloud API 仅将 `{username,phone,password}`/`{phone,password}` 发送到新契约，仍使用 `KeystoreTokenStore`。

- [ ] 写 JUnit 红测：手机号规范化、非法手机号、用户名、弱密码、确认不一致、登录手机号/密码缺失；测试不得断言明文密码/手机号出现在错误。
- [ ] 运行 focused Gradle 测试，确认缺少策略/新 Cloud 请求形状。
- [ ] 实现纯策略与固定中文错误；更新 Cloud request/response parsing、ViewModel 参数。
- [ ] 运行 focused 和 `testDebugUnitTest`。
- [ ] Commit: `feat(android): add phone authentication policy`

### Task 5: Android 注册/登录表单和最终验收

**Files:**
- Modify: `android/app/src/main/java/com/verba/interpretation/MainActivity.kt`
- Modify: `android/README.md`
- Modify: `docs/reviews/android-core-translation-ui-redesign-acceptance.md`
- Modify: `android/app/src/test/java/com/verba/interpretation/ui/account/*Test.kt`

**Interfaces:**
- 消费 Task 4 的 form policy 和 `AccountViewModel.register(username,phone,password)` / `login(phone,password)`。
- 不调用验证码预留端点。

- [ ] 写/调整红测：登录模式只提交手机号/密码；注册模式只显示/派发用户名、手机号、密码、确认密码；无效输入不派发；成功注册调用自动登录路径。
- [ ] 运行 focused 单测确认失败。
- [ ] 将未登录 Account UI 改为手机号登录和独立注册页；使用 password visual transformation、IME、安全固定错误和 >=48dp 控件。
- [ ] 更新设备验收：手机号注册进入用户页、重复/弱密码安全提示、无验证码、退出后手机号登录、不得截图/记录完整号码或密码。
- [ ] 运行 `testDebugUnitTest lintDebug assembleDebug`、`git diff --check`、追踪 APK/AAB/build 工件排除检查。
- [ ] 在受控设备进行真实 Cloud 注册/登录 smoke test；记录结果但不记录完整手机号、密码、token/DSN。
- [ ] Commit: `feat(android): add phone registration experience`
