> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-3-rereview-1.md`；原始 SHA-256 `0171406317cb64fb6ac1dd63f1d8aa0b55240a20d97bd3f98e112f27e3081e85`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 3 fix round 1 范围复审

## 结论

**ADDRESSED — Task 3 通过本轮范围复审。** 原 Critical/Important 1–5 均已解决；未发现修复引入新的 Critical 或 Important。

复审范围仅包括原审查、修复报告、`bc5d50c..9417753` review package、Task 3 brief、批准的 implementation plan/design Global Constraints，以及对应提交的只读检查与干净归档验证。

## 原 Findings 逐项判定

### 1. `bc5d50c` 不是可独立检出、构建和验证的提交

**ADDRESSED。** 未改写 `bc5d50c`；其直接后继 `de942ec76132e19563f35f56b434be9752454204` 纳入完整选择性来源基线，使当前 Task 3 提交链 `bc5d50c → de942ec → 9417753` 可在干净检出中构建验证。

独立逐路径核验 `de942ec` 的 135 个新增路径：

- 64 个 `.github/workflows/android-ci.yml` / `android/**` 路径与允许 ref `android-cloud-auth@35445f18f743752310e595df6378d433ec7c7fd1` 的 Git object hash 完全一致；
- 47 个 Cloud API/migrations 路径与 `cloud-api-business@421498fe08ae470757f0e2746b09c57efe654b98` 完全一致；
- 23 个 `cloud-api/admin-web/**` 路径与 `cloud-admin-live@4f07956d2560b44bb6fa92c9486c7cc9a319aa7f` 完全一致；
- 唯一裁决路径 `docker-compose.yml` 等于 Cloud 来源文件仅将 `127.0.0.1:5432:5432` 精确替换为 `127.0.0.1:15432:5432`；新映射恰好出现一次，旧宿主映射不存在。容器内 Cloud API 仍连接 `postgres:5432`。
- 没有无法映射到允许 refs 或既有 Compose 安全裁决的路径。

从 `9417753` 创建临时干净 `git archive` 后，Cloud contract、Agent 和 Android 工程入口及直接依赖均存在并可执行。

### 2. Agent server 层未完整证明所有拒绝均发生在 Provider `Start` 前

**ADDRESSED。** `9417753` 新增真实 WebSocket + fake Provider 表驱动 server 测试，覆盖：wrong issuer、wrong audience、expired token、wrong signature、wrong user、wrong session、wrong install；每项均断言统一授权错误且 `fake.starts() == 0`，并检查日志不含 token。

另有真实握手测试把合法 compact JWT 放入禁止的 `bearer.<JWT>` subprotocol，断言 HTTP 401、未协商 subprotocol、响应不泄露 token、Provider `Start=0`。该格式确属禁止位置；未把允许 protocol 候选的顺序变化错误当成非法格式。

干净归档复跑：

```text
go test ./internal/server -run 'TestSessionAuthorizationRejects(EveryInvalidToken|DisallowedSubprotocol)BeforeProviderStart' -count=1
ok translator-agent/internal/server
```

### 3. 缺失 scope 没有 server 级 Provider-before-Start 证据

**ADDRESSED。** 同一 server 表驱动测试分别覆盖完全缺失 `scope` 与 `scope=api`，两者均通过真实 WebSocket 授权路径得到 `TRANSLATION_AUTH_INVALID`，且 `fake.starts() == 0`。

### 4. Cloud contract 没有真正把 Cloud signer 输出送入 Agent verifier

**ADDRESSED（按批准计划的任务边界裁决）。** 本轮没有伪造一个跨 nested Go module / `internal` package 的直接导入，也没有把 Cloud 自解析冒充 Agent 互操作。现有 Task 3 contract 继续锁定 Cloud signer 的精确 wire fields；Agent server tests 锁定 verifier 的 fail-closed 边界。

将“Cloud API 实际签发 grant → Android 等价 subprotocol/start → Agent server → fake Provider ready/拒绝”的直接互操作保留给 Task 7，与批准的 implementation plan 完全一致：Task 7 明确要求 Cloud route/service 签发 token、连接 Agent WebSocket、正例 `ready`/`Start=1`，以及 expired/signature/user/session/install 负例 `Start=0`。因此本轮裁决不是取消 E2E，也未宣称该 E2E 已通过；它只是避免在 Task 3 重复或削弱 Task 7 的明确交付物。

### 5. Android 验证不是 fresh compile

**ADDRESSED。** 从 `9417753` 的临时干净归档执行：

```text
./gradlew --no-daemon clean testDebugUnitTest \
  --tests '*PcmPacketizerTest' \
  --tests '*CloudAgentHandshakeTest' \
  --tests '*AgentProtocolTest'
```

结果 `BUILD SUCCESSFUL in 29s`，27 个 actionable tasks 中 26 executed、1 up-to-date；Kotlin 主源码、focused tests 均重新编译。三条 `FaceToFaceCoordinator.kt` 条件恒真 warning 与报告一致，不属于 Task 3 协议修复，也未阻断构建。

同一干净归档中：

```text
(cd cloud-api/contracttest && go test -count=1 ./...)
# ok github.com/dngmeng/cloud-api/contracttest

(cd agent && go test ./... && go vet ./... && go build ./cmd/translator-agent)
# exit 0
```

## 新增 Critical / Important 检查

无。

- `de942ec` 内容完整来自批准 refs，除已批准且精确受限的 Compose `15432` 安全裁决外无来源漂移。
- `9417753` 仅修改 `agent/internal/server/server_test.go`，未弱化生产鉴权、未增加 token fallback 或兼容入口。
- `git diff --check bc5d50c..9417753`、`git show --check de942ec`、`git show --check 9417753` 均通过。
- integration worktree 在复审时干净。
- Task 7 的直接 Cloud signer → Agent E2E 仍是后续强制 gate；在其实际执行前，不得宣称跨服务授权 E2E 已通过。

## 最终判定

原 1–5：**5 ADDRESSED / 0 NOT ADDRESSED**。本轮无新增 Critical/Important，Task 3 可批准；Task 7 E2E 仍须按批准计划执行并记录真实结果。
