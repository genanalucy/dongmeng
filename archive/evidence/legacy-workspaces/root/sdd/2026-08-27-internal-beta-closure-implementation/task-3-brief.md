> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-3-brief.md`；原始 SHA-256 `71287285c011c3409ee6ba5c31926df1dbc642da018074a707c0337e89232035`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

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
cd [旧临时路径]
go test ./internal/sessionauth ./internal/server -run 'Test.*(Cloud|Session|Origin|Token)' -count=1
cd [旧临时路径]
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
cd [旧临时路径]
go test ./... && go vet ./... && go build ./cmd/translator-agent
cd [旧临时路径]
go test -count=1 ./...
cd [旧临时路径]
./gradlew --no-daemon testDebugUnitTest --tests '*PcmPacketizerTest' --tests '*CloudAgentHandshakeTest' --tests '*AgentProtocolTest'
```

Expected: all pass. If Android and Agent packet size differs, stop integration and resolve the protocol rather than accepting both formats implicitly.

- [ ] **Step 6: Commit protocol changes**

```bash
cd [旧临时路径]
git add -- agent cloud-api/contracttest android handoff.md android/README.md cloud-api/API.md
git diff --cached --check
git commit -m "test: lock translation session protocol contract"
```
