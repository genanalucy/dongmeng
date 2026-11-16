> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-review.md`；原始 SHA-256 `609ec35bdebe87970ab950c149553e655840e0ed09388252a9baef9935c70282`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 高风险只读评审：Cloud → Agent 授权 E2E

日期：2026-08-27
评审工作树：`[旧临时路径]`
范围：仅只读检查；未修改代码、未启动网络服务、未连接数据库、未派发代理。

## 结论

**Task 7 当前不能宣称完成；缺少真正的跨模块 E2E harness。**

现有代码已经分别证明：Cloud 能签发符合 wire contract 的 HS256 token，Agent verifier/server 能在 fake Provider 前拒绝多类非法 token；但没有测试把 **Cloud API 的实际 session HTTP route 产出的 token** 送入 **真实 Agent HTTP/WebSocket server**，并在同一测试中证明合法请求恰好一次 Provider `Start`、恰好一个 `ready`，以及各类 Cloud-shaped 非法 token 在 Provider `Start` 前失败。

## 已确认的现状

### Cloud signer/handler

- `cloud-api/internal/auth/tokens.go` 的 `TranslationTokenForInstall` 签发：
  - HS256；`typ=translation_session`；
  - `iss` 为配置 issuer；`aud` 为独立 `SessionAudience`；
  - `sub == user_id`；包含 `session_id`、`install_id`、`entitlement_id`、`scope=translation`、`jti`、`iat`、`nbf`、`exp`。
- `cloud-api/internal/auth/authorization.go` 的 `CreateTranslationSession` 先检查活动 entitlement，生成 session/JTI，令 token TTL 不超过 entitlement 到期时间，再持久化授权 session。
- `cloud-api/internal/http/business.go` 的 `session` handler 通过 `POST /api/v1/translation-sessions` 返回 `session_id/user_id/install_id/expires_at/token`。该 handler 只能在 `api.require` 通过 access token 后调用。
- `cloud-api/internal/http/router.go` 将该 route 注册在 bearer access-token middleware 下；当前 `cloud-api/integration/integration_test.go` 只检查 readiness/access-token 基础行为，未配置 store，也未调用 session route。
- `cloud-api/contracttest/session_token_contract_test.go` 只在 Cloud module 内用 JWT parser 自己解析 signer 输出；它没有调用 Agent verifier/server。

### Agent server/sessionauth/fake Provider

- `agent/internal/sessionauth/sessionauth.go` 已严格要求 HS256、`typ=translation_session`、issuer、单 audience、exp/iat、最大 lifetime、`sub == user_id`、`user_id/session_id/install_id` 绑定和 `scope=translation`。
- `agent/internal/server/server.go` 只从 `Sec-WebSocket-Protocol` 读取两个协议项：`translation.v1` 与 `translation.jwt.<JWT>`；成功握手只协商 `translation.v1`，不会回显 credential protocol/JWT。
- `runConnection` 先读取 Android-shaped `start`，再执行 verifier，只有 verifier 成功后才调用 `astClient.Start`；因此当前生产路径的 Provider-before-auth 顺序正确。
- `agent/internal/server/server_test.go` 已使用 `httptest.NewServer` 和 fake AST client 覆盖 valid token、缺失/wrong scope、issuer、audience、expired、signature、user/session/install 绑定，以及禁止的 `bearer.<JWT>` subprotocol；相关负例断言 `fake.starts()==0`，并检查日志/响应不含 token。
- 但这些 token 是 Agent test helper 直接用 `jwt.MapClaims` 签发的，不是从 Cloud HTTP session route 得到的；现有 valid case 也只读取一个事件并断言其为 `ready`，没有独立证明“恰好一个 ready”。

## 缺失的精确 E2E harness

应新增 `cloud-api/integration/agent_session_e2e_test.go`（或等价的、明确由 Cloud integration module 驱动的 focused harness），最小行为如下：

### 正向用例（必须同时经过 Cloud route 与真实 Agent server）

1. 使用专用 test store/数据库 fixture 创建普通用户及活动 trial/entitlement；不得使用开发者 5432 数据库。
2. 用 Cloud API `POST /api/v1/auth/login` 获得 access token，仅在内存中保存；调用 `POST /api/v1/translation-sessions`，得到 Cloud 实际返回的 `session_id/user_id/install_id/token`。
3. 连接真实 Agent `Handler`/监听器，WebSocket 请求头**仅**为：
   `Sec-WebSocket-Protocol: translation.v1, translation.jwt.<JWT>`。
   不得把 JWT 放进 URL、Cookie 或 Authorization header。
4. 发送与 Android 相同的首包：`type=start`、`sessionId`、`userId`、`installId` 取自同一 Cloud grant，并保留现有 `mode=s2s`、`zh→en`、`pcm`、`16000` 字段。
5. 断言连接协商结果只有 `translation.v1`，响应协议头不含 JWT；收到**恰好一个** `ready`，并在短超时内确认没有第二个 `ready`/额外重复 ready。
6. fake Provider 的 `Start` 计数必须最终为 **1**；不要使用真实 Provider credentials，也不要因真实 Provider 不可用而 fallback。
7. 若 harness 启动的是 listener，必须明确关闭并等待 server/goroutine；优先使用 OS 分配的 loopback 临时端口，避免固定端口冲突。

### 负向矩阵（每项都要由 Cloud-shaped token/同一 E2E server path 驱动）

对同一个有效 Cloud grant，分别构造只改变一个授权条件的 token/start/header，并每次使用新的 fake Provider 计数器：

| 案例 | 失败点 | 必须断言 |
|---|---|---|
| `scope=api` | JWT scope | Agent authorization error/close；`Start=0` |
| 缺失 `scope` | JWT scope | 同上；`Start=0` |
| 错误 `aud` | JWT audience | 同上；`Start=0` |
| 错误 `iss` | JWT issuer | 同上；`Start=0` |
| 错误签名 | HMAC signature | 同上；`Start=0` |
| 过期 | `exp` | 同上；`Start=0` |
| 错误 user binding | token 或 start `userId` | 同上；`Start=0` |
| 错误 session binding | token 或 start `sessionId` | 同上；`Start=0` |
| 错误 install binding | token 或 start `installId` | 同上；`Start=0` |
| 禁止 subprotocol | `bearer.<JWT>`、仅 JWT、URL/Cookie/Header credential 等 | HTTP 401/拒绝握手；无协商 protocol；`Start=0` |

其中 `aud/iss/scope/exp/signature` 负例需要保留 Cloud signer 的 HS256/header/claim 形状，而不是只测试任意手写 token。测试可以使用固定、仅测试用的 32-byte key 和 signer fixture；不得把生产 secret 读入日志或报告。对于 altered token，应明确测试是“同一 Cloud 配置下的恶意篡改/伪造”，不能声称 Cloud route 会签发非法 token。

## 跨模块实现阻碍

当前模块边界使“一个 Go test 直接 import 两边生产 internal package”不可行：

- Cloud module 是 `cloud-api`；Agent module 是 `agent`/`translator-agent`。
- `agent/internal/...` 只能由 Agent module 内部导入；`cloud-api/integration` 不能直接导入 Agent 的 `internal/server` 或 `internal/sessionauth`。
- Agent `cmd/translator-agent/main.go` 固定绑定 `127.0.0.1:18765`，并构造真实 configured AST routing client；没有测试端口参数或 fake Provider 注入点。仅启动该 binary 无法在无 Provider credentials 的情况下满足 fake Provider `Start` 计数要求。
- Cloud `NewRouter` 的业务 route 需要真实 `businessStore`；当前 integration test 未提供 store。只用 `httptest` readiness handler 不能产生真实 Cloud session token。

因此当前缺失不是再补一个 Agent 单测，而是需要一个最小测试桥接方案。建议优先级：

1. **最小、测试专用桥接：**在 Agent module 增加仅测试使用的 harness/fixture entry point（例如 test-only helper binary 或可测试的 listener factory），允许传入 fake `ast.Client`、`SessionVerifier` 和 `net.Listener`；不改变生产 cmd 的默认固定端口、Provider 路由或认证策略。Cloud integration harness 通过本地 HTTP/WebSocket 与该 helper 进程交互。
2. Cloud 侧使用已配置的专用 integration store/数据库 fixture，通过真实 `NewRouter` 调用 session route；Cloud test key/issuer/audience 与 Agent test harness verifier 使用同一组测试配置。
3. 若不接受进程桥接，可把纯 verifier/server test harness 提取为稳定、可导入的公共内部测试包；但这属于更大 API/package 重构，不是 Task 7 的最小范围，且仍需 Cloud route/store fixture。

不建议：复制 Agent verifier 到 Cloud contract test、在 Agent cmd 中加生产 fallback、允许 URL/Cookie/Authorization token、让 fake Provider 替代真实 Provider 的生产路由、或把固定 18765/8080 当作唯一测试端口。

## Critical

### C1. 没有 Cloud-issued token → real Agent WebSocket → fake Provider 的跨服务 E2E

这是 Task 7 计划 Step 1 的核心交付物，但当前仅有两侧孤立测试：Cloud contract 自解析 token，Agent server 用本地 helper token。两侧测试可同时通过而仍无法发现 signer/verifier 配置、claim、subprotocol 或 start 字段的互操作漂移。

影响：不能证明设计定义的“Cloud 签发 token 经过 Android-equivalent framing 后能进入 Agent ready”，也不能进入真实音频前的 M2 authorization gate。

### C2. 现有 Agent binary 无法满足 fake Provider/临时端口 E2E 条件

`cmd/translator-agent/main.go` 固定 `server.DefaultAddress`（`127.0.0.1:18765`）并创建真实 AST client；没有 fake Provider 和 listener 注入。直接运行 binary 既会有固定端口冲突风险，又会要求 Provider 配置，不能作为当前计划所需的隔离 E2E server。

影响：若不加入严格 test-only bridge，所谓“E2E”只能退化成 Agent package 内测试或依赖真实 Provider，均不符合任务边界。

## Important

### I1. 负向矩阵尚未由 Cloud route 产物驱动

Agent server 负例已覆盖 scope/aud/iss/signature/expired/user/session/install 和禁止 subprotocol，但其 token 直接由 Agent test helper 签发；没有验证 Cloud signer 的实际输出、配置和 Android start grant 在这些情况下的完整链路。需要在新 E2E 中逐项断言 `Start=0`，不可只复用 verifier 单测结果。

### I2. 正向断言不是“恰好一次 ready + 恰好一次 Start”

现有 `TestSessionAuthorizationAcceptsValidBoundToken` 断言一次读到的事件是 `ready`、`fake.starts()==1`，但没有检测后续重复 `ready`。Task 7 要求 valid token reaches fake Provider exactly once；harness 应显式计数 ready，并对重复事件设置短、确定的 no-extra-event 检查。

### I3. Cloud session route 的持久化/端口前置条件没有被 harness 明确隔离

Cloud route 需要活动 entitlement 和 business store；设计/计划明确 Cloud PostgreSQL 只允许 `127.0.0.1:15432`，严禁 host `5432`。当前 integration test 不连接数据库也不产生 grant，未来 harness 若复用默认 DSN 或 Docker service 必须 fail closed：

- dedicated Cloud DB 只能显式传入 DSN；
- host-side PostgreSQL 必须为 `127.0.0.1:15432`；
- Compose 内部例外只能是 `postgres:5432`；
- 不得默认 fallback 到 `127.0.0.1:5432`；
- Agent/Cloud Web server 测试优先使用 loopback ephemeral ports，避免占用已有 `8080`/`18765`。

### I4. token-safe 测试报告约束尚未在跨服务 harness 中定义

现有 Agent 日志断言较好：认证错误使用固定 message/code，日志不含 token，server 不回显 credential protocol。新 harness 还必须保证：

- 不把 token 放入 `t.Fatalf` 的格式参数、快照、JUnit artifact、HTTP dump、日志 buffer 或 acceptance 文档；
- 不打印完整 request/response headers（其中含 `Sec-WebSocket-Protocol`）；
- 失败只报告 case 名、状态码、固定错误码、`providerStarts` 和 `tokenLeaked=false` 等布尔/非敏感摘要；
- Cloud session response 中的 token 只在内存传递，断言其存在时只用长度/非空布尔值，不能输出值或片段；
- 关闭连接后清理 listener、临时数据库 fixture 和测试进程；不保存 JWT/密码/DSN。

## 最小 test-only/code scope 建议

1. 新增一个 focused Cloud integration E2E test 文件，实际调用 Cloud `POST /api/v1/translation-sessions`，并通过同一 HTTP/WS path 驱动 Agent。
2. 新增 Agent test-only listener/fake-provider 注入桥接；仅暴露测试所需的 `net.Listener`、fake `ast.Client` 和现有 verifier/server handler，不改变生产认证代码或 token location。
3. 新增 Cloud integration store/DB fixture；若复用 PostgreSQL，使用显式 `15432` DSN 并继承 Task 4 的 target validation，不新增迁移、不修改已发布 migration。
4. 新增一个 token-safe assertion helper：固定错误码/HTTP status、Provider start count、协商 protocol 是否仅为 `translation.v1`；禁止打印敏感 header/body。
5. 仅在 E2E 首次失败明确证明 contract/config mismatch 时修改生产 Cloud/Agent code；当前只读证据尚未证明生产代码需要修改，因此**不建议预先修改生产 signer、handler、sessionauth 或 server**。
6. E2E 通过后再写 `docs/reviews/internal-beta-authorization-acceptance.md`；报告必须列出 case 结果、端口/DB 隔离、commit SHA 和 token-free log review，不写 JWT、密码、DSN 或 Provider secret。

## 验收判定

在补齐 C1/C2、并通过完整正负向矩阵前：**Task 7 = Not approved / 未完成。**

当前可确认的是 Agent 内部 fail-closed 授权边界和 Cloud wire contract 基础正确；当前不可确认的是 Cloud 实际 session route 与真实 Agent server/fake Provider 的互操作闭环。
