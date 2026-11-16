> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-7-final-review.md`；原始 SHA-256 `b0d41546bfae08ac33670dc5a0064a500f112cde1277988bef7378dae32cf021`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 7 最终独立高风险评审：Cloud → Agent 授权 E2E

日期：2026-08-28
评审类型：只读最终复核
评审工作树：`[旧临时路径]`

## 结论

**Task 7：完成（Approved）。**

指定的真实受控 E2E 证据已经补齐并通过：Cloud 的真实 register/login/session HTTP route 产生 grant，grant 通过 Agent test-only 真实 HTTP/WebSocket server，Agent 使用真实 `server.New` 与真实 `sessionauth.NewVerifier`，正例进入 fake Provider；负例在 Provider 启动前被拒绝。真实 Provider、真实音频/设备和生产传输不属于 Task 7，明确延期到后续受控验证（Task 8/生产准备），不阻塞本 Task 7 授权 gate。

## 证据来源

- 真实 E2E：`[旧临时路径]`
  - disposable PostgreSQL 明确绑定 `127.0.0.1:15432`；
  - 真实 `/api/v1/auth/register`、`/api/v1/auth/login` 和 `/api/v1/translation-sessions` 请求成功；
  - `TestCloudAgentSessionAuthorization` 及其全部正负子测试通过；
  - Cloud-to-Agent authorization E2E matrix completed；
  - disposable PostgreSQL 已移除。
- 全量静态 gates：`[旧临时路径]`
  - Cloud API 与 Agent 所列 Go tests、vet、build 全部通过；
  - Agent test-only harness 相关包通过；
  - 生成 binaries 已移除。
- Task 7 既有材料：`task-7-review.md`、`task-7-rereview-1.md`、`task-7-rereview-2.md`、`task-7-fix-round-1-report.md`、`task-7-report.md`。
- 当前实现：`agent/cmd/session-test-harness/main.go`、`cloud-api/integration/agent_session_e2e_test.go`。

两份指定日志的 SHA-256（用于证据固定）：

- `pi-unified-exec-89-2b776d83.log`: `08c2c1766856eeb27d7d8f8d67c7b8c8c629917a48118e9e359bceee6a5dc305`
- `pi-unified-exec-90-108e20cd.log`: `daa4f5b2a6ce88b4ef77a0a4f911c39df19491f226a6f270352f6822c94dff67`

## 高风险验收逐项核对

### 1. Cloud 实际 route → Agent test-only 真实 WS → fake Provider

通过。

`createGrant` 实际按顺序调用 register、login 和受 bearer access token 保护的 `POST /api/v1/translation-sessions`。返回的 `session_id/user_id/install_id/token` 在内存中传递给独立启动的 Agent helper。helper 使用真实 Agent server/auth 实现，仅注入 fake Provider；不是 Agent 单测内自签 token，也不是生产 binary 或真实 Provider。

### 2. 正例 exactly-one ready/start

通过。

- WebSocket 只使用 `translation.v1` 与 `translation.jwt.<JWT>`；
- 首包使用同一 Cloud grant 的 `sessionId/userId/installId`，并保留 `s2s`、`zh→en`、`pcm`、`16000` Android-shaped 字段；
- 响应协商协议严格为 `translation.v1`，不回显 JWT credential protocol；
- 测试读取并断言一个 `ready`，随后以短超时拒绝第二个 `ready` 或任何额外事件；
- fake Provider start count 最终严格断言为 `1`；
- Agent 代码顺序确认：start 读取与 JWT 验证成功后才调用 Provider，Provider 成功后只发一次 `ready`。

指定 E2E 日志中正例子测试通过，静态代码检查与日志结果一致。

### 3. 完整负例与 Start=0

通过。

每个负例都启动独立 helper/fake Provider，避免计数污染，并断言授权错误或握手拒绝及 `Start == 0`：

- `scope=api`；
- 缺失 `scope`；
- 错误 audience；
- 错误 issuer；
- 错误签名；
- 已过期 token（历史 `iat/nbf`，仅 expiration 失效）；
- 错误 user binding；
- 错误 session binding；
- 错误 install binding；
- `translation.v1` 加禁止的 `bearer.<JWT>`；
- 仅 JWT credential protocol；
- Authorization header；
- Cookie；
- URL query。

替代 credential transport 的三个 case 都保留 `translation.v1`、不提供允许的 JWT subprotocol，从而能区分“凭据位置被拒绝”与“缺少 application protocol”。所有这类握手均要求 HTTP 401、无协商 protocol、`Start == 0`。

### 4. 进程、listener 与 fixture cleanup

通过。

- helper 先 build 到 `t.TempDir()`，再直接执行 binary，不使用无法可靠回收子孙进程的 `go run` wrapper；
- cleanup 取消 context，等待进程退出，超时则 kill 后再次等待；
- 取得地址后 cleanup 重新绑定原 loopback 地址验证 listener 已释放；
- register 成功后立即注册按 user ID 删除 fixture 的 cleanup，覆盖 login/session 后续失败路径；数据库级联清理关联 entitlement、refresh/session/device；
- 真实 E2E 日志明确记录 disposable PostgreSQL 已移除；static gate 日志明确记录 generated binaries removed。

### 5. 15432 隔离

通过。

E2E 只接受显式 `CLOUD_API_TEST_DATABASE_URL`，复用既有 isolated DSN validation，不存在默认 fallback。真实证据明确显示 disposable PostgreSQL 仅绑定 `127.0.0.1:15432` 并在结束后移除。评审期间未启动、停止、修改或连接 host `127.0.0.1:5432`。

Agent helper 使用 OS 分配的 `127.0.0.1:0`，不占用生产 `127.0.0.1:18765`；Cloud 使用 `httptest.Server`。

### 6. 凭据与敏感输出

通过。

- helper stdout 只输出 loopback 地址；stderr 为固定错误摘要；
- 测试失败信息不格式化 JWT、access token、DSN、密码、完整 headers 或 Provider credential；
- token 仅在测试进程内存中用于 HTTP/WS 调用；不进入 URL/Cookie/Authorization 的合法路径、快照或报告；
- 对两份指定日志进行了只读模式扫描：compact JWT、PostgreSQL DSN、Authorization credential value、Cookie value、private-key marker 均为 `0`；
- 代码中的 signing keys/password 是固定 test-only fixture，不是生产 secret，也没有从 Provider 配置读取凭据。

## Worktree 与 commit scope

复核结果：worktree 干净，无 staged、unstaged 或 untracked 变更。

HEAD：`7d837a972bea8c6a461f943b7e6e1720af46dac7`

Task 7 相对前一完成点 `0996056` 的变更范围仅为：

- `agent/cmd/session-test-harness/main.go`（test-only harness）；
- `cloud-api/integration/agent_session_e2e_test.go`（integration-tagged E2E）；
- `cloud-api/go.mod`（`github.com/coder/websocket v1.8.14`）；
- `cloud-api/go.sum`（对应 checksum）。

Task 7 两个 commit：

- `fb3f6dd3bac004d600a395563809f0ee1c1f3fc1` — `test: add cloud agent authorization e2e bridge`
- `7d837a972bea8c6a461f943b7e6e1720af46dac7` — `test: harden agent authorization e2e harness`

`git diff --check 0996056..HEAD` 通过；Task 7 diff 未包含环境文件、二进制、APK、缓存、数据库导出或临时脚本。

## Critical

无。

## Important

### I1. 计划中的独立 acceptance 文档尚未出现在集成 worktree

Task 7 implementation plan 的 Step 5 计划创建 `docs/reviews/internal-beta-authorization-acceptance.md`，当前 HEAD 中不存在该文件。它不削弱已经执行的 E2E 安全断言，也不构成 Task 7 的运行 gate failure，因为本报告已记录同等的 case、隔离、清理、敏感输出和 commit 证据；但若将计划文档交付物视为强制项，建议在最终 release-gate 文档阶段补齐或明确以本报告替代，避免交付清单不一致。

除此之外，未发现 Critical 或 Important。

## 延期边界

以下事项未被本 Task 7 证明，必须延期，不得由本 E2E 结果推断完成：

- 真实 Volcengine/Qwen Provider 连接与凭据注入；
- 真实 PCM 音频流、字幕/TTS 数据面；
- Android 真机或模拟器端到端翻译与正常收尾；
- HTTPS/WSS、正式域名、生产 Secret Manager、云端部署；
- 真实设备、麦克风、网络中断和生产观测验证。

最终判定：**Task 7 授权互操作测试完成；真实 Provider/audio/device 明确 deferred，不阻塞 Task 7，但仍阻塞整个内测体验闭环/Task 8 完成声明。**
