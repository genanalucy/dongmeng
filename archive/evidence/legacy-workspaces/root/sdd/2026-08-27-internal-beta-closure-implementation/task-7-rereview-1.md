> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-rereview-1.md`；原始 SHA-256 `0bfac7d148a86292bdebf8e0b50a9af640275535a522757935bd833eb1b1f491`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 复审 1：Cloud → Agent 授权 E2E 测试桥

日期：2026-08-28
评审范围：`fb3f6dd` vs `0996056`，Task 7 初审、实施报告及 `review-0996056..fb3f6dd.diff`
工作树：`[旧临时路径]`
方式：高风险只读评审；未修改被审代码，未启动服务，未连接数据库，未派发代理，未运行测试。

## 结论

**存在 4 项 Important，暂无 Critical。当前不建议 controller 直接创建/连接真实 `127.0.0.1:15432` 执行完整 E2E；应先修复下列测试隔离与判别力问题并重新静态复审。**

桥接方向正确，且未发现生产 `cmd` 行为变化：Agent helper 由 `//go:build integrationtest` 排除在正常构建外，使用 `127.0.0.1:0`、fake Provider、真实 `server.New`/`sessionauth.NewVerifier`；生产 `agent/cmd/translator-agent/main.go`、Agent/Cloud 生产内部实现及 Cloud 生产命令均未出现在提交 diff 中。Cloud E2E 也确实经过真实 register、login、受 access-token 保护的 translation-session route，再把 Cloud route 返回的 grant 送入真实 Agent WebSocket server。

但当前测试在子进程回收、失败路径数据库清理、过期 token 单变量构造，以及 URL/Cookie/Authorization 负例判别力上仍不满足高风险 E2E gate。

## 已确认符合项

### Test-only helper 与生产隔离

- `agent/cmd/session-test-harness/main.go` 文件首行是 `//go:build integrationtest`；没有无 tag 的同目录实现。
- helper 明确监听 `net.Listen("tcp", "127.0.0.1:0")`，由 OS 分配 loopback ephemeral port，不占用生产 `127.0.0.1:18765`。
- helper 注入本地 `fakeProvider`，只实现 `ast.Client`/`ast.Session`，不加载 Provider 配置或 credentials。
- helper 复用真实 Agent `server.New` 与 `sessionauth.NewVerifier`，并只通过 test-only `/test/provider-starts` 暴露计数。
- `0996056..fb3f6dd` 仅新增 helper、Cloud integration test 和 WebSocket 测试依赖；没有修改 `agent/cmd/translator-agent`、`cloud-api/cmd` 或任一生产 server/auth/store 实现。因此未发现生产命令默认端口、Provider 路由或认证行为变化。

### Cloud route 与 WebSocket 正向链路

- `createGrant` 实际调用：
  1. `POST /api/v1/auth/register`；
  2. `POST /api/v1/auth/login`；
  3. 携带内存中的 access token 调用 `POST /api/v1/translation-sessions`。
- Cloud router 使用真实 `httpapi.NewRouter` 和真实 `store.Postgres`；session route 位于 `api.require` bearer middleware 后。
- 正向 Agent upgrade 只设置 `Origin`，credentials 仅位于 `Sec-WebSocket-Protocol` 的 `translation.jwt.<JWT>` 项；URL 无 query，未设置 Cookie 或 Authorization。
- start 首包使用同一 Cloud grant 的 `sessionId/userId/installId`，并包含 Android-shaped `s2s`、`zh→en`、`pcm`、`16000`。
- 正向断言协商结果和响应头均严格等于 `translation.v1`，不会回显 credential protocol。
- 正向先读取一个 `ready`，再以短超时拒绝第二个 ready，并最终断言 fake Provider `Start == 1`。从 Agent 真实实现看，Provider `Start` 在 verifier 成功后调用，且 `ready` 在 `Start` 成功后仅发送一次。

### 负向授权矩阵

- 每个负向 subtest 都新启一个 helper/fake Provider，避免计数互相污染。
- scope=api、缺失 scope、错误 aud、错误 iss、错误签名、user/session/install binding 均经过真实 Agent WebSocket/start 路径，并断言固定 authorization error 与 `Start == 0`。
- `translation.v1 + bearer.<JWT>` 和仅 `translation.jwt.<JWT>` 都要求 HTTP 401、无协商 protocol、`Start == 0`。
- 生产 Agent 的 `sessionTokenFromRequest` 静态检查只读取 `Sec-WebSocket-Protocol`，没有读取 URL、Cookie 或 Authorization；合法路径也没有把 session credential 放到这些位置。

### DB target guard、fixture 与敏感信息

- E2E 必须显式提供 `CLOUD_API_TEST_DATABASE_URL`；未设置时 skip，不存在默认 DSN fallback。
- 复用 `isolatedPostgresTestDSN` → `validateIsolatedPostgresTestDSN` → `migrate.ValidateDatabaseURL(..., false)`；既有负例覆盖 multi-host fallback、query host/service/options/runtime overrides。失败信息不打印 DSN，目标被限制为隔离的 `127.0.0.1:15432`。
- 完整 grant 创建成功后，按 user ID 删除 fixture user；数据库外键 `ON DELETE CASCADE` 会清理 entitlement、refresh token、translation session、device 等相关记录。
- 新代码的失败信息未格式化 token、request/response headers、密码、DSN 或 Provider credential；helper stderr 仅固定摘要，stdout 仅 loopback address。静态审查未发现 token/header/DSN 泄漏。
- 提交中的 session/access key 和测试密码均为固定、显式 test fixture，不是生产 secret。

## Critical

无。

## Important

### I1. `go run` cleanup 只终止 Go tool wrapper，不能可靠证明 helper binary 已退出

位置：`cloud-api/integration/agent_session_e2e_test.go` 的 `startAgentSessionHarness`。

测试通过 `exec.CommandContext(ctx, "go", "run", ...)` 启动 helper。cleanup 调用 `cancel()` 后等待的 `done` 来自 `command.Wait()`，它只代表被 `os/exec` 直接启动的 `go` tool 进程结束。`go run` 编译后会再启动测试 helper 子进程；取消 CommandContext 默认只 kill 直接子进程，不构成跨平台的进程组/子孙进程回收保证。因此 cleanup 可能在 `go` wrapper 退出后成功返回，而真正监听 loopback 的 helper 仍存活。

完整矩阵会启动多个 helper；残留进程不仅消耗资源，也会保留 test-only start-count endpoint 和固定测试 verifier key，违反“明确关闭并等待 server/process/goroutine”的验收要求。

建议：先构建到 `t.TempDir()`，再直接用 `exec.CommandContext` 启动生成的 helper binary，并在 cleanup 中确认该直接进程退出；或使用可证明会终止整个进程组的受控方案。还应在超时后主动 kill 并再次 Wait，而不是仅 `t.Error` 后留下进程。

### I2. fixture user cleanup 注册得过晚；register 后的失败会污染真实 15432 测试库

位置：`TestCloudAgentSessionAuthorization` 与 `e2eCloud.createGrant`。

当前 cleanup 只有在 `createGrant` 完整完成、grant user ID 成功解析后才注册。但 `createGrant` 先执行真实 register，然后还可能在 login、access token 解码、session route、session response 解码任一步 `t.Fatal`。这些失败发生时，用户、trial entitlement，以及可能已创建的 refresh token/session 已经写入数据库，却尚无任何 cleanup。

成功路径的 `DELETE FROM users` 级联清理是正确的，但高风险 E2E 最需要保证的恰是中途失败路径。随机 email 虽降低冲突，却不等于 fixture cleanup。

建议：register 成功后立即从 register response 提取并验证 user ID，立刻注册 cleanup；随后再执行 login/session。cleanup 应覆盖部分创建状态并保持固定、无敏感信息的错误消息。

### I3. expired 负例不是单一“已过期但其余 Cloud-shaped 合法”的 token

位置：invalid case `expired` 与 `signCloudShapedToken`。

helper 默认设置 `iat=now`、`nbf=now`，expired patch 仅把 `exp` 改为 `now-1m`，结果是 `exp < iat/nbf`。这同时违反“已过期”和“有效期必须在签发时间之后”两个条件，也不是 Cloud signer 会产生的历史已过期 token。即使 Agent 的 expiration 检查失效，该 token 仍可被 lifetime consistency 检查拒绝，因此该 case 不能独立证明 expiration fail-closed。

建议：构造历史签发的 Cloud-shaped token，例如 `iat/nbf=now-2m`、`exp=now-1m`，保持 `exp > iat` 且 lifetime 不超过 verifier 上限；只让当前时间超过 exp。

### I4. URL/Cookie/Authorization 负例缺少 `translation.v1`，拒绝原因被“缺少应用协议”混淆

位置：`rejects credential outside subprotocol` 和 `rejects credential in URL`。

这三类调用给 `assertE2EUpgradeRejected` 的 `protocols` 都是 `nil`。当前 Agent 会因缺少必须的 `translation.v1` 和 `translation.jwt.*` pair 而返回 401；测试并不能区分“alternate credential location 被拒绝”与“仅仅缺少 application protocol”。未来若错误地新增一种要求同时携带 `translation.v1` 的 Authorization/Cookie/query token 支持，这些测试仍可能因构造不匹配而无法准确守住只允许 credential subprotocol 的边界。

建议：URL、Cookie、Authorization 三个 case 都携带 `Sec-WebSocket-Protocol: translation.v1`，但不携带 `translation.jwt.*`，并只把 token 放在被禁止的位置；仍要求 401、无协商 protocol、`Start == 0`。这样拒绝才能归因于 credential 不在唯一允许的位置。

## 是否可进入 controller 创建的真实 15432 E2E

**Not approved / 暂不安全进入。**

不是因为发现生产认证缺陷或 DSN 泄漏；而是测试运行自身可能遗留 helper 进程、数据库失败路径可能遗留 fixture，且两个关键负例不能单独证明目标安全属性。应先修复 I1–I4，做一次只读复审；随后 controller 才可显式创建/确认专用 `127.0.0.1:15432`，运行 focused E2E。仍不得使用、停止或修改 host `127.0.0.1:5432`，也不得在日志/报告中输出 token、完整 headers 或 DSN。
