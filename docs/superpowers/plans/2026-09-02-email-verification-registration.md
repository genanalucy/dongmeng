# 邮箱验证码注册 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用自建 EC2 本机 SMTP 发送 6 位邮箱验证码，验证成功后原子创建正式用户和试用权益，并将 Android 注册改为两步邮箱验证。

**Architecture:** Cloud API 增加 PostgreSQL 驱动的待验证注册/限流存储、验证码编排服务和 SMTP sender；新注册不创建 pending 用户。Postfix 只在 EC2 loopback 接收 Cloud API 提交，Caddy 继续只公开 HTTPS API。Android 将当前手机号即时注册替换为“填写资料→发送验证码→确认验证码”。

**Tech Stack:** Go、Chi、pgx/PostgreSQL 17、Postfix/systemd、Kotlin、Jetpack Compose、OkHttp。

**Spec:** `docs/superpowers/specs/2026-09-02-email-verification-registration-design.md`

## Global Constraints

- 验证码为 `crypto/rand` 生成的 6 位数字，10 分钟有效，只存 hash + 独立随机 salt，以常量时间比较。
- 不创建 pending 用户；成功确认前不得创建 user、password credential 或 trial entitlement。
- 单邮箱冷却 60 秒、单邮箱 5 次/小时、单 IP 20 次/小时、单验证码最多 5 次尝试。
- PostgreSQL 仅监听 `127.0.0.1:15432`，禁止使用或开放 `5432`。
- SMTP 仅监听 `127.0.0.1`；公网不开放 `25`、`465`、`587`；严禁 open relay。
- 不记录明文密码、验证码、token、DSN、SMTP 凭据、完整邮箱或邮件正文。
- 无域名阶段允许 EC2 IP 尝试真实投递，但必须将不可投递显式作为可重试失败，不能伪造发送成功。
- Android 不持久化明文密码或验证码；保留用户显式自定义端点功能。
- 旧用户登录、refresh/access token、账户中心保持兼容；新注册不得绕过邮箱验证。

---

### Task 1: 定义验证码注册领域模型与安全原语

**Files:**
- Modify: `cloud-api/internal/domain/domain.go`
- Create: `cloud-api/internal/auth/email_registration.go`
- Create: `cloud-api/internal/auth/email_registration_test.go`

**Interfaces:**

```go
type RegistrationVerificationRequest struct {
    Username string
    Email    string
    Password string
    ClientIP netip.Addr
}

type RegistrationVerificationConfirmation struct {
    Email string
    Code  string
}

type RegistrationVerificationResult struct {
    RetryAfterSeconds int
}

type RegistrationCodeSender interface {
    SendRegistrationCode(ctx context.Context, email, code string, expiresAt time.Time) error
}
```

- [ ] **Step 1: 写失败测试：验证码及输入规则**

在 `email_registration_test.go` 覆盖：仅接受 6 个 ASCII 数字；密码沿用现有密码规则；邮箱、用户名沿用 `ParseEmail`、`ParseUsername`；验证码过期边界；错误码不暴露记录是否存在。

```go
func TestParseRegistrationVerificationCode(t *testing.T) {
    _, err := ParseRegistrationVerificationCode("12345a")
    require.ErrorIs(t, err, domain.ErrInvalid)
    got, err := ParseRegistrationVerificationCode("012345")
    require.NoError(t, err)
    require.Equal(t, "012345", got)
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd cloud-api && go test ./internal/auth -run TestParseRegistrationVerificationCode -count=1`

Expected: FAIL，因为验证码 parser 尚不存在。

- [ ] **Step 3: 实现最小安全原语**

实现：`ParseRegistrationVerificationCode`、`generateSixDigitCode`（`crypto/rand`）、随机 salt、`hashCode(salt, code)`、`constantTimeCodeMatch`；定义固定常量：10 分钟、60 秒、5/小时、20/小时、5 次尝试。禁止将 code 写日志。

- [ ] **Step 4: 实现验证服务的可注入依赖**

在 `email_registration.go` 定义 `EmailRegistrationService`，注入 Store、密码哈希器、code/salt 生成器、rate-limit keyed hash secret、`RegistrationCodeSender` 和 clock；测试使用 fake sender，真实 SMTP 不进入单元测试。

- [ ] **Step 5: 运行认证单测**

Run: `cd cloud-api && go test ./internal/auth -count=1`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add cloud-api/internal/domain/domain.go cloud-api/internal/auth/email_registration.go cloud-api/internal/auth/email_registration_test.go
git commit -m "feat(cloud): add email verification primitives"
```

### Task 2: 添加 PostgreSQL 验证记录与原子限流存储

**Files:**
- Create: `migrations/000005_email_registration_verifications.up.sql`
- Create: `migrations/000005_email_registration_verifications.down.sql`
- Modify: `cloud-api/internal/domain/domain.go`
- Modify: `cloud-api/internal/store/business.go`
- Create: `cloud-api/internal/store/email_registration_test.go`
- Modify: `cloud-api/integration/postgres_business_test.go`

**Schema:**

```sql
CREATE TABLE registration_verifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username text NOT NULL,
  email text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  code_hash bytea NOT NULL,
  code_salt bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
  sent_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX registration_verifications_expires_at_idx ON registration_verifications(expires_at);

CREATE TABLE email_verification_rate_limits (
  key_type text NOT NULL CHECK (key_type IN ('email', 'ip')),
  key_hash bytea NOT NULL,
  window_started_at timestamptz NOT NULL,
  request_count integer NOT NULL CHECK (request_count >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (key_type, key_hash)
);
```

- [ ] **Step 1: 写 store 失败测试**

覆盖同一邮箱 60 秒内重发、邮箱第 6 次/小时、IP 第 21 次/小时、并发确认、第五次错误使记录失效、确认成功仅创建一个 user 和一个 entitlement。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd cloud-api && go test ./internal/store -run 'TestRegistrationVerification' -count=1`

Expected: FAIL，因为表和 Store 接口不存在。

- [ ] **Step 3: 编写 up/down migration**

up 创建两张表及索引；down 仅删除本 migration 创建的对象。生产迁移只执行 up，不执行 down。

- [ ] **Step 4: 实现原子 Store 操作**

定义：

```go
RequestRegistrationVerification(ctx context.Context, params domain.CreateRegistrationVerificationParams) (domain.RegistrationVerification, error)
ConfirmRegistrationVerification(ctx context.Context, params domain.ConfirmRegistrationVerificationParams) (domain.RegisterParams, error)
InvalidateRegistrationVerification(ctx context.Context, email string, now time.Time) error
```

请求事务：锁定/更新 email 与 IP 限流桶、检查正式 users 的 username/email 冲突、写入或更新单条 verification。确认事务：`SELECT ... FOR UPDATE`，先原子递增 attempt，再做 hash 比较；成功后在同一事务调用现有注册 user/credential/trial 逻辑并删除 verification。

- [ ] **Step 5: 运行 store/integration 测试**

Run: `cd cloud-api && CLOUD_API_TEST_DATABASE_URL='postgres://…127.0.0.1:15432…' go test ./internal/store ./integration -count=1`

Expected: PASS。测试 DSN 必须是精确 `127.0.0.1:15432`，不输出 DSN。

- [ ] **Step 6: 提交**

```bash
git add migrations cloud-api/internal/domain/domain.go cloud-api/internal/store/business.go cloud-api/internal/store/email_registration_test.go cloud-api/integration/postgres_business_test.go
git commit -m "feat(cloud): persist email registration verifications"
```

### Task 3: 实现 SMTP sender 和安全配置

**Files:**
- Modify: `cloud-api/internal/config/config.go`
- Modify: `cloud-api/internal/config/config_test.go`
- Create: `cloud-api/internal/mail/smtp_sender.go`
- Create: `cloud-api/internal/mail/smtp_sender_test.go`
- Modify: `cloud-api/cmd/cloud-api/main.go`

**Interfaces:**

```go
type SMTPConfig struct {
    Host string
    Port int
    From string
    ConnectTimeout time.Duration
    SendTimeout time.Duration
}
```

- [ ] **Step 1: 写失败测试：生产 SMTP 配置拒绝非 loopback**

```go
func TestValidateRejectsNonLoopbackSMTPHostInProduction(t *testing.T) {
    cfg := validConfig(t)
    cfg.Environment = "production"
    cfg.SMTPHost = "mail.example.com"
    require.Error(t, cfg.Validate())
}
```

- [ ] **Step 2: 实现 Config 和校验**

加入 `SMTP_HOST`、`SMTP_PORT`、`SMTP_FROM`、SMTP 超时、`EMAIL_VERIFICATION_RATE_LIMIT_SECRET`。生产仅接受 `127.0.0.1`/`localhost` SMTP host，要求有效 sender 与足够长度 secret。错误不得包含 secret 值。

- [ ] **Step 3: 实现 sender 与 fake transport 测试**

`SMTPRegistrationCodeSender.SendRegistrationCode` 仅发送固定纯文本模板：产品名、验证码、“10 分钟内有效”。校验收件人、发件人、超时；不包含密码、token、IP、内部地址。sender 返回提交失败而非吞错。

- [ ] **Step 4: 接入 main wiring**

在 `main.go` 创建 sender 和 verification service，将它们注入 router；启动时 SMTP/secret 配置不安全则失败。

- [ ] **Step 5: 运行测试**

Run: `cd cloud-api && go test ./internal/config ./internal/mail ./cmd/cloud-api -count=1`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add cloud-api/internal/config cloud-api/internal/mail cloud-api/cmd/cloud-api/main.go
git commit -m "feat(cloud): add loopback smtp sender"
```

### Task 4: 替换 HTTP 注册契约并接入可信客户端 IP

**Files:**
- Modify: `cloud-api/internal/http/router.go`
- Modify: `cloud-api/internal/http/business.go`
- Modify: `cloud-api/internal/http/router_test.go`
- Modify: `cloud-api/internal/http/business_test.go`（若当前测试组织在 `router_test.go`，放入该文件）

**HTTP contract:**

```text
POST /api/v1/auth/registration-verifications
{"username":"example_user","email":"user@example.com","password":"..."}
→ 202 {"retry_after_seconds":60}

POST /api/v1/auth/registration-verifications/confirm
{"email":"user@example.com","code":"123456"}
→ 201 {"user":{…},"trial_entitlement":{…}}
```

- [ ] **Step 1: 写 handler 失败测试**

覆盖：严格 JSON、字段缺失、发送成功、SMTP 失败、冷却/邮箱/IP 限制、错误/过期/第五次验证码、确认成功、并发确认、旧 `/auth/register` 不能绕过验证。

- [ ] **Step 2: 实现 trusted client IP 解析**

仅当请求来自 loopback Caddy 时读取 Caddy 注入的 forwarded 地址；直连或格式错误 forwarded header 不进入 IP 限流绕过路径。任何客户端直接伪造 header 均不得获得不同 IP 配额。

- [ ] **Step 3: 实现两个公开 handler**

使用已有 `decode`、`writeJSON`、`domainError` 约定；验证码请求对注册邮箱、受限、外部 SMTP 错误维持可枚举性最小的 `202` 外部响应，内部记录 request ID + 脱敏 reason；确认对错误/过期/已消费统一不可验证错误。

- [ ] **Step 4: 封闭旧 bypass**

`POST /api/v1/auth/register` 返回明确迁移错误或移除 route；不得调用现有立即注册服务。保留 login、token 与旧已注册用户路径。

- [ ] **Step 5: 运行 Cloud API 测试**

Run: `cd cloud-api && go test ./internal/http ./internal/auth ./...`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add cloud-api/internal/http
git commit -m "feat(cloud): require email verification for registration"
```

### Task 5: 配置 EC2 本机 Postfix 并部署 Cloud API

**Files:**
- Create: `docs/operations/email-registration-smtp.md`
- Modify: `README.md`

- [ ] **Step 1: 编写运维验收清单**

记录 Postfix `inet_interfaces = loopback-only`、本地 Cloud API SMTP 提交、open relay 拒绝、systemd 状态、队列检查、日志脱敏、无域名/IP-only 投递限制，以及未来 SPF/DKIM/DMARC/PTR 切换步骤。

- [ ] **Step 2: 在 EC2 安装并收紧 Postfix**

使用 EC2 systemd 服务；仅回环监听 SMTP；确认 Security Group/本机监听不包含公网 `25`、`465`、`587`。不得把配置、队列、日志或凭据复制入 Git。

- [ ] **Step 3: 部署 migration 与 Cloud API**

备份/快照现有 EC2 数据库，执行仅 up migration，写入权限 `640` 的 EC2 私有 Cloud API SMTP 环境变量，重启 Cloud API。数据库始终为 `127.0.0.1:15432`。

- [ ] **Step 4: 运行 EC2 安全与投递 smoke**

验证 loopback SMTP、EC2 Cloud API `/healthz`、公网 HTTPS API；使用受控真实邮箱请求验证码，确认可见邮件提交结果。若 AWS 出站 25 受限或收件方拒收，API 返回可重试失败，不显示验证码。

- [ ] **Step 5: 提交文档**

```bash
git add README.md docs/operations/email-registration-smtp.md
git commit -m "docs: add email registration smtp operations"
```

### Task 6: 替换 Android Cloud API 注册契约与状态机

**Files:**
- Modify: `android/app/src/main/java/com/verba/interpretation/cloud/CloudApi.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/AccountViewModel.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/cloud/CloudApiAuthenticationContractTest.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/ui/AccountViewModelPhoneAuthenticationTest.kt`

**Kotlin contract:**

```kotlin
fun requestRegistrationVerification(username: String, email: String, password: String): Int
fun confirmRegistrationVerification(email: String, code: String): RegistrationResponse
```

- [ ] **Step 1: 写失败 API contract 测试**

断言 send-code JSON 只有 `username`、`email`、`password`；confirm JSON 只有 `email`、`code`；不再发送 phone；`202` 读取 `retry_after_seconds`。

- [ ] **Step 2: 修改 `CloudApi` 与 `AccountApi`**

删除新注册请求的 phone 字段，加入 send/confirm DTO 与方法。沿用现有授权/错误解析机制；验证码和密码不进日志。

- [ ] **Step 3: 实现 ViewModel 两步状态**

定义 details/challenge 状态：发送成功后仅保留脱敏邮箱、重发等待与用户名等非敏感 UI 状态；密码在发送 API 返回后清空；验证码确认请求后清空。任何 `SavedStateHandle`、preferences、导航参数均不得保存 password/code。

- [ ] **Step 4: 运行 focused tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*CloudApiAuthenticationContractTest' --tests '*AccountViewModelPhoneAuthenticationTest'`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/verba/interpretation/cloud/CloudApi.kt android/app/src/main/java/com/verba/interpretation/ui/AccountViewModel.kt android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt android/app/src/test/java/com/verba/interpretation/cloud/CloudApiAuthenticationContractTest.kt android/app/src/test/java/com/verba/interpretation/ui/AccountViewModelPhoneAuthenticationTest.kt
git commit -m "feat(android): add email verification registration api"
```

### Task 7: 重构 Android 注册 UI 为邮箱验证码两步流程

**Files:**
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/account/PhoneAuthenticationForm.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/account/PhoneAuthenticationFormPolicy.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/account/PhoneAuthenticationSubmissionPolicy.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/account/RegistrationFormPolicy.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/ui/account/PhoneAuthenticationFormPolicyTest.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/ui/account/PhoneAuthenticationSubmissionPolicyTest.kt`
- Modify: `android/app/src/androidTest/java/com/verba/interpretation/ui/account/PhoneAuthenticationFormTest.kt`

- [ ] **Step 1: 写失败 UI/policy 测试**

断言注册资料页仅显示用户名、邮箱、密码、确认密码；无手机号字段；验证码页仅显示脱敏邮箱、6 位数字输入、确认与倒计时重发；60 秒内重发禁用。

- [ ] **Step 2: 改造 form policy 和 submission policy**

复用现有用户名/邮箱/密码边界验证；移除新注册的中国手机号规范化和 phone submission；验证码限制为 6 个 ASCII 数字。登录保留既有 identifier 兼容，直到单独登录收敛变更。

- [ ] **Step 3: 改造 Compose UI**

资料页按钮文字为“发送验证码”；验证码页具有返回编辑资料、重发倒计时和明确可重试错误。不得显示完整邮箱、验证码、password 或内部服务器错误。

- [ ] **Step 4: 运行 Android 验证**

Run:

```bash
cd android
export JAVA_HOME="$HOME/.local/share/verba-android-tools/jdk17"
export ANDROID_SDK_ROOT="$HOME/.local/share/verba-android-tools/android-sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/verba/interpretation/ui/account android/app/src/test/java/com/verba/interpretation/ui/account android/app/src/androidTest/java/com/verba/interpretation/ui/account
git commit -m "feat(android): add email code registration flow"
```

### Task 8: 端到端验收、文档更新与发布门禁

**Files:**
- Modify: `docs/operations/email-registration-smtp.md`
- Modify: `docs/internal-beta-update-guide.md`（若当前分支存在该手册）

- [ ] **Step 1: Cloud API 全量验证**

Run: `cd cloud-api && go test ./...`

Expected: PASS。若运行 PostgreSQL integration，DSN 必须精确指向 `127.0.0.1:15432`。

- [ ] **Step 2: EC2 防暴露检查**

确认公网只有 `80/443`；Cloud API `8080`、Agent `18765`、PostgreSQL `15432`、SMTP `25/465/587` 都未公网监听。

- [ ] **Step 3: 真实投递与注册 smoke**

用受控真实邮箱验证：收码、60 秒重发、错误码、过期码、5 次失败、成功确认、重复确认、用户名/邮箱冲突。日志检查不得包含验证码、密码、token、DSN 或完整邮箱。

- [ ] **Step 4: 真机验收**

安装最新 Debug APK，完成资料→验证码→注册→账户状态刷新；检查网络失败、投递失败和返回编辑资料。

- [ ] **Step 5: 检查提交范围并提交文档**

```bash
git diff --check
git status --short
git add docs/operations/email-registration-smtp.md docs/internal-beta-update-guide.md
git commit -m "docs: add email verification acceptance guide"
```

## Self-review

- Spec coverage: Task 1–4 覆盖验证码、hash、TTL、无 pending、限流与 API；Task 3/5 覆盖 loopback SMTP 和无域名真实投递限制；Task 6/7 覆盖 Android 两步界面与敏感数据清理；Task 8 覆盖真实邮箱、EC2 端口和真机验收。
- 占位扫描：无未完成标记或模糊实现指令。
- Type consistency: HTTP 路径、DTO 字段、Store 责任和 Android send/confirm 调用在 Task 2、4、6 中一致。
