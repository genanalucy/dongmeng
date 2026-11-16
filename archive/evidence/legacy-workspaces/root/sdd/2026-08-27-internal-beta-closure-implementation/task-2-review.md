> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-2-review.md`；原始 SHA-256 `7df0f1e11d409915c65cfa58df278f49a92908e3a48deed02b04f41cd6c4b8bc`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 2 独立审查报告

## 结论

**结论：CHANGES REQUIRED。** 路径限定导入、来源归属、Agent 兼容性裁决、Android ignore 策略和未提交策略均基本正确；但 `docker-compose.yml:9` 仍将 Cloud PostgreSQL 发布到 `127.0.0.1:5432`，直接违反计划 Global Constraints。该问题修复前，不应把 Task 2 判为通过，也不应无条件进入 Task 3。

Task 3 的协议测试本身不需要 PostgreSQL；若严格保证不执行 `docker compose`、不连接数据库，则当前源码可用于只读/组件级协议检查。但从任务交接安全性看，遗留一个可直接触发禁用端口的运行配置不满足“可安全进入下一任务”的门槛。

## 审查基线与方法

审查对象：`[旧临时路径]`。

独立核验结果：

- 当前 HEAD 为 `3e0b93f67b97d51f9820f94cd031da8da1d424c0`；该 commit 只修改根 `.gitignore`。
- 固定来源 ref 与 manifest 一致：
  - Android：`35445f18f743752310e595df6378d433ec7c7fd1`
  - Cloud API business：`421498fe08ae470757f0e2746b09c57efe654b98`
  - Admin Web：`4f07956d2560b44bb6fa92c9486c7cc9a319aa7f`
- `git diff --name-only` 可见 146 个 intent-to-add 源文件；`git diff --cached --name-only` 为 0，证明这些组件源代码未进入已提交快照或普通 staged diff。
- `git diff --check` 与 `git diff --cached --check` 均通过。
- 因本次审查被限定为只读，未运行会生成构建缓存或产物的组件测试/构建。

## Spec compliance

### Critical

1. **Cloud PostgreSQL 主机端口违反全局安全约束。**
   - `docker-compose.yml:9` 当前为：`127.0.0.1:5432:5432`。
   - 计划明确规定 Cloud PostgreSQL 仅允许使用 `127.0.0.1:15432`，并禁止影响 `127.0.0.1:5432`。
   - 该文件虽逐文件匹配 `cloud-api-business` 来源，但“来源正确”不能覆盖 Global Constraints；若执行该 Compose 配置，会尝试占用或使用明确禁止的主机端口。
   - **要求：** 在任何 Compose/数据库操作前，将主机映射改为 `127.0.0.1:15432:5432`，并重新核对相关运行文档和实际测试 DSN。`cloud-api/internal/config/config_test.go` 中的 `5432` 字符串目前只是配置解析 fixture，不会自行连接数据库，不等同于实际运行端口，但后续数据库测试必须显式使用 `15432`。

### Important

无其他 Important 级别的规范偏差。

### Minor

1. **计划中的字面 forbidden grep 示例仍有误报风险，但 Task 2 实际审查规则已正确收紧。**
   - 计划示例中的裸替代项 `app/build` 缺少路径结尾边界，直接照抄可能命中合法文件 `android/app/build.gradle.kts`。
   - 实施报告使用了带 `(/|$)`/精确名称语义的扫描；独立检查确认 `android/app/build.gradle.kts` 可跟踪且未被忽略，当前 review package 也未误报。
   - 后续 Task 9 不应机械复用无边界的示例表达式，应继续使用精确目录/文件规则。

## Task quality

### Critical

无独立于上述全局端口问题的 Critical 质量缺陷。

### Important

无 Important 质量缺陷。

### Minor

1. **进入 Task 3 的交接说明应显式携带端口禁令。** 当前实施报告称未提交范围等待 Task 3–6 验证，但未把已导入 Compose 的 `5432` 映射标成 blocker。即使 Task 3 的规定命令不启动数据库，也应在交接中明确“修复前不得运行 Compose/Cloud PostgreSQL”。

## 核查明细

### 1. 路径限定与来源归属

通过：

- Android 工作树内容与 `android-cloud-auth` 的 `android/**` 完全一致；`.github/workflows/android-ci.yml` 也完全一致。
- `branding/**` 在当前基线已存在，且对象内容与 Android 来源一致，因此没有重复形成未提交 branding diff，不属于遗漏。
- `migrations/**` 与 `docker-compose.yml` 来自 `cloud-api-business`。
- 除 `cloud-api/admin-web/**` 外的 Cloud API 路径与 `cloud-api-business` 完全一致。
- `cloud-api/admin-web/**` 与 `cloud-admin-live` 完全一致。
- 当前 146 个未提交路径全部位于允许范围：`.github/workflows/android-ci.yml`、`android/**`、`cloud-api/**`、`migrations/**`、`docker-compose.yml`。

### 2. Admin Web 与真实后端

通过：

- Admin 来源只覆盖 `cloud-api/admin-web/**`。
- `cloud-api/internal/**`、`cloud-api/cmd/cloud-api/main.go`、`cloud-api/go.mod`、`cloud-api/go.sum`、contract/integration tests、migrations 和 `cloud-api/API.md` 均与 `cloud-api-business` 一致。
- 未发现从 `cloud-admin-live` 引入 placeholder 后端、其 migrations、根 Agent、Makefile 或 README 的证据。

### 3. 根文件与 Agent 变更裁决

通过，且“不引入 business 分支 Agent”是正确选择：

- 当前基线 Agent 已包含 `agent/internal/sessionauth`、配置加载、server 注入和 `translation.v1` / `translation.jwt.<JWT>` subprotocol 处理。
- 当前 verifier 接受 `typ=translation_session`，并绑定 `sub/user_id/session_id/install_id`；这与当前 Cloud signer 的关键 wire contract 一致。
- `cloud-api-business` 候选 Agent 使用另一套 `agent/sessionauth` 包，要求 `typ=JWT` 并以 `entitlement_id` 取代 `install_id` 绑定；直接导入会破坏当前 Cloud–Agent–Android 合约，属于不兼容变更。
- 根 `Makefile` 候选主要添加便捷的 Cloud targets，根 `README.md` 候选主要添加运行说明；Cloud API 自有 `go.mod` 和 `cmd/cloud-api/main.go`，不存在因未导入这些根变更而无法直接构建的证据。
- Task 3 仍应按计划用失败测试锁定 `scope=translation`、`entitlement_id` 及完整 claims 约束；这属于 Task 3 的契约验证，而不是引入已知不兼容 business Agent 的理由。

### 4. `.gitignore` 与禁止文件规则

通过：

- 根 `.gitignore` 包含：
  - `android/.gradle/`
  - `android/.kotlin/`
  - `android/app/build/`
  - `android/local.properties`
  - `*.apk`
- 正向检查确认 `.gradle`、`.kotlin`、`app/build/`、`local.properties` 和 APK 被忽略。
- 反向检查确认以下路径仍可跟踪：
  - `android/app/src/main/AndroidManifest.xml`
  - `android/gradlew`
  - `android/build.gradle.kts`
  - `android/app/build.gradle.kts`
  - `android/gradle/wrapper/gradle-wrapper.jar`
- 对当前未提交路径执行精确目录/名称扫描，无 `.env*`、`node_modules`、Gradle/Kotlin cache、`app/build/`、`local.properties`、APK、`cloud-api/cloud-api` 或 `cloud-api/cmd/local-reset-admin/`。

### 5. 未提交策略与进入 Task 3

部分通过：

- 组件源码保持未提交，符合“Tasks 3–6 组件验证前不提交未经验证的整合快照”。
- 当前仅提交根 ignore 安全规则，未提交 146 个组件源文件；这与实施报告一致。
- intent-to-add 使普通 `git diff` 可审查全部新增文件，同时 cached diff 仍为空；后续必须继续使用路径限定 `git add -- <paths>`，不得使用 `git add .`。
- 除 `docker-compose.yml` 的禁用端口外，未提交范围没有来源污染、禁止文件或 Admin/backend 覆盖问题，可承接 Task 3。
- **最终门槛：** 先修复/隔离 `docker-compose.yml:9` 的主机端口风险，再将 Task 2 标为通过并进入下一任务；至少在修复前严禁执行 Compose 或任何指向 `127.0.0.1:5432` 的 Cloud 数据库操作。
