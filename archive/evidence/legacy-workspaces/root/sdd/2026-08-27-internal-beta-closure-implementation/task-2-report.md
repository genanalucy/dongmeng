> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-2-report.md`；原始 SHA-256 `13f36e4a7535091c23b1fd38dbc005b1c5a6be8666e4d25c8e38c683e4a4ef3c`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 2：选择性整合核验报告

## 范围与基线

- 执行工作树：`[旧临时路径]`
- BASE：`1c6a2e05200f335c0a1a6f158f85440da4370503`
- 来源清单：`docs/reviews/internal-beta-source-manifest.md`
- 已提交的唯一 Task 2 变更：`3e0b93f67b97d51f9820f94cd031da8da1d424c0`（`chore: ignore Android local build artifacts`）
- 该 commit **仅**包含根 `.gitignore` 的 Android 本地/构建产物忽略规则；不包含任何组件源代码整合快照。

## 选择性来源与路径限制

| 组件 | 固定来源 | 仅允许读取/整合路径 | 结果 |
| --- | --- | --- | --- |
| Android | `android-cloud-auth@35445f18f743752310e595df6378d433ec7c7fd1` | `.github/workflows/android-ci.yml`、`android/**`、必需 `branding/**` 资源 | 已选择这些路径；内容逐文件与该来源一致。 |
| Cloud API business | `cloud-api-business@421498fe08ae470757f0e2746b09c57efe654b98` | `cloud-api/**`、`migrations/**`、`docker-compose.yml` | 已选择这些路径；内容逐文件与该来源一致。明确未选择 `cloud-api/cloud-api`、`cloud-api/cmd/local-reset-admin/`。 |
| Admin Web | `cloud-admin-live@4f07956d2560b44bb6fa92c9486c7cc9a319aa7f` | **仅** `cloud-api/admin-web/**` | 已选择的 Admin 文件逐文件与该来源一致。未从该分支读取/覆盖 `cloud-api/internal/**`、`cloud-api/cmd/**`、migrations、根 Agent、Makefile 或 README。 |

真实 Cloud API 后端（`cloud-api/internal/**`、`cloud-api/cmd/cloud-api/main.go`、`cloud-api/go.mod`、migrations）只来自 `cloud-api-business`；`cloud-admin-live` 仅提供 Admin Web 前端。

## 根文件与 Agent 裁决

检查了 `git diff main...cloud-api-business -- Makefile README.md agent`，但没有引入任何根 `Makefile`、`README.md` 或 Agent 差异。

理由：当前 BASE 的 `agent/internal/sessionauth` 已接受 Cloud API business 所签发的 `typ=translation_session`、`user_id`、`session_id`、`install_id` 合约。business 分支候选的 `agent/sessionauth` 改为 `typ=JWT`、`entitlement_id` 的不同合约，且会替换当前兼容实现。因此不满足“为直接构建/协议缺失所必需”的例外条件，保留 BASE 的根 Agent 行为。Cloud API 有独立 `cloud-api/go.mod` 与 `cloud-api/cmd/cloud-api/main.go`，不依赖根 Makefile 目标即可直接构建。

## 根 ignore 策略

commit `3e0b93f67b97d51f9820f94cd031da8da1d424c0` 为根 `.gitignore` 增加：

```gitignore
android/.gradle/
android/.kotlin/
android/app/build/
android/local.properties
*.apk
```

执行 `git check-ignore -v` 的结果：`android/.gradle/`、`android/.kotlin/`、`android/app/build/`、`android/local.properties` 和 APK 均被忽略；`android/app/src/**`、`android/gradlew`、`android/build.gradle.kts` 不被忽略。

禁止路径扫描中的 `android/app/build.gradle.kts` 是合法且应跟踪的 Gradle 构建脚本；它不匹配、也不等同于被禁止的生成目录 `android/app/build/`。

## 实际检查与结果

| 命令/检查 | 结果 |
| --- | --- |
| `git diff --name-status $(git merge-base main android-cloud-auth) android-cloud-auth -- android branding .github` | 确认 Android 候选仅为 Android、branding 和 Android CI workflow 路径。 |
| `git diff --name-status $(git merge-base main cloud-api-business) cloud-api-business -- cloud-api migrations docker-compose.yml Makefile README.md agent` | 确认 Cloud API business 候选及根/Agent 差异；后者经兼容性裁决未引入。 |
| `git diff --name-status $(git merge-base main cloud-admin-live) cloud-admin-live -- cloud-api/admin-web` | 候选只含 `cloud-api/admin-web/**`。 |
| `git diff --check` | 通过，无空白错误。 |
| `git diff --stat`（对 intent-to-add 的组件源变更） | `146 files changed, 18490 insertions(+)`。 |
| `git diff --name-only -- cloud-api/admin-web` | 仅列出 Admin Web 路径。 |
| `git diff --name-only -- cloud-api/internal cloud-api/cmd cloud-api/go.mod migrations` | 列出 Cloud API business 后端和 migration 路径；未使用 Admin 分支 placeholder 后端。 |
| 逐文件 object hash 与来源 ref 比较 | Android/branding/workflow 均匹配 Android ref；Admin Web 均匹配 Admin ref；Cloud API business 路径均匹配 business ref。 |
| `git diff --name-only | grep -E '(^|/)(\\.env[^/]*|node_modules|\\.gradle|\\.kotlin|app/build|local\\.properties|local-reset-admin|cloud-api/cloud-api|[^/]+\\.apk)$'` | 无匹配。扫描不将 `android/app/build.gradle.kts` 误判为 `android/app/build/`。 |
| `git check-ignore -v android/.gradle/ android/.kotlin/ android/app/build/ android/local.properties sample.apk` | 均显示忽略规则；跟踪 Android 源码、wrapper 与构建脚本的反向检查通过。 |

## 实际 diff 名称清单摘要

`git diff --name-only`（通过 intent-to-add 使未跟踪选择性文件可见）共 146 个文件，范围为：

- `.github/workflows/android-ci.yml`
- `android/**`
- `cloud-api/**`，其中 Admin 部分为 `cloud-api/admin-web/**`
- `migrations/000001_init.{up,down}.sql`
- `migrations/000002_refresh_tokens_expiry_idx.{up,down}.sql`
- `migrations/000003_business_lifecycle.{up,down}.sql`
- `docker-compose.yml`

不包含根 `Makefile`、`README.md`、`agent/**`、`.env*`、Node/Gradle 缓存、APK、`cloud-api/cloud-api` 或 `cloud-api/cmd/local-reset-admin/**`。

## 提交与未提交状态

- **已提交**：仅根 `.gitignore` 规则，commit `3e0b93f67b97d51f9820f94cd031da8da1d424c0`。
- **故意未提交**：上述 146 个组件源文件、共 18,490 行新增。它们保留在 integration 工作树，等待 Task 3–6 的组件验证；遵循计划，不创建未经验证的组件源代码整合快照。
- 本报告未记录、读取或包含任何 secret。

## Fix round 1：PostgreSQL 宿主端口

仅修改了 integration 工作树中已导入且未提交的 `docker-compose.yml`：PostgreSQL 的宿主机映射从 `127.0.0.1:5432:5432` 精确改为 `127.0.0.1:15432:5432`，以避免占用受全局约束禁止的宿主机 `5432`。文件内不存在仅描述实际映射的 `localhost:5432` 或 `127.0.0.1:5432` 文本，无需同步文案。

保留容器内部连接不变：`DATABASE_URL` 仍为 `[历史开发连接已脱敏]`；未修改代码 fixture 或任何无关端口。

本轮实际验证：

| 命令/检查 | 结果 |
| --- | --- |
| `docker compose config --quiet` | 通过（Docker 在环境中可用）。 |
| `git diff --check` | 通过，无空白错误。 |
| 精确检查 `grep -Fq '127.0.0.1:5432:5432' docker-compose.yml` | 无匹配。 |
| 精确检查 `grep -Fx '      - "127.0.0.1:15432:5432"' docker-compose.yml` | 匹配，所需宿主机映射存在。 |
| `grep -nE 'localhost:5432|127\\.0\\.0\\.1:5432' docker-compose.yml` | 无陈旧宿主机 `5432` 描述。 |
| 精确检查容器内部 `DATABASE_URL` | 仍使用 `postgres:5432`，确认未修改内部连接。 |

该端口修复属于未验证组件源快照的一部分，遵循 Task 2 规则保持未提交；没有创建新 commit 或修改暂存区。
