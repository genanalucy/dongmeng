> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-2-brief.md`；原始 SHA-256 `1948f0030eba2bed5a5cc4e7083c211c7f99197279b99372fed30abec3d67843`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

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
cd [旧临时路径]
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
