> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-rereview-2.md`；原始 SHA-256 `defdf61a0d9f870fc983f911a408c112da13ef2f823d333c58ccf2551686ee88`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 复审 2：Round 1 修复

日期：2026-08-28
评审范围：`7d837a9` 相对 `fb3f6dd`；Task 7 初审、复审 1、Round 1 修复报告及 `review-fb3f6dd..7d837a9.diff`
工作树：`[旧临时路径]`
方式：高风险只读评审；未修改被审工作树，未运行数据库、服务、测试或代理。

## 结论

**Approved for controlled full E2E。复审 1 的 I1–I4 均已解决；未发现新增 Critical 或 Important，也未发现 token、DSN 或生产凭据泄漏。**

现在可由 controller 创建一次性、明确绑定 **`127.0.0.1:15432`** 的 PostgreSQL，并在显式提供经过既有 guard 校验的 `CLOUD_API_TEST_DATABASE_URL` 后运行完整 focused E2E。仍禁止接触 host `127.0.0.1:5432`，不得输出 DSN、token 或完整 headers；运行后应销毁 disposable 数据库并确认无 helper 进程/监听端口残留。

## 复审证据

- 被审工作树 HEAD 为 `7d837a972bea8c6a461f943b7e6e1720af46dac7`，base 为 `fb3f6dd3bac004d600a395563809f0ee1c1f3fc1`。
- commit 范围仅修改 `cloud-api/integration/agent_session_e2e_test.go`；没有生产 Agent/Cloud 命令、认证、Provider 或 store 代码变化。
- `git diff --check fb3f6dd..7d837a9` 静态通过。
- 本次依约未执行 Go test、未启动 helper、未连接 PostgreSQL；最终运行结论仍须以 controller 的真实 full E2E 结果为准。

## I1：直接 helper 进程回收与端口释放

**Resolved。**

- harness 不再使用 `go run` wrapper，而是先将 test-only helper 构建到 `t.TempDir()`，随后以 `exec.CommandContext` 直接启动该 binary；`done` 对应的就是实际监听进程的 `Wait()`。
- cleanup 先取消 context并等待退出；3 秒未退出则对直接进程执行 `Kill`，随后再次等待 `Wait`，避免只报告超时却遗留进程。
- 地址读取失败和 3 秒启动超时路径同样进入统一停止逻辑；地址读取 goroutine 的结果 channel 有缓冲，进程退出后不会因无人接收而阻塞。
- 正常取得地址后，cleanup 在进程退出并完成 `Wait` 后对原地址执行 `net.Listen("tcp", address)`，成功后关闭该 listener，明确验证原 loopback 端口已释放。
- helper 本身不派生其他服务进程；因此直接进程回收覆盖该 test-only listener 生命周期。

## I2：fixture cleanup 注册时机

**Resolved。**

- `createGrant` 在真实 register 返回 `201` 后立即提取并严格验证响应中的 non-nil UUID user ID。
- 随即注册按该 user ID 删除 `users` 记录的 `t.Cleanup`，注册发生在 login、access-token 检查及 translation-session 创建之前。
- 因外键级联，后续任一步失败时，已创建的 trial entitlement、refresh/session/device 等关联 fixture 均纳入回收。
- cleanup 使用独立 5 秒 context；错误仅为固定摘要，不输出 user ID 或 DSN。
- grant 完成后又断言 `grant.UserID` 与注册 user ID 一致，避免清理对象与测试授权对象漂移。

## I3：expired 仅验证 expiration

**Resolved。**

expired token 现在使用：

- `iat = now - 2m`
- `nbf = now - 2m`
- `exp = now - 1m`

因此 `exp > iat/nbf`、token lifetime 为 1 分钟且保持 Cloud-shaped claim/header/signature；签发与生效时间均在过去，唯一预期失效属性是当前时间已超过 `exp`。该 case 不再可被 `exp < iat/nbf` 的一致性错误替代拒绝。

## I4：`translation.v1` 下的替代 credential transport

**Resolved。**

Authorization、Cookie、URL query 三个 case 均：

- 携带 `Sec-WebSocket-Protocol: translation.v1`；
- 不携带允许的 `translation.jwt.<JWT>` credential subprotocol；
- 只把 token 放在各自被禁止的位置；
- 要求 HTTP 401、响应不协商 subprotocol、fake Provider `Start == 0`。

这消除了“仅因缺少 application protocol 而拒绝”的混淆，能够阻止未来误加 Authorization/Cookie/query credential fallback。

## 新增 Critical / Important

### Critical

无。

### Important

无。

每个 harness 单独执行一次缓存友好的 `go build` 会增加完整矩阵耗时，但不削弱安全断言、隔离或资源回收，当前不构成 gate blocker。controller 运行时仍应关注总体测试时限与失败后的进程/端口检查。

## token / DSN / credential 泄漏检查

未发现泄漏：

- 新增失败信息均为固定摘要，不格式化 JWT、access token、完整 request/response headers、密码、user ID 或 DSN。
- alternate transport 用例仅在内存构造 Authorization/Cookie/query；断言失败不会打印其值。
- helper 环境变量仅包含固定 test-only signing key、issuer/audience/origin；没有读取或记录生产 secret/Provider credential。
- 新增代码没有硬编码或打印 PostgreSQL DSN，也没有放宽既有显式 DSN/`127.0.0.1:15432` guard。
- diff 中出现的 `integration-password` 和 `e2e-*-signing-key` 是固定测试 fixture，不是生产凭据。

## Controller 下一步授权边界

**可以进入受控 full E2E，但仅限以下条件：**

1. controller 创建全新 disposable PostgreSQL，host endpoint 精确为 `127.0.0.1:15432`；
2. 不使用、停止、重配或探测业务用途的 `127.0.0.1:5432`；
3. 仅通过环境变量向 focused test 显式提供专用 DSN，并依赖现有 target guard fail closed；
4. 运行 `cloud-api` focused `TestCloudAgentSessionAuthorization` 完整正负矩阵；不连接真实 Provider，不启动生产 Cloud/Agent 命令；
5. 日志和报告只记录 case、状态及计数，不记录 DSN、JWT、headers 或密码；
6. 结束后销毁 disposable PostgreSQL，并确认 helper 进程已退出、临时 loopback listener 已释放、fixture cleanup 无错误。

静态复审已解除执行 gate；Task 7 是否最终通过，仍取决于上述真实 full E2E 全部通过且清理检查无异常。
