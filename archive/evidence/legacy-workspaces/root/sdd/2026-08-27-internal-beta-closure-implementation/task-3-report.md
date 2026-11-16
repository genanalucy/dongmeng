> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-3-report.md`；原始 SHA-256 `ad661ea2a7bc8b39f14b61f0f87438f7d2ae77a15586437c72ea3f627712786f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 3：Cloud–Agent–Android 协议契约报告

## 范围

- 执行 worktree：`[旧临时路径]`
- 任务：锁定 HS256 translation session JWT、受限 WebSocket subprotocol、严格 identity binding 与 Android 16 kHz mono PCM16 2560-byte / 80-ms framing。
- Task 2 的 146 个选择性组件源文件在本任务开始时仍是未提交变更。未使用 bulk add；本任务只选择性暂存协议依赖文件。

## TDD：RED → GREEN

### RED

新增 Agent verifier/server 回归用例后执行：

```bash
cd [旧临时路径]
go test ./internal/sessionauth ./internal/server -run 'Test.*(Cloud|Session|Origin|Token)' -count=1
```

首次结果：失败，且失败原因符合新增契约缺口：

```text
--- FAIL: TestVerifyRejectsTranslationSessionTokenWithMissingOrWrongScope
    scope=: Verify() error = <nil>, want ErrInvalidToken
    scope=api: Verify() error = <nil>, want ErrInvalidToken
--- FAIL: TestSessionAuthorizationRejectsTokenWithNonTranslationScopeBeforeProviderStart
    event = server.browserEvent{Type:"ready", ...}
```

根因：`agent/internal/sessionauth.Claims` 未反序列化 `scope`，`validateClaims` 也未要求 `scope=translation`。因此 header、issuer/audience、identity binding 均正确但 `scope=api` 或缺失 scope 的 token 会通过 Agent 并启动 Provider。

### GREEN

最小修复为：为 Agent `Claims` 增加 JSON wire claim `scope`，并在现有 `validateClaims` 的严格 binding 检查中要求其精确为 `translation`。没有新增 URL token、Cookie、Authorization header 或未鉴权兼容路径。

修复后重新执行同一 Agent focused 命令：

```text
ok   translator-agent/internal/sessionauth  0.003s
ok   translator-agent/internal/server       0.010s
```

## 实际改动

### Agent

- `agent/internal/sessionauth/sessionauth.go`
  - 接收并验证 `scope` claim；仅接受 `translation`。
  - 保留 HS256、`typ=translation_session`、issuer、单一 audience、`sub == user_id`、`user_id/session_id/install_id`、时钟和 lifetime 的既有严格验证。
- `agent/internal/sessionauth/sessionauth_test.go`
  - 覆盖缺失与 `api` scope 必须失败。
- `agent/internal/server/server_test.go`
  - 覆盖 `scope=api` 授权失败会返回 `TRANSLATION_AUTH_INVALID`，fake Provider `Start` 调用数为 0。

### Cloud contract

- `cloud-api/contracttest/session_token_contract_test.go`
  - 断言 Cloud signer 输出 HS256、`typ=translation_session`、issuer/session audience、`sub == user_id`、`user_id/session_id/install_id/entitlement_id` 和 `scope=translation`。
- `cloud-api/API.md`
  - 将过时的 `/translation-sessions` 改为实际 `/api/v1/translation-sessions`，并列出验证后的 `entitlement_id` 与 `scope=translation` claims。

### Android

- `PcmPacketizerTest` 精确断言 `SAMPLE_RATE=16000`、`PACKET_BYTES=2560`、`PACKET_DURATION_MS=80`。
- `CloudAgentHandshakeTest` 精确断言 `translation.v1, translation.jwt.$token`，并验证 `StartMessage` 从同一 grant 序列化 `sessionId/userId/installId`。
- 因 Task 2 Android 文件尚未提交，本次提交包含 focused tests 的直接编译依赖：`PcmPacketizer.kt`、`CloudApi.kt`（`TranslationSessionGrant`）、`AgentProtocol.kt`、`CloudAgentHandshake.kt`，以及对应的 `AgentProtocolTest`。

### 文档

- `handoff.md`：经代码与 Android packetizer 验证，将错误的 `20 ms / 2560 bytes` 更正为 `80 ms / 2560 bytes`。
- `android/README.md` 已包含正确的 `16000 Hz / mono PCM16 / 2560 bytes / 80 ms` 描述，未修改。

## 完整验证

### Agent

```bash
cd [旧临时路径]
go test ./... && go vet ./... && go build ./cmd/translator-agent
```

结果：exit 0。

```text
ok   translator-agent/cmd/translator-agent
ok   translator-agent/internal/ast
ok   translator-agent/internal/config
ok   translator-agent/internal/server
ok   translator-agent/internal/sessionauth
```

`go build` 产生的未跟踪 `agent/translator-agent` 已删除，未暂存。

### Cloud contract

```bash
cd [旧临时路径]
go test -count=1 ./...
```

结果：exit 0。

```text
ok   github.com/dngmeng/cloud-api/contracttest  0.003s
```

### Android focused tests

```bash
cd [旧临时路径]
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17 \
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk \
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH \
./gradlew --no-daemon testDebugUnitTest \
  --tests '*PcmPacketizerTest' \
  --tests '*CloudAgentHandshakeTest' \
  --tests '*AgentProtocolTest'
```

结果：exit 0，`BUILD SUCCESSFUL in 10s`，26 actionable tasks 均为 up-to-date。

首次 focused Android 构建（修复前后同一验证周期）编译时曾打印三条既有 warning：

```text
android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceCoordinator.kt:163:24 Condition is always 'true'.
android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceCoordinator.kt:192:24 Condition is always 'true'.
android/app/src/main/java/com/verba/interpretation/ui/FaceToFaceCoordinator.kt:292:24 Condition is always 'true'.
```

本次复跑所有 Kotlin 编译 task 为 `UP-TO-DATE`，未重新输出该 warning。它不属于 Task 3 协议改动，未修改。

## 暂存与安全检查

提交前执行：

```bash
cd [旧临时路径]
git diff --check
git diff --cached --check
```

均无输出（通过）。暂存路径白名单只含 Task 3 Agent、Cloud contract/API、Android protocol/audio 及其 focused test 的直接依赖和 `handoff.md`。

提交后执行：

```bash
git show --check --stat --oneline HEAD
git status --short
```

`git show --check` 无输出（通过）。创建的提交为：

```text
bc5d50c test: lock translation session protocol contract
```

提交包含 15 个文件、584 行新增、10 行删除。提交后工作树仍保留 Task 2 的 146 个未提交选择性组件源文件；它们未被此提交纳入。

禁止路径扫描未发现 `.env*`、缓存、APK、binary、`cloud-api/cmd/local-reset-admin/` 或 `cloud-api/cloud-api`。未暂存 Task 2 的 Android UI、Cloud API backend、Admin Web、migrations 或 docker-compose。

## 关注项

- Android `FaceToFaceCoordinator.kt` 的三条 Kotlin 条件恒真 warning 仍存在，但不在本任务范围内。
- 本任务只运行了要求的 Android focused tests，未运行完整 Android test/lint/assemble。
- Cloud API 完整后端与 PostgreSQL 集成验收不属于本 Task 3；仅运行了专用 contract module。

## Fix round 1：审查修复

### 提交自洽性：选择性来源基线

审查确认 `bc5d50c` 单独检出时缺少 Android Gradle 工程和 Cloud API module/signer，因而其验证曾依赖 Task 2 的未提交工作树。未重写、reset 或改动该历史；在其后创建了完整来源基线提交：

```text
de942ec chore: integrate internal beta source baseline
```

（实际 commit：`de942ec`。）该提交把剩余的 135 个已记录来源文件纳入历史：Android 与 Android CI workflow 来自 `android-cloud-auth@35445f18f743752310e595df6378d433ec7c7fd1`；Cloud API business、migrations 与 `docker-compose.yml` 来自 `cloud-api-business@421498fe08ae470757f0e2746b09c57efe654b98`；Admin Web 仅来自 `cloud-admin-live@4f07956d2560b44bb6fa92c9486c7cc9a319aa7f`。

提交前执行了逐文件 object hash 来源核验：135 个来源文件均与对应 fixed ref 相等；唯一刻意差异是已有裁决的 `docker-compose.yml` PostgreSQL host mapping `127.0.0.1:15432:5432`，并确认旧 `127.0.0.1:5432:5432` 不存在。`git diff --check`、暂存 `git diff --cached --check` 和提交后 `git show --check` 均通过。

来源与排除检查命令（第一次执行纳入了本轮未暂存的 Agent test，因而报告了该预期非来源路径；第二次排除该 test 后成功）：

```bash
# 对 git diff --name-only 的每个来源路径按 source ref 执行：
git rev-parse "$ref:$path"
git hash-object "$path"
# 检查 135 个来源文件完全相同；docker-compose.yml 仅验证已裁决端口差异。
git diff --name-only | grep -E '(^|/)(\\.env[^/]*|node_modules|\\.gradle|\\.kotlin|app/build|local\\.properties|local-reset-admin|cloud-api/cloud-api|[^/]+\\.apk)$'
git diff --check
```

禁止路径扫描没有输出；基线未包含 `.env*`、cache、APK、`cloud-api/cloud-api` binary 或 `cloud-api/cmd/local-reset-admin/`。Android 受跟踪的 launcher 图标与 Gradle wrapper 为 fixed Android source 的项目资源，不是 APK 或 build cache。

在此基线 HEAD 从当前仓库创建临时干净 clone：

```text
[旧临时路径]
```

在 clone 中实际执行并通过：

```bash
cd [旧临时路径]
go test -count=1 ./...
# ok github.com/dngmeng/cloud-api/contracttest 0.002s

cd [旧临时路径]
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17 \
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk \
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH \
./gradlew --no-daemon clean testDebugUnitTest \
  --tests '*PcmPacketizerTest' \
  --tests '*CloudAgentHandshakeTest' \
  --tests '*AgentProtocolTest'
# BUILD SUCCESSFUL in 29s; 27 actionable tasks: 26 executed, 1 up-to-date
```

该 clean 编译重新出现既有 `FaceToFaceCoordinator.kt` 三条 `Condition is always 'true'` Kotlin warning（163、192、292 行）；不属于本轮协议/来源基线修改，未掩盖或修复。clone 在命令退出时通过 trap 删除，并以 `test ! -e [旧临时路径]` 确认清理。

### Agent server 拒绝边界

新增真实 WebSocket + fake Provider 表驱动测试，并提交为：

```text
9417753 test: cover session authorization rejection boundary
```

每一项都断言统一 `TRANSLATION_AUTH_INVALID` / 固定错误消息、日志不含 token、fake Provider `Start=0`：

- 缺失 scope；
- `scope=api`；
- 错误 issuer；
- 错误 audience；
- 过期 token；
- 错误签名；
- 错误 user、session、install binding。

另增加真实 server 握手用例，将一个合法 compact JWT 放在禁止的 `bearer.<JWT>` subprotocol，断言 HTTP 401、无协商 subprotocol、响应不泄露 token、`Start=0`。

TDD 记录：首次用例错误地将允许的两个 protocol 候选仅交换顺序定义为禁止格式，实际得到 101 / `translation.v1`。根因是既有 `sessionTokenFromRequest` 依据允许集合解析、不要求 protocol 顺序，这一行为不违反协议。将用例改为真正禁止的 `bearer.<JWT>` 后，无生产代码改动，执行：

```bash
cd [旧临时路径]
go test ./internal/server \
  -run 'TestSessionAuthorizationRejects(EveryInvalidToken|DisallowedSubprotocol)BeforeProviderStart' \
  -count=1
# ok translator-agent/internal/server 0.009s

go test ./... && go vet ./... && go build ./cmd/translator-agent
# exit 0；build artifact 已删除
```

这轮测试锁定 Agent 真实 server 拒绝边界。Cloud signer 到 Agent 的真实 WebSocket/fake Provider E2E 明确留给 Task 7：Cloud contract 是 nested Go module，且 Agent `internal` package 边界使 contract module 不能直接导入 verifier。当前 Cloud contract 仍锁定 signer 的 wire fields，Agent server tests锁定 fail-closed 拒绝边界；本轮不复制或伪造跨模块互操作。

### 最终检查与提交范围

当前额外提交范围：

```text
bc5d50c..9417753
  de942ec chore: integrate internal beta source baseline
  9417753 test: cover session authorization rejection boundary
```

执行：

```bash
cd [旧临时路径]
go test -count=1 ./...
# ok github.com/dngmeng/cloud-api/contracttest 0.002s

cd [旧临时路径]
git diff HEAD~2..HEAD --check
# 无输出，exit 0
git status --short
# 无输出
```
