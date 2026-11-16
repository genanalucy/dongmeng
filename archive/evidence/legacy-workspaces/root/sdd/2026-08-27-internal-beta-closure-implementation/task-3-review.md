> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-3-review.md`；原始 SHA-256 `9ab2dccd5d082ce88907a8c8283c8d4c3f102dad81cd05cab1811beaa07a37d8`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 3 独立审查

**审查范围：** `task-3-brief.md`、`task-3-report.md`、`review-3e0b93f..bc5d50c.diff`、设计规范及实施计划 Global Constraints；并只读检查 integration worktree 与提交 `bc5d50c`。

**Spec compliance: ❌**

**Task quality: Not approved**

## Critical

### 1. `bc5d50c` 不是可独立检出、构建和验证的提交

本次选择性提交纳入了 Android 的以下实现/测试文件：

- `PcmPacketizer.kt`
- `CloudApi.kt`
- `AgentProtocol.kt`
- `CloudAgentHandshake.kt`
- 三个 focused test

但没有纳入 Android Gradle 工程入口和这些源文件的直接类型依赖。对 `bc5d50c` 做干净 `git archive` 后：

- `android/gradlew`、`android/settings.gradle.kts`、`android/build.gradle.kts`、`android/app/build.gradle.kts` 均不存在，要求的 Android 测试命令无法启动；
- `CloudApi.kt` 引用的 `CloudEndpointSettings`、`TokenStore`、`InstallationIdStore`、`AuthTokens`、`UsageRecordPayload` 均不在该提交树中；
- 因此即使补一个外部 Gradle wrapper，该提交中的 Android 源也不能独立编译。

同样，提交加入了 `cloud-api/contracttest`，其 `go.mod` 通过 `replace github.com/dngmeng/cloud-api => ..` 依赖 Cloud API 主模块，但 `bc5d50c` 没有提交 `cloud-api/go.mod` 或 `cloud-api/internal/auth/tokens.go`。在 `bc5d50c` 的干净 archive 中执行 `cloud-api/contracttest/go test -count=1 ./...`，实际失败：

```text
reading ../go.mod: .../cloud-api/go.mod: no such file or directory
FAIL github.com/dngmeng/cloud-api/contracttest [setup failed]
```

报告中的 Android 与 Cloud contract 成功结果依赖工作树中仍未提交的 Task 2 文件，而不是提交 `bc5d50c` 自身。任务要求“只从已提交历史整合”、可重复验证，并特别要求选择性提交保持依赖一致；当前提交边界不满足该要求。

**建议：** 在不夹带 Tasks 4–6 无关实现的前提下，重新设计提交边界。至少应先提交或同一原子提交纳入 Android 构建骨架、所选 Android 源的全部直接编译依赖，以及 Cloud API module/signer 的必要已验证源；然后从干净检出重新运行 Task 3 的三组命令。若 Task 2 的架构本来就是“文件保持未提交直到 Tasks 3–6 验证”，则不能提前把依赖这些未提交文件的 Task 3 测试声明为可独立提交完成，应调整任务/提交顺序并记录原因。

## Important

### 2. Agent server 层未完整证明 brief 要求的所有拒绝均发生在 Provider `Start` 前

Agent verifier 层已有 issuer、audience、签名、过期、user/session/install 等负例，server 层已有签名与三重 binding 负例及 `fake.starts()==0`。本次新增的 `scope=api` server 用例也正确证明 Provider 未启动。

但 brief 明确要求 Agent server cases 对下列负例均断言 Provider `Start` 为 0：mismatched `userId`、`sessionId`、`installId`、issuer、audience、signature、expired token，以及 token 位于不允许的 subprotocol 格式。当前 server 测试中：

- 没有 wrong issuer 的 server/Provider-zero 用例；
- 没有 wrong audience 的 server/Provider-zero 用例；
- 没有 expired token 的 server/Provider-zero 用例；
- malformed/missing/Authorization-only subprotocol 用例有 Provider-zero 断言，但合法 compact JWT 被放在不允许的 protocol 组合/位置时，主要只由 `sessionTokenFromRequest` 单元表覆盖，未全面通过 server 握手路径证明 Provider-zero。

因此“所有指定 Agent server case 均在 Provider Start 前 fail-closed”的测试证据不完整。

**建议：** 将 signer fixture 扩展为可覆盖 issuer/audience/time/scope，并新增表驱动 server 测试；每个拒绝项都断言错误/HTTP 状态、token 不泄露和 `fake.starts()==0`。

### 3. 缺失 scope 仅在 verifier 层有回归用例，Provider-before-Start 要求没有 server 级证据

实现本身在 `sessionauth.validateClaims` 中要求 `claims.Scope == "translation"`，所以从代码路径看，`scope=api` 与缺失 scope 均会在 `astClient.Start` 之前失败；focused Agent 测试也通过。

但 server 新增用例只测试 `scope=api`，没有测试“scope claim 完全缺失”时 `TRANSLATION_AUTH_INVALID` 且 `fake.starts()==0`。用户明确要求重点确认 `scope=api/缺失 scope` 都确实在 Provider Start 前拒绝，当前对缺失 scope 的结论依赖实现推断与 verifier 单测，不是完整 server 边界回归锁定。

**建议：** server 层用表驱动分别签发 `scope=api` 与不含 `scope` 的 token，均断言 `fake.starts()==0`。

### 4. Cloud contract test 没有真正把 Cloud signer 输出送入 Agent verifier

`session_token_contract_test.go` 使用 Cloud signer 后，又用测试自身的 `jwt.ParseWithClaims` 解析。它注释称检查 Agent verifier 所绑定的全部 claims，但 contract module 实际没有调用 Agent verifier；`translator-agent` 依赖甚至没有被 Go 源导入。这样会复制而不是复用 Agent 的规则，未来 Agent verifier 规则变化时可能产生双边测试都绿但 wire contract 已漂移的风险。

当前 wire names 基本一致：

- `alg=HS256`、`typ=translation_session`；
- `iss=cloud-api`、session `aud=translator-agent`；
- `sub == user_id`；
- `user_id`、`session_id`、`install_id`、`entitlement_id`、`scope=translation`；
- Android `TranslationSessionGrant(sessionId,userId,installId,token)`，handshake 使用 `translation.v1, translation.jwt.<token>`，start 使用同一 grant 的三重字段。

但“Cloud-issued JWT pass Agent verification”尚未由跨模块测试直接证明；尤其 Agent `Claims` 并不解析或要求 `entitlement_id`，Cloud contract test 对 entitlement 的断言只是 Cloud 自检，并非 Agent 消费契约。设计中 Agent 必须绑定的是 user/session/install；若 `entitlement_id` 只是必须存在的 wire claim，应在契约中明确它由 Cloud 保证、Agent忽略；若要求 Agent 验证其存在，则实现与测试均不足。

**建议：** 把 Agent 可复用 verifier 移到可导入包/共享 contract harness，或在 Agent 模块增加一个真正使用 Cloud signer 产物的测试夹具；同时明确 `entitlement_id` 是 Cloud-only informational claim 还是 Agent 必须校验的 claim。

### 5. Android 验证不是 fresh compile，无法排除未提交依赖或缓存掩盖问题

报告与复验输出均显示 focused Android tasks 全部 `UP-TO-DATE`。这只能说明当前污染 worktree 的既有构建缓存接受了当前文件集合，不能证明本次选择性提交在干净环境可编译。上述 clean archive 已直接证明提交缺少构建入口和依赖。

**建议：** 修正提交边界后使用干净 checkout，并至少执行 `./gradlew --no-daemon clean testDebugUnitTest ...`；不得以 `UP-TO-DATE` 结果作为选择性提交自洽性的证据。

## Minor

### 6. Android 2560 bytes / 80 ms 与 subprotocol 断言本身精确，建议拆分测试以提高失败定位

所要求的四条精确断言均存在：

```kotlin
assertEquals(16_000, PcmPacketizer.SAMPLE_RATE)
assertEquals(2_560, PcmPacketizer.PACKET_BYTES)
assertEquals(80, PcmPacketizer.PACKET_DURATION_MS)
assertEquals("translation.v1, translation.jwt.$token", CloudAgentHandshake.subprotocols(grant))
```

`CloudAgentHandshakeTest` 也从同一个 `TranslationSessionGrant` 验证了 `sessionId/userId/installId`。协议值符合设计，且 Agent `PCMFrameBytes=2560`。

不过 framing 常量断言与 packet accumulation 行为混在一个测试中，handshake string 与 start binding 也混在一个测试中。建议拆成独立测试，以便常量、分包、subprotocol 和 grant binding 任一漂移时得到精确失败信息。这不阻断验收。

### 7. 文档改动内容有代码依据，但 `cloud-api/API.md` 作为整文件新提交放大了 Task 3 的归属范围

实际语义修正均可由源码/路由证实：

- `/translation-sessions` 改为 `/api/v1/translation-sessions`；
- 增加 `entitlement_id` 与 `scope=translation`；
- `handoff.md` 从错误的 `20 ms / 2560 bytes` 更正为正确的 `80 ms / 2560 bytes`；
- `android/README.md` 已正确，因此未修改。

未发现文档虚构新的兼容入口。但由于父提交中整个 `cloud-api/API.md` 不存在，本提交实际新增完整 60 行 API 文档，而不是只提交两处事实修正。该文件来自已批准 Cloud business 历史，未发现机密，但进一步体现了 Task 2 基础文件与 Task 3 变更被拆成不自洽提交的问题。

### 8. 未发现本提交夹带机密、缓存、APK、二进制或明确无关代码

对 `3e0b93f..bc5d50c` 的路径与 diff 检查未发现：

- `.env*`、`local.properties`、FRP、CSV、APK；
- `.gradle`、`app/build`、Node cache；
- `cloud-api/cloud-api`、`cmd/local-reset-admin/`；
- 私钥或明显真实凭据。

`git diff --check` / `git show --check` 通过。提交中的固定 HMAC key 仅为测试夹具。主要问题不是泄密或缓存，而是选择性提交漏掉必要依赖。

## 结论

`scope=translation` 已在 Agent claims/验签路径 fail-closed，`scope=api` 也有 server 级 Provider-zero 证据；Android 协议常量与 wire names 正确；文档事实修正有依据，且未发现禁入资产。

但 Task 3 仍不能批准：提交 `bc5d50c` 对 Android 和 Cloud contract 都不是独立可构建提交，成功验证依赖 146 个未提交 Task 2 文件；同时缺失 scope、wrong issuer/audience/expired token 等 server 层 Provider-before-Start 覆盖不完整，Cloud signer 也未真正与 Agent verifier 做同一测试路径的互操作验证。修复提交边界并补齐上述测试后应重新审查。
