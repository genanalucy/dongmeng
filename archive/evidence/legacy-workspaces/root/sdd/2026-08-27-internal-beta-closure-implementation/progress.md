> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/progress.md`；原始 SHA-256 `2fb2330de2b552cb128e2a7fa9f1d989a127eb8d7a386bf576ebf28a7c29eb65`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# SDD ledger — plan: [旧工作区路径]

## Workspace

- Integration worktree: `[旧临时路径]`
- Integration branch: `internal-beta-integration`
- Merge base: `44e042b4d6f131f169b245e32172fc27f6674737`
- Design spec: `[旧工作区路径]`

## Preflight dependency and consistency scan

| Tasks / shared boundary | Producer → consumer | Finding / ruling |
|---|---|---|
| 1 → 2 | Source manifest and clean integration worktree → path-limited component imports | Consistent. Task 2 must use only refs recorded by Task 1. |
| 2 → 3 | Integrated Android, Cloud API signer and Agent verifier → protocol tests | Consistent. Android/Cloud/Agent protocol paths are all introduced before Task 3. |
| 2 → 4 | Cloud API source and migrations → migration runner | Consistent. Task 4 creates only new migration execution code; existing migrations remain immutable. |
| 3 → 5 | Stable JWT/framing contract → Android lifecycle tests | Consistent. Task 5 does not alter protocol. |
| 4 → 5 | End/revoke/usage API semantics → Android closure | Consistent. Android fix is conditional on deterministic test evidence. |
| 4 → 6 | Real Cloud business handlers → Admin contract | Consistent. Admin frontend is selected from Admin branch; backend remains Cloud API business branch. |
| 3, 4, 6 → 7 | Stable auth contract, session handlers, Admin independent → authorization E2E | Consistent. Fake provider prevents reliance on secrets/real provider. |
| 5, 7 → 8 | Debug APK and authorization evidence → user device test | Consistent. User test cannot start before authorization gate. |
| 1-8 → 9 | Commits and verification evidence → release gate | Consistent. Task 9 must report unavailable external checks truthfully. |
| Task 2 Step 4 | Admin import must not overwrite Cloud API business backend | Ruling: import only `cloud-api/admin-web` from `cloud-admin-live`; preserve business backend introduced in Task 2 Step 3. Cost if wrong: placeholder routes could replace real API and block all auth/RBAC. |
| Task 4 vs Global Constraints | Migration validation targets Cloud DB only | Ruling: test runner requires an explicit dedicated DSN and never defaults to port 5432. Cost if wrong: potential modification of unrelated PostgreSQL instance. |
| Task 8 vs Global Constraints | Debug public HTTP/WS transport | Ruling: allow only user-approved, short-lived controlled internal test after previous gates; record it as a non-production risk. Cost if wrong: session credentials can be exposed in transit. |

## Preflight result

No unresolved plan/spec contradictions. Task 1 is the first incomplete task.

Task 1: fix round 1/5 (2 addressed, 0 open — source manifest accurately distinguishes selected base from current main and records full refs/worktree reuse; commits 97374a9..1c6a2e0)
Task 1: complete (commits 44e042b..1c6a2e0, review clean)

Task 2: Ruling: replace Cloud Compose host mapping `127.0.0.1:5432:5432` with `127.0.0.1:15432:5432` — design Global Constraints prohibit use of host 5432; source fidelity yields to approved safety boundary. Cost if wrong: old local startup documentation/users expecting port 5432 must update, but unrelated database remains protected.
Task 2: fix round 1/5 started — Compose host-port constraint violation.

Task 2: fix round 1/5 (1 addressed, 0 open — Cloud Compose host port now 15432; commits 3e0b93f..3e0b93f plus uncommitted verified source integration)
Task 2: complete (commits 1c6a2e0..3e0b93f, review clean; 146 selected component files intentionally remain uncommitted for Tasks 3–6 verification)

Task 3: Ruling: move the selected integration-source baseline into a reviewed commit before accepting Task 3 — the plan's “do not commit unverified integration snapshot” protects against unreviewed code, but a source-only, exact-ref baseline is now required for clean-checkout protocol verification. Cost if wrong: Cloud/API or Android non-protocol code is committed before its later behavioral gates, but it remains traceable, tested incrementally, and avoids non-reproducible protocol commits.
Task 3: Ruling: direct Cloud signer → Agent server fake-provider interoperability remains Task 7's explicit E2E deliverable — Go nested-module `internal` boundaries prevent a small Task 3 import without widening APIs; Task 3 must still lock exact wire fields and Agent fail-closed server cases. Cost if wrong: signer/verifier drift is only caught at Task 7, not Task 3.
Task 3: fix round 1/5 started — self-contained commit boundary and missing Agent server fail-closed cases.

Task 3: fix round 1/5 (5 addressed, 0 open — reproducible integration baseline and complete Agent server fail-closed coverage; commits bc5d50c..9417753)
Task 3: complete (commits 3e0b93f..9417753, review clean)

Task 4: Ruling: migration execution is fail-closed by target class — host CLI accepts only `127.0.0.1:15432`; Compose receives a narrow exact `postgres:5432` service-network exception; every other target rejects before connecting. Cost if wrong: legitimate nonstandard development targets require explicit future policy, but the unrelated host 5432 database cannot be mutated.
Task 4: Ruling: a concurrent-index migration is recorded only after an exact, valid index state is verified under a global advisory lock; an invalid/ambiguous index fails closed with no ledger write. Cost if wrong: operator intervention may be required after a failed concurrent build, but schema drift cannot be silently marked complete.
Task 4: Ruling: an existing Cloud schema with an empty migration ledger fails closed and is documented as requiring an explicit recovery/adoption procedure; runner never replays initial migrations over it. Cost if wrong: legacy dev volumes do not auto-start until safely handled, but existing data/schema is not overwritten.
Task 4: fix round 1/5 started — consolidated migration runner Critical/Important review findings.

Task 4: Ruling: every PostgreSQL integration/lifecycle helper must reuse the runner's exact target validation before opening a pool; no helper may implement a weaker host/port check. Cost if wrong: test-only code can mutate the unrelated host 5432 database despite runner safety.
Task 4: Ruling: concurrent migration ledgering requires catalog verification of the expected parsed index relation/definition, not only a valid same-name index; mismatches fail closed. Cost if wrong: stricter parser/catalog matching can reject unusual but valid future index DDL until explicitly supported, but silent schema drift cannot be recorded.
Task 4: fix round 2/5 started — remaining Critical/Important findings from migration safety rereview.

Task 4: COMPLETE — final review `task-4-final-review.md` reports Critical=0 and Important=0. Disposable PostgreSQL 17 E2E passed on an ephemeral database bound only to `127.0.0.1:15432`; the container was removed after verification. Evidence: `[旧临时路径]` shows the real lifecycle suite, repository migrations 000001→000003, checksum/idempotence, advisory-lock serialization, legacy empty-ledger fail-closed, invalid/wrong-definition concurrent-index recovery, NULLS NOT DISTINCT mismatch, full Go tests, vet, and builds passing. Host `127.0.0.1:5432` was not used or modified.
Task 4: final implementation commits after source import: `f03e9e7`, `82c10ad`, `2b33e77`, `48e8aea`, `b1aa16d`, `4016483`, `a9af5c6`, `17a05ad`, `ae6e3b1`, `00ebf68`.

Task 5: started — Android translation/session lifecycle verification; first action is read-only design/code review to prove whether a defect exists before changing Android code.

Task 5: rereview-1 rejected implementation. Ruling: synchronous Cloud usage/end calls must execute on an explicit IO dispatcher, never ViewModel Main; coordinator owns a bounded retry policy after end transport failure because ViewModels clear grants and cannot be the retry owner. Retry preserves usage-at-most-once and one end request at a time; state is removed only after remote end success. Cost: bounded background work may briefly outlive the UI owner, but prevents permanent active session leakage from transient failure.
Task 5: fix round 1/3 started — dispatch/threading and production-reachable end retry.

Task 5: COMPLETE — final review `task-5-final-review.md` approved code with Critical=0 and Important=0. `da87c23`, `a1874c1`, and `2ccf115` close auto-stop drain, manual-empty grant closure, late-grant lifecycle races, explicit IO transport, bounded end retry, exactly-once usage/end properties, and production terminal-failure observation. Android unit tests/lint/Debug build passed with explicit JDK17/SDK. Real device/audio/network/server-state/token dynamic checks remain deferred to Task 8.
Task 6: started — Admin Web minimum RBAC and real Cloud API users/audit envelope verification.

Task 6: COMPLETE — final review `task-6-final-review.md` approved with Critical=0 and Important=0. Commits `6b30078` and `0996056` close logout authorization, audit metadata projection, HTTP RBAC/envelope/query contracts, Admin Web query/pagination/search, documentation alignment, and stale response isolation. Go and Admin Web quality gates passed; live browser/API verification remains deferred to later controlled validation.
Task 7: started — Cloud-signed translation session JWT → Agent WebSocket → fake Provider positive/negative interoperability E2E.

Task 7: COMPLETE — final review `task-7-final-review.md` approved. Real Cloud register/login/session route → Agent test-only real WebSocket → fake Provider E2E passed on disposable PostgreSQL 17 bound only to 127.0.0.1:15432; positive asserts exactly one ready / Provider Start=1; complete negative authorization and prohibited credential transport matrix asserts Start=0. Helper/listener/fixture cleanup and token-safe output reviewed. Commits `fb3f6dd`, `7d837a9`. Real Provider/audio/device are deferred to Task 8.
Task 8: started — Debug APK generation and token-safe device acceptance checklist; user device action will be required for actual translation.

Task 8: BLOCKED — device reported Android text mapped exclusively from Cloud error `unauthorized`. Read-only unauthenticated checks on the Debug Cloud target `http://[旧环境地址已脱敏]:8080` found `/api/v1/health`=200 but `/api/v1/ready`=503; Agent `/api/health`=200. Source confirms Cloud readiness returns 503 only when its database dependency is absent or its database ping fails. Do not retry device login until the approved Cloud operator restores readiness=200; no credentials/DSN/logs were read or emitted, and 127.0.0.1:5432 was untouched.
