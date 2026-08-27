# 言枢智能内测闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可重复验证的 Android 内测闭环：普通用户注册/登录、权益、Agent 授权、真实翻译、会话结束和一次聚合用量，并验证 Admin Web 最小 RBAC。

**Architecture:** 以 `main` 的 Web/Agent 为基线，在新建的干净集成 worktree 中选择性引入 Android、Cloud API 业务实现和 Admin Web。Cloud API 是控制面，Agent 是数据面；会话 JWT 只经 WebSocket subprotocol 传递并绑定 user/session/install。先以组件验证和契约测试建立证据，再做受控环境 E2E 与用户真机验收。

**Tech Stack:** Go、PostgreSQL 17、React/Vite/TypeScript、Kotlin/Jetpack Compose、OkHttp、Gradle、Vitest、Go testing。

**Spec:** `docs/superpowers/specs/2026-08-27-internal-beta-closure-design.md`

## Global Constraints

- 仅从已提交历史整合：`main@44e042b`、`android-cloud-auth@35445f1`、`cloud-api-business@421498f`、`cloud-admin-live@4f07956`。
- 不执行 `git add .`；不纳管环境文件、CSV、FRP 配置、APK、缓存、二进制、数据库导出、临时脚本或 `cmd/local-reset-admin/`。
- Cloud PostgreSQL 仅允许使用 `127.0.0.1:15432`；禁止停止、迁移或修改 `127.0.0.1:5432`。
- Session JWT 仅经 `Sec-WebSocket-Protocol: translation.v1, translation.jwt.<JWT>` 传递，不能出现在 URL、Cookie、Authorization header、日志、错误响应或测试快照。
- PCM 统一为 mono PCM16、16 kHz、`2560 bytes`/包，即 `80 ms`；不得以无版本协商的双包长掩盖协议分裂。
- Android Debug 的公网 `http/ws` 仅限受控、短期内测；不得作为生产或扩大测试人群的方案。Release 只能接受 HTTPS/WSS。
- 真实 Provider 不可用时必须明确失败，不得静默 fallback 到 Mock。
- 每项改动必须先有失败测试，后最小实现，最后运行相关检查；验证失败时停止并记录根因。

---

## File and ownership map

| 路径 | 责任 |
|---|---|
| `agent/internal/sessionauth/sessionauth.go` | Agent 对 Cloud session JWT 的信任边界 |
| `agent/internal/server/server.go` | Origin、subprotocol、start、PCM、Provider 启动顺序 |
| `agent/internal/server/server_test.go` | Agent WebSocket 授权和 Provider 不触达断言 |
| `cloud-api/internal/auth/tokens.go` | Cloud session JWT claims/TTL/签名 |
| `cloud-api/contracttest/session_token_contract_test.go` | Cloud signer 与 Agent verifier 的 wire contract |
| `cloud-api/internal/store/{postgres,business,lifecycle}.go` | PostgreSQL 业务持久化和 session/usage 生命周期 |
| `migrations/*.sql` | 现有 schema 迁移；不可改写已发布 migration |
| `android/app/src/main/.../audio/PcmPacketizer.kt` | Android 80ms PCM framing |
| `android/app/src/main/.../protocol/{CloudAgentHandshake,AgentSocket}.kt` | JWT subprotocol、start 三重绑定、WS 帧生命周期 |
| `android/app/src/main/.../cloud/TranslationSessionCoordinator.kt` | Cloud session end 与单次 usage 上报 |
| `android/app/src/main/.../ui/{InterpretationViewModel,FaceToFaceViewModel}.kt` | 单人/面对面收尾时序 |
| `cloud-api/admin-web/src/api/cloudApi.ts` | Admin API typed client 与 envelope 解析 |
| `cloud-api/admin-web/src/{App,api/cloudApi}.test.ts(x)` | RBAC、refresh 和 users/audit contract 测试 |

## Task 1: 冻结来源并创建干净集成 worktree

**Files:**
- Create: `docs/reviews/internal-beta-source-manifest.md`
- Do not copy: root untracked `android/`, `cloud-api/`, `docs/`, `ui/`, `.pi/`.

**Consumes:** 当前已批准规范和四个权威提交。

**Produces:** 一个仅包含已提交历史的 integration worktree；来源清单供后续任务使用。

- [ ] **Step 1: 验证四个权威 worktree 的 commit 与污染情况**

Run:
```bash
for repo in \
  /home/genanalucy/dngmeng_project \
  /tmp/dngmeng-android-cloud-auth \
  /tmp/dngmeng-cloud-api-business \
  /tmp/dngmeng-cloud-admin-live; do
  printf '\n== %s ==\n' "$repo"
  git -C "$repo" status --short --branch
  git -C "$repo" log -1 --oneline
  git -C "$repo" diff --check
 done
```

Expected: `main` is `44e042b`; Android is clean at `35445f1`; Cloud API source is `421498f` while its local binary and `cmd/local-reset-admin/` remain explicitly untracked and excluded; Admin is clean at `4f07956`.

- [ ] **Step 2: 创建隔离整合 worktree，不修改现有 worktree**

Run from repository root:
```bash
integration=/tmp/dngmeng-internal-beta-integration
test -e "$integration" && { echo "integration path already exists"; exit 1; }
git worktree add -b internal-beta-integration "$integration" 44e042b
```

Expected: `git -C "$integration" status --short --branch` only shows `internal-beta-integration` and no working-tree changes.

- [ ] **Step 3: 写入来源和排除项清单**

Record the exact source refs, date, and exclusions in `docs/reviews/internal-beta-source-manifest.md`. It must state that `cloud-api/cloud-api`, `cloud-api/cmd/local-reset-admin/`, `agent/.env.local`, all `.env*`, APKs, Gradle/Node caches, FRP configuration, and root untracked directories are not integration inputs.

- [ ] **Step 4: Verify source manifest and worktree cleanliness**

Run:
```bash
git -C /tmp/dngmeng-internal-beta-integration diff --check
git -C /tmp/dngmeng-internal-beta-integration status --short
git -C /tmp/dngmeng-internal-beta-integration check-ignore -v agent/.env.local || true
```

Expected: no diff errors; no unintended files; local credential file is ignored.

- [ ] **Step 5: Commit only the source manifest**

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- docs/reviews/internal-beta-source-manifest.md
git diff --cached --check
git commit -m "docs: record internal beta integration sources"
```

## Task 2: 选择性整合 Android、Cloud API 和 Admin Web

**Files:**
- Add from `android-cloud-auth`: `.github/workflows/android-ci.yml`, `android/**`, required `branding/**` assets only.
- Add from `cloud-api-business`: `cloud-api/**`, `migrations/**`, `docker-compose.yml`, required root `Makefile`/`README.md` changes, and compatible Agent session-auth changes.
- Add from `cloud-admin-live`: only `cloud-api/admin-web/**` and its direct documentation/tests.
- Modify: `.gitignore` to exclude Android local/build caches if their tracked Android `.gitignore` does not fully protect root operations.

**Consumes:** Task 1 integration worktree and source manifest.

**Produces:** one coherent source tree containing all intended code, without old placeholder Cloud API server files from `cloud-admin-live`.

- [ ] **Step 1: Inspect exact commit ranges before applying**

Run:
```bash
cd /tmp/dngmeng-internal-beta-integration
git diff --name-status $(git merge-base main android-cloud-auth) android-cloud-auth -- android branding .github
git diff --name-status $(git merge-base main cloud-api-business) cloud-api-business -- cloud-api migrations docker-compose.yml Makefile README.md agent
git diff --name-status $(git merge-base main cloud-admin-live) cloud-admin-live -- cloud-api/admin-web
```

Expected: Admin candidate list contains `cloud-api/admin-web/**` only; do not copy its `cloud-api/internal/**`, `migrations/**`, root Agent, Makefile, or README versions.

- [ ] **Step 2: Apply Android committed paths and inspect conflict set**

Use a path-limited checkout from `android-cloud-auth`:
```bash
git checkout android-cloud-auth -- .github/workflows/android-ci.yml android branding
```

Run:
```bash
git diff --check
git status --short
```

Expected: source files only; no `android/.gradle`, `android/app/build`, `android/local.properties`, APK or cache.

- [ ] **Step 3: Apply Cloud API business paths and inspect conflict set**

```bash
git checkout cloud-api-business -- cloud-api migrations docker-compose.yml
```

For root `Makefile`, `README.md`, and Agent files, inspect three-way differences first:
```bash
git diff main...cloud-api-business -- Makefile README.md agent
```

Apply only changes required for the Cloud API/Agent session contract. Resolve each conflict by retaining the current `main` Web/Agent behavior plus business-branch session-auth compatibility. Do not replace root files wholesale.

- [ ] **Step 4: Apply only Admin Web paths**

```bash
# 此命令只从 Admin 分支读取管理前端路径；不会覆盖前一步已引入的业务后端。
git checkout cloud-admin-live -- cloud-api/admin-web
```

Verify that Admin source selection remains path-limited, while the real backend still comes from `cloud-api-business`:
```bash
git diff --name-only cloud-api/admin-web
git diff --name-only -- cloud-api/internal cloud-api/cmd cloud-api/go.mod migrations
```

Expected: the first command lists only Admin Web paths; the second command lists only Cloud API business-path changes introduced in Step 3, never files sourced from `cloud-admin-live`.

- [ ] **Step 5: Make root ignore policy safe and add a regression check**

Add root `.gitignore` rules for:
```gitignore
android/.gradle/
android/.kotlin/
android/app/build/
android/local.properties
*.apk
```

Add or update an ignore-policy test/script only if this repository already has a suitable shell test convention; otherwise verify with:
```bash
git check-ignore -v android/.gradle android/app/build android/local.properties
```

Expected: all local Android generated/config paths are ignored while `android/app/src/**`, Gradle wrapper and build scripts remain trackable.

- [ ] **Step 6: Review integration content, then commit source integration**

Run:
```bash
git diff --check
git status --short
git diff --stat
git diff --name-only | grep -E '(^|/)(\.env|node_modules|\.gradle|app/build|local\.properties|local-reset-admin|cloud-api/cloud-api)' && exit 1 || true
```

Expected: no forbidden files. Commit only after Tasks 3-6 component checks pass; do not commit an unverified integration snapshot.

## Task 3: Lock the Cloud–Agent–Android protocol contract

**Files:**
- Modify: `agent/internal/server/server_test.go`
- Modify: `agent/internal/sessionauth/sessionauth_test.go`
- Modify: `cloud-api/contracttest/session_token_contract_test.go`
- Modify: `android/app/src/test/java/com/verba/interpretation/audio/PcmPacketizerTest.kt`
- Modify: `android/app/src/test/java/com/verba/interpretation/protocol/CloudAgentHandshakeTest.kt`
- Modify documentation: `handoff.md`, `android/README.md`, and `cloud-api/API.md` only where they claim 20ms/2560 bytes or obsolete endpoint behavior.

**Consumes:** integrated Cloud token signer, Agent verifier, Android handshake and packetizer.

**Produces:** one tested protocol: HS256 translation JWT; `translation.v1` plus private token candidate; strict user/session/install binding; mono PCM16 16kHz 2560-byte / 80ms packets.

- [ ] **Step 1: Write failing Agent/Cloud contract cases**

Add tests that make a Cloud-issued JWT pass Agent verification only when:
- header is `alg=HS256`, `typ=translation_session`;
- issuer and session audience match configuration;
- `sub == user_id`, and `user_id`, `session_id`, `install_id` match expected bindings;
- token is valid at current clock and under configured TTL.

Add negative cases for a mismatched `userId`, `sessionId`, `installId`, issuer, audience, signature, expired token, and a token supplied outside the allowed subprotocol format. Each Agent server case must assert fake Provider `Start` was called `0` times.

- [ ] **Step 2: Run failures before implementation changes**

```bash
cd /tmp/dngmeng-internal-beta-integration/agent
go test ./internal/sessionauth ./internal/server -run 'Test.*(Cloud|Session|Origin|Token)' -count=1
cd /tmp/dngmeng-internal-beta-integration/cloud-api/contracttest
go test -count=1 ./...
```

Expected: new tests initially fail only when a documented mismatch exists; do not change behavior until the mismatch is identified.

- [ ] **Step 3: Make the smallest compatibility change**

Resolve only evidenced field, claim-name, audience, header parsing, or test-fixture differences. Preserve these public wire names:

```text
subprotocol: translation.v1, translation.jwt.<compact-JWT>
start fields: sessionId, userId, installId
JWT custom claims: user_id, session_id, install_id, entitlement_id, scope=translation
PCM: 16000 Hz, mono PCM16, 2560 bytes, 80 ms
```

Do not add a URL-token fallback, Cookie fallback, Authorization-header fallback, or unauthenticated compatibility path.

- [ ] **Step 4: Lock Android framing and handshake tests**

Ensure exact assertions exist:

```kotlin
assertEquals(16_000, PcmPacketizer.SAMPLE_RATE)
assertEquals(2_560, PcmPacketizer.PACKET_BYTES)
assertEquals(80, PcmPacketizer.PACKET_DURATION_MS)
assertEquals("translation.v1, translation.jwt.$token", CloudAgentHandshake.subprotocols(grant))
```

Ensure `StartMessage` serializes `sessionId`, `userId`, and `installId` from the same `TranslationSessionGrant`.

- [ ] **Step 5: Run protocol verification**

```bash
cd /tmp/dngmeng-internal-beta-integration/agent
go test ./... && go vet ./... && go build ./cmd/translator-agent
cd /tmp/dngmeng-internal-beta-integration/cloud-api/contracttest
go test -count=1 ./...
cd /tmp/dngmeng-internal-beta-integration/android
./gradlew --no-daemon testDebugUnitTest --tests '*PcmPacketizerTest' --tests '*CloudAgentHandshakeTest' --tests '*AgentProtocolTest'
```

Expected: all pass. If Android and Agent packet size differs, stop integration and resolve the protocol rather than accepting both formats implicitly.

- [ ] **Step 6: Commit protocol changes**

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- agent cloud-api/contracttest android handoff.md android/README.md cloud-api/API.md
git diff --cached --check
git commit -m "test: lock translation session protocol contract"
```

## Task 4: Make Cloud API database setup repeatable and validate business lifecycle

**Files:**
- Create: `cloud-api/cmd/migrate/main.go` or `cloud-api/internal/migrate/runner.go` with a focused CLI entry point.
- Create: migration runner tests under `cloud-api/internal/migrate/`.
- Modify: `cloud-api/Makefile`, `cloud-api/README.md`, `docker-compose.yml` only to document/use the runner.
- Test: `cloud-api/integration/postgres_business_test.go` and new migration tests.

**Consumes:** `migrations/000001_init.up.sql`, `000002_refresh_tokens_expiry_idx.up.sql`, `000003_business_lifecycle.up.sql`.

**Produces:** repeatable, ordered, checksummed migration execution that supports `CREATE INDEX CONCURRENTLY` outside a transaction and does not use destructive down migrations for normal operation.

- [ ] **Step 1: Write a failing isolated PostgreSQL migration test**

Use an explicitly supplied test DSN (never default to production/local developer DSN). Test behavior:
1. runner creates a schema migration ledger with version, checksum and applied timestamp;
2. runs `000001`, `000002`, `000003` in lexical order;
3. executes `000002` outside a transaction;
4. repeated run is a no-op;
5. checksum mismatch fails before applying later migrations.

Skip with a clear message only when the dedicated test DSN is absent; normal unit tests must remain runnable without secrets.

- [ ] **Step 2: Run test to prove missing runner**

```bash
cd /tmp/dngmeng-internal-beta-integration/cloud-api
go test ./internal/migrate -count=1
```

Expected: fail until runner exists, or compile failure identifying the missing package.

- [ ] **Step 3: Implement focused runner**

Implement a runner that:
- embeds or reads only repository migration files in lexicographic numeric order;
- computes SHA-256 checksum of each SQL file;
- uses a dedicated ledger table;
- wraps ordinary migration SQL in one transaction per migration;
- detects `CREATE INDEX CONCURRENTLY` and executes that migration outside a transaction;
- rejects every `.down.sql` in normal `up` command;
- accepts DSN only from an explicit environment variable/flag without logging it.

Do not introduce an ORM or a new migration dependency.

- [ ] **Step 4: Add business lifecycle PostgreSQL E2E cases**

Extend existing PostgreSQL integration coverage to prove:

```text
register → one 3-day trial
redeem normalized code → one 365-day stacked entitlement
create session → second active session returns conflict
write usage once → second usage for same session conflicts
end/revoke → next session can be created
cross-user usage/session association is rejected
```

All fixtures must generate unique email/install/session values and must not print passwords, JWTs, DSNs, or full SQL errors containing credentials.

- [ ] **Step 5: Run Cloud checks against isolated test setup**

```bash
cd /tmp/dngmeng-internal-beta-integration/cloud-api
go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
go build ./cmd/migrate
git diff --check
```

Run PostgreSQL migration and business integration cases only after confirming their DSN targets the dedicated Cloud test database at port `15432`, never `5432`.

- [ ] **Step 6: Commit Cloud lifecycle/migration work**

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- cloud-api migrations docker-compose.yml
git diff --cached --check
git commit -m "feat(cloud): add repeatable migration validation"
```

## Task 5: Fix Android session closure only if lifecycle tests prove a defect

**Files:**
- Test: `android/app/src/test/java/com/verba/interpretation/cloud/TranslationSessionCoordinatorTest.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/FaceToFaceCoordinatorTest.kt`
- Modify if required: `android/app/src/main/java/com/verba/interpretation/cloud/TranslationSessionCoordinator.kt`
- Modify if required: `android/app/src/main/java/com/verba/interpretation/ui/{InterpretationViewModel,FaceToFaceViewModel}.kt`

**Consumes:** Task 3 protocol and Task 4 session endpoint semantics.

**Produces:** one cloud session ends exactly once after the relevant active translation work is finished or cancelled; usage failure never blocks cleanup.

- [ ] **Step 1: Add failing lifecycle tests for known boundary cases**

Test these exact cases:
- `end(sessionId)` issues at most one usage request and at most one end request;
- usage failure still invokes end;
- manual face-to-face empty input closes the already-created Cloud grant;
- automatic face-to-face stop does not terminate cloud session before outstanding turn/TTS drain is complete;
- `cancel()` and ViewModel clear terminate a grant without leaving the microphone active.

- [ ] **Step 2: Run focused tests before any implementation change**

```bash
cd /tmp/dngmeng-internal-beta-integration/android
./gradlew --no-daemon testDebugUnitTest --tests '*TranslationSessionCoordinatorTest' --tests '*FaceToFaceCoordinatorTest' --tests '*TurnSessionCoordinatorTest'
```

Expected: any lifecycle defect is shown by a deterministic test. If all pass and code review confirms semantics, do not modify implementation.

- [ ] **Step 3: Implement the minimal state transition correction when required**

Keep `TranslationSessionCoordinator.end(sessionId)` as the single idempotence owner. Delay `endCloudSession()` only until the coordinator reports all started turns have reached terminal/drained state. For empty manual input, call the same idempotent close path. Do not retry usage forever or persist JWT/PCM.

- [ ] **Step 4: Run Android quality gate and build Debug APK**

```bash
cd /tmp/dngmeng-internal-beta-integration/android
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17 \
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk \
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Expected: successful test, lint and Debug build. Report APK path but never add it to Git.

- [ ] **Step 5: Commit only if source changed**

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- android/app/src/main android/app/src/test
git diff --cached --check
git diff --cached --quiet || git commit -m "fix(android): close cloud sessions after turn drain"
```

## Task 6: Align and verify Admin Web against the real Cloud API contract

**Files:**
- Modify: `cloud-api/admin-web/src/api/cloudApi.ts`
- Modify: `cloud-api/admin-web/src/api/cloudApi.test.ts`
- Modify: `cloud-api/admin-web/src/App.tsx`, `cloud-api/admin-web/src/App.test.tsx` only for evidenced RBAC/base URL behavior.
- Modify: `cloud-api/admin-web/API_EXPECTATIONS.md`, `cloud-api/API.md`.
- Test: Cloud API handler/router tests for `GET /api/v1/admin/users` and `GET /api/v1/admin/audit-logs`.

**Consumes:** Cloud API business handlers and final schema from Task 4.

**Produces:** Admin Web and Cloud API agree on `{ "users": [...] }` and `{ "audit_logs": [...] }`; backend enforces admin role; frontend only reflects it.

- [ ] **Step 1: Write failing Cloud API HTTP contract tests**

Add tests that prove:

```http
GET /api/v1/admin/users
GET /api/v1/admin/audit-logs
```

return `401` without/with invalid access token, `403` for a valid `role=user` access token, and `200` for `role=admin` with exactly:

```json
{"users":[]}
{"audit_logs":[]}
```

Require list items to have documented fields; ensure audit metadata returned to the browser is a safe object subset, not raw secret-bearing values.

- [ ] **Step 2: Run tests to expose contract mismatch**

```bash
cd /tmp/dngmeng-internal-beta-integration/cloud-api
go test ./internal/http -run 'Test.*Admin' -count=1
cd /tmp/dngmeng-internal-beta-integration/cloud-api/admin-web
npm test -- --run src/api/cloudApi.test.ts src/App.test.tsx
```

Expected: any remaining placeholder route, naked-array response, or frontend parser mismatch fails.

- [ ] **Step 3: Implement only the necessary contract alignment**

- Cloud API handler must use the existing real authorization/store service, not frontend-provided role.
- Admin Web parser accepts only object envelopes `users` and `audit_logs`; do not restore bare-array fallback.
- Keep one refresh retry on 401; clear `sessionStorage` after refresh failure or non-admin identity.
- Document the same envelopes in both API documents.
- For the internal beta environment, record the exact allowed CORS origin configuration outside Git; do not hard-code a credential or public endpoint into source.

- [ ] **Step 4: Run Admin and Cloud checks**

```bash
cd /tmp/dngmeng-internal-beta-integration/cloud-api
go test -count=1 ./...
go vet ./...
go build ./cmd/cloud-api
cd /tmp/dngmeng-internal-beta-integration/cloud-api/admin-web
npm test
npm run typecheck
npm run lint
npm run build
```

Expected: all pass; UI renders explicit unavailable/error states rather than fabricated rows.

- [ ] **Step 5: Commit Admin contract work**

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- cloud-api/internal cloud-api/admin-web cloud-api/API.md
git diff --cached --check
git commit -m "feat(admin): verify cloud management contracts"
```

## Task 7: Run controlled Cloud–Agent authorization E2E before real audio

**Files:**
- Create: `cloud-api/integration/agent_session_e2e_test.go` or a focused external test harness under `cloud-api/contracttest/`.
- Modify only if test evidence requires: Agent server/session-auth code or Cloud API token configuration test fixtures.
- Create: `docs/reviews/internal-beta-authorization-acceptance.md`.

**Consumes:** Tasks 3-6, controlled Cloud API and Agent configuration with values never printed.

**Produces:** evidence that Cloud-signed token, Android-equivalent subprotocol/start fields, and Agent verifier produce `ready`; rejected credentials cause zero Provider startup.

- [ ] **Step 1: Write E2E positive and negative cases using fake Provider**

The harness must:
1. issue a session grant through the Cloud API service/HTTP route;
2. open Agent WS with `translation.v1, translation.jwt.<token>`;
3. send valid Android-shaped `start` JSON;
4. assert exactly one `ready` and fake Provider start count `1`;
5. retry with expired, altered-signature, wrong-user, wrong-session and wrong-install cases;
6. assert policy close/error and fake Provider start count `0` for each rejection.

The test must redact token in failures and never include it in test snapshots.

- [ ] **Step 2: Run E2E test before implementation changes**

```bash
cd /tmp/dngmeng-internal-beta-integration/cloud-api
go test ./integration -run TestCloudAgentSessionAuthorization -count=1
```

Expected: failure identifies exact signer/verifier/HTTP/subprotocol mismatch, if any.

- [ ] **Step 3: Apply minimal contract fix, if the failing test proves one**

Fix only configuration wiring or identified JWT/header/start mismatch. Do not weaken Agent auth, extend token locations, or add provider fallback.

- [ ] **Step 4: Verify full local gates**

```bash
cd /tmp/dngmeng-internal-beta-integration
make web-test web-typecheck web-lint web-build agent-test agent-vet agent-build
cd cloud-api && go test -count=1 ./... && go vet ./... && go build ./cmd/cloud-api
cd ../android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
cd ../cloud-api/admin-web && npm test && npm run typecheck && npm run lint && npm run build
```

Expected: all applicable checks pass. If real Provider credentials or network are unavailable, record that only fake-Provider authorization E2E passed; do not claim translation success.

- [ ] **Step 5: Write acceptance evidence and commit it**

Document executed commands, commit SHAs, positive/negative authorization case outcomes, explicit external-dependency gaps, and a token-free log review in `docs/reviews/internal-beta-authorization-acceptance.md`.

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- cloud-api/integration docs/reviews/internal-beta-authorization-acceptance.md agent cloud-api
git diff --cached --check
git commit -m "test: verify cloud agent authorization flow"
```

## Task 8: User-assisted Android real-translation acceptance

**Files:**
- Create: `docs/reviews/internal-beta-android-device-checklist.md`
- Create after user test: `docs/reviews/internal-beta-android-device-acceptance.md`

**Consumes:** Task 7 full gates, Debug APK from Task 5, controlled local/internal-beta services.

**Produces:** user-facing two-minute validation and an evidence record without personal credentials or transcript content.

- [ ] **Step 1: Prepare token-safe one-page device checklist**

Checklist must instruct user to:
1. install the generated Debug APK;
2. allow microphone permission;
3. register/login a normal user;
4. confirm trial/entitlement state;
5. open single interpretation;
6. say `你好，今天天气很好。`;
7. confirm English subtitle or audio;
8. tap finish;
9. report only success/failure, screenshot and visible error text.

It must explicitly say not to send password, JWT, API key, environment file, database dump, raw logs or conversation recording.

- [ ] **Step 2: Check controlled environment before asking user**

Without printing secrets, verify service health:

```bash
curl -fsS http://127.0.0.1:18765/api/health
curl -fsS http://127.0.0.1:8080/api/v1/ready
```

Verify the installed Debug endpoint is not stale `127.0.0.1` on a physical device. Record that current debug traffic is HTTP/WS and only permitted for the approved, controlled internal test network.

- [ ] **Step 3: Wait for user device result**

Do not infer real translation from unit tests. If the user reports failure, collect only the UI error/screenshot and follow systematic debugging before changing code.

- [ ] **Step 4: Read-only post-session database/API verification**

Using a credential-safe operator procedure, confirm:

```text
session exists and is ended/revoked;
usage_records contains exactly one aggregate row for the session;
no PCM, source/translation body, JWT or password has been stored.
```

Do not paste database rows with user email, token, or sensitive metadata into docs or chat.

- [ ] **Step 5: Record result and commit the token-free acceptance report**

State passed/failed/not-run truthfully, include no secrets or transcript content.

```bash
cd /tmp/dngmeng-internal-beta-integration
git add -- docs/reviews/internal-beta-android-device-*.md
git diff --cached --check
git commit -m "docs: record android internal beta acceptance"
```

## Task 9: Final integration gate and handoff

**Files:**
- Create: `docs/reviews/internal-beta-release-gate.md`
- Modify: `handoff.md` only to correct verified facts, 80ms PCM wording, source locations, build commands and known limitations.

**Consumes:** Tasks 1-8.

**Produces:** a clean integration commit series with explicit internal-beta limits; no production-ready claim.

- [ ] **Step 1: Run final source and artifact exclusion checks**

```bash
cd /tmp/dngmeng-internal-beta-integration
git status --short
git diff --check
git ls-files | grep -E '(^|/)(\.env|node_modules|\.gradle|app/build|local\.properties|local-reset-admin|cloud-api/cloud-api|.*\.apk$)' && exit 1 || true
```

Expected: no tracked forbidden artifacts. Resolve unexpected entries before proceeding.

- [ ] **Step 2: Re-run all applicable verification commands**

```bash
make web-test web-typecheck web-lint web-build agent-test agent-vet agent-build
(cd cloud-api && go test -count=1 ./... && go vet ./... && go build ./cmd/cloud-api)
(cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug)
(cd cloud-api/admin-web && npm test && npm run typecheck && npm run lint && npm run build)
```

Expected: fresh exit code 0 for every applicable check. Record unavailable external-provider or device checks as unavailable, never as passed.

- [ ] **Step 3: Create release-gate report**

The report must list:
- integrated commit range and source commits;
- each command and result;
- JWT positive/negative E2E result;
- Android device result or explicit pending state;
- Admin RBAC result;
- remaining production blockers: HTTPS/WSS, Secret Manager, migration deployment process, managed database, monitoring/backup, feedback object storage/deletion, Release signing/distribution.

- [ ] **Step 4: Inspect staged content and commit final gate documents**

```bash
git add -- docs/reviews/internal-beta-release-gate.md handoff.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: record internal beta release gate"
git status --short --branch
```

Expected: only reviewed source/docs commits exist; root untracked legacy directories remain untouched and untracked.
