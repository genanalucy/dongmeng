> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-fix-round-1-report.md`；原始 SHA-256 `8dafebdb3e97b4a69dd0454a28881600b9f1b506980c9beea9622654a00b0ed2`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 Fix Round 1 报告：Cloud → Agent 授权 E2E 测试桥

日期：2026-08-28
Worktree：`[旧临时路径]`

## 范围

仅修改测试/harness 路径中的 `cloud-api/integration/agent_session_e2e_test.go`。未修改 Agent 或 Cloud 的生产授权、命令、Provider 配置或运行行为；未连接数据库，未启动真实 Cloud/Agent 服务。

## Important 修复

### I1：helper 构建与可靠回收

- 不再通过 `go run` 启动 helper。
- 每个 harness 先以 `go build -tags integrationtest` 输出到对应测试的 `t.TempDir()`，再直接执行生成的 binary。
- cleanup 先取消 context 并等待直接子进程；超时后显式 kill，再次等待 `Wait` 返回。
- helper 已退出后重新监听原 loopback 地址，验证临时 listener 已释放。
- helper 地址读取失败和启动超时路径同样执行停止与等待，不遗留进程。

### I2：fixture 失败路径清理

- register 成功后立即从 register 响应提取并验证 UUID user ID。
- 在 login、access token 检查或 translation-session 请求前立即注册 `t.Cleanup`。
- cleanup 仅按已验证的 user ID 执行级联删除；失败信息为固定摘要，不记录 DSN、user ID、token 或 headers。
- session grant 仍额外断言其 user ID 与注册用户一致。

### I3：单变量 expired JWT

- expired case 设置历史 `iat` 与 `nbf`，设置更晚的 `exp`，但 `exp` 仍早于当前时间。
- token 保持 `exp > iat` 的合法有效期，并位于 verifier 的最大 lifetime 内；负例仅验证 expiration 过期拒绝。

### I4：禁止 credential transport 的判别力

- URL query、Cookie、Authorization 三类负例均携带 `Sec-WebSocket-Protocol: translation.v1`。
- 三类负例均不带 `translation.jwt.*`；credential 只放在被禁止的位置。
- 仍统一断言 HTTP 401、未协商 protocol，以及 fake Provider `Start == 0`。

## 验证

本轮没有设置数据库 DSN；未连接 PostgreSQL，完整 Cloud route E2E 依其既有 guard 跳过。

- Controller 已复跑：`go test -tags integration ./integration -run '^TestAgentSessionTestHarnessStartsOnEphemeralLoopback$' -count=1`（未设置 DSN）通过。
- `git diff --check` 通过。
- 白名单与未跟踪产物检查：仅 `cloud-api/integration/agent_session_e2e_test.go` 有待提交变更；未发现本轮 Agent/Cloud binary 或 Go test artifact。
- 本报告不包含 token、DSN、完整 headers 或 user ID。

## 风险与后续

真实 Cloud route 到专用数据库的完整正负 E2E 仍需未来在受控条件下显式提供隔离 DSN 执行；本轮按约束未进行该操作。真实 Provider、生产凭据和生产服务均不在本轮范围内。
