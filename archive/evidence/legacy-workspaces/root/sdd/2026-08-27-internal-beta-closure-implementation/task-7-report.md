> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-report.md`；原始 SHA-256 `426e08ada67edf4978bc9a54c93faeaf80784e25784a35c7acccdfb7994ae18e`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 实施报告：Cloud → Agent 授权 E2E 测试桥

日期：2026-08-28
Worktree：`[旧临时路径]`

## 实施内容

- 新增 `agent/cmd/session-test-harness/main.go`，仅在 `integrationtest` build tag 下编译：
  - 使用 OS 分配的 `127.0.0.1:0` loopback listener；不绑定生产 `127.0.0.1:18765`。
  - 注入 fake Provider，计数仅经 test-only `/test/provider-starts` 返回。
  - 复用实际 `server.New` 和 `sessionauth.NewVerifier`，不读取 Provider 配置或凭据，不改变生产命令、默认端口、Provider 路由或认证路径。
  - stdout 仅打印 loopback `host:port` ready 地址；stderr 仅输出固定错误摘要，不打印 token、DSN、header 或环境值。
- 新增 `cloud-api/integration/agent_session_e2e_test.go`，仅在 `integration` build tag 下参与编译：
  - 真正调用 Cloud `register`、`login` 和 `POST /api/v1/translation-sessions` HTTP route；access/session token 仅在测试进程内存中使用。
  - 通过本地 HTTP/WebSocket 启动 Agent helper，发送 Android-shaped `start`（同一 grant 的 `sessionId`、`userId`、`installId`，以及 `s2s/zh/en/pcm/16000`）。
  - 正向断言只协商 `translation.v1`、收到恰好一个 `ready`、fake Provider `Start == 1`；短超时拒绝第二个 `ready`。
  - 负向矩阵每项启动新的 fake Provider，覆盖 `scope=api`、缺失 scope、错误 aud/iss/签名、过期、user/session/install 绑定不符，以及禁止 subprotocol、Authorization、Cookie 和 URL credential transport；每项要求拒绝且 `Start == 0`。
  - Cloud session 数据库路径复用既有显式 `CLOUD_API_TEST_DATABASE_URL` 安全验证；没有变量时明确 skip。运行时会在测试结束删除该 E2E fixture user。
  - 不格式化、快照或打印 token、完整 headers、DSN、密码或 Provider 凭据；错误仅含固定操作摘要、HTTP status、事件类型/错误码和 Provider 计数。
- Cloud integration module 增加既有锁定版本 `github.com/coder/websocket v1.8.14`，用于真正的 WebSocket 测试客户端；`go mod tidy` 确认仅保留这两个必需的 checksum 条目。

## 静态验证

本轮未设置 `CLOUD_API_TEST_DATABASE_URL`，未连接 PostgreSQL、未使用或修改 host `127.0.0.1:5432`、未启动真实 Cloud/Agent 服务，也未连接真实 Provider。

- `agent`: `go test ./...` 通过。
- `agent`: `go vet ./...` 通过。
- `agent`: `go build ./cmd/translator-agent` 通过；生成产物已删除。
- `agent`: `go test -tags=integrationtest ./cmd/session-test-harness` 通过。
- `agent`: `go build -tags=integrationtest ./cmd/session-test-harness` 通过；输出到临时路径并删除。
- `cloud-api`: `go test -tags=integration -v ./integration -run 'Test(AgentSessionTestHarnessStartsOnEphemeralLoopback|CloudAgentSessionAuthorization)' -count=1` 通过：helper loopback health/start-count case 通过；真正 route E2E 因未设置专用 DSN 正确 skip。
- `cloud-api`: `go test -count=1 ./...` 通过。
- `cloud-api`: `go vet ./...` 通过。
- `cloud-api`: `go build ./cmd/cloud-api`、`go build ./cmd/migrate` 通过；生成产物已删除。
- 两个 Go module 的 `go mod tidy` 已执行；Agent 无模块变化；Cloud 仅保留 E2E WebSocket 客户端所需依赖变更。
- `git diff --check` 通过；产物检查确认本轮生成的 Agent/Cloud binary 已删除；白名单外没有待提交改动。

## 延后验证和风险

完整 Cloud route → 专用 PostgreSQL → Agent helper WebSocket 的正负矩阵尚未运行，因为本轮没有连接数据库。后续控制器只能在代码复审后，显式提供并再次确认目标为 `127.0.0.1:15432` 的专用 `CLOUD_API_TEST_DATABASE_URL` 时运行：

```text
go test -tags=integration ./integration -run TestCloudAgentSessionAuthorization -count=1
```

该后续步骤仍不得使用、停止或修改 host `127.0.0.1:5432`；不应把 token、DSN、headers 或 Provider credentials 写入日志或报告。真实 Provider、真实音频、Android 设备、HTTPS/WSS 与生产 secret 注入不在本 Task 的静态验证范围。
