# 言枢智能（VerbalInterpretation）项目交接说明

> 最后核对：2026-08-26
>
> 本文记录的是当前本机开发环境和 Git 工作树的实际状态。**不包含密码、JWT、API Key、数据库连接串或其他机密值。** 运行时机密只存在于 Git 忽略且权限受限的本机文件中。

---

## 1. 产品目标

**产品名：** 言枢智能（短名：言枢）
**宣传语：** 实时同传，智联世界

产品提供实时语音同传，主要使用场景：

- 单人同传：用户持续说话，获得实时源语字幕、翻译字幕和翻译语音；
- 面对面同传：双语双方轮流说话，可使用手动 PTT 或自动连续模式；
- 支持语言：`zh`（中文）、`en`（英语）、`fr`（法语）、`vi`（越南语）；
- Web 与原生 Android 均使用同一套 Translator Agent 数据平面协议；
- Cloud API 提供账户、试用、权益、兑换、翻译会话授权、用量与管理能力。

产品边界：客户端不持有翻译供应商密钥；供应商协议与密钥仅在本地/服务器端 Translator Agent 中处理。

---

## 2. 总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│                       客户端                                  │
│  Web（React/Vite）             Android（Kotlin/Compose）      │
│  - 采集 PCM                    - 采集 PCM                     │
│  - 显示字幕/播放 PCM           - 显示字幕/播放 PCM             │
│  - 账户/权益（Android 已接入）  - Cloud API 登录/会话授权       │
└───────────────┬───────────────────────────────┬─────────────┘
                │ WebSocket / PCM                │ HTTPS REST
                ▼                                ▼
┌────────────────────────────┐    ┌───────────────────────────┐
│ Translator Agent（Go）      │    │ Cloud API（Go + PostgreSQL）│
│ 翻译数据平面               │    │ 控制平面                    │
│ - Provider 路由             │    │ - 账户 / RBAC               │
│ - 字幕 / TTS PCM            │    │ - 试用 / 权益 / 兑换码       │
│ - Session JWT 校验          │    │ - 翻译会话 JWT 签发          │
└──────────────┬─────────────┘    │ - 用量 / 审计 / 反馈元数据   │
               │                  └──────────────┬────────────┘
               ▼                                 ▼
┌────────────────────────────┐    ┌───────────────────────────┐
│ Volcengine AST              │    │ PostgreSQL                 │
│ 中文 ↔ 英语                 │    │ 账户、权益、会话、用量等     │
└────────────────────────────┘    └───────────────────────────┘
┌────────────────────────────┐
│ Qwen LiveTranslate          │
│ 含法语/越南语的语言组合      │
└────────────────────────────┘
```

### 数据平面与控制平面

| 部分 | 职责 | 禁止承担的职责 |
|---|---|---|
| Translator Agent | 音频流、翻译 Provider 路由、字幕、TTS、WebSocket Session Token 校验 | 账号体系、权益长期存储、客户端暴露 Provider Key |
| Cloud API | 身份认证、RBAC、权益、兑换、翻译会话授权、用量、管理审计 | 直接长期保存流式 PCM |
| PostgreSQL | 元数据与业务状态 | 普通翻译过程中产生的原始 PCM |
| 私有对象存储（未来） | 用户明确同意后的短期反馈音频 | 默认留存所有语音 |

---

## 3. 翻译 Provider 与稳定客户端协议

### Provider 路由

```text
zh ↔ en                    → Volcengine AST
任意包含 fr 或 vi 的组合    → Qwen qwen3.5-livetranslate-flash-realtime
```

不得静默回退到 Mock 或其他供应商。真实供应商故障应向客户端返回准确错误。

### 客户端与 Agent WebSocket 协议

客户端只依赖稳定产品协议，不了解 Volcengine/Qwen 的细节：

```text
客户端 → Agent
- 文本 start JSON
- PCM16 二进制音频包（16 kHz，80 ms，2560 bytes）
- finish JSON

Agent → 客户端
- ready
- source_partial / source_final
- translation_partial / translation_final
- PCM16 TTS 二进制包
- finished
- error
```

普通 start 的核心字段：

```json
{
  "type": "start",
  "sessionId": "uuid",
  "mode": "s2s",
  "sourceLanguage": "zh",
  "targetLanguage": "en",
  "targetAudioFormat": "pcm",
  "targetAudioRate": 16000
}
```

启用 Cloud API 翻译会话授权时还必须携带：

```json
{
  "userId": "cloud-user-id",
  "installId": "stable-installation-id"
}
```

JWT 必须以 WebSocket subprotocol 方式传递，不能放 query string、Cookie 或 Authorization header：

```text
translation.v1, translation.jwt.<compact-JWT>
```

Agent 只协商 `translation.v1`，不会回显 JWT。

---

## 4. Web 应用

### 技术与目录

```text
web/
├── src/app/                  # 应用状态与入口
├── src/pages/                # 单人、面对面、连接测试页面
├── src/audio/                # 麦克风、PCM capture、立体声 TTS
├── src/solo/                 # 单人同传控制器
├── src/face/                 # 面对面控制器
├── src/translation/          # Agent 健康检查、WebSocket Client、端点配置
└── src/components/           # UI 组件
```

### 已完成能力

- 单人同传；
- 面对面 PTT 与自动/连续交替翻译；
- 浏览器麦克风 PCM 捕获；
- 实时字幕与顺序化 TTS 播放；
- 立体声测试与固定双耳输出；
- 四语言选择；
- 连接/端点配置和运行状态；
- Agent 健康探测；
- 统一的言枢视觉设计。

### Web 开发运行

当前本机服务：

```text
Translation Web: http://10.10.10.7:5173/
```

Vite 代理：

```text
/api → http://127.0.0.1:18765
/ws  → ws://127.0.0.1:18765
```

因此 Agent 未直接暴露到 LAN。

---

## 5. Android 原生应用

### 技术与目录

```text
android/
├── app/src/main/java/com/verba/interpretation/
│   ├── audio/                # MicrophoneCapture、TTS
│   ├── protocol/             # AgentSocket、CloudAgentHandshake
│   ├── cloud/                # Cloud API、Token、安装 ID、会话与用量
│   ├── ui/                   # Compose 页面、账户状态、翻译 ViewModel
│   └── brand/                # 品牌配置
└── app/src/test/             # 单元测试
```

Android 必须保持 Kotlin + Jetpack Compose 原生实现，**不是 WebView 应用**。

### Android Cloud API 接入状态

已在独立远程分支完成并推送：

```text
branch: android-cloud-auth
latest: 35445f1 feat(android): report translation session usage
```

关键提交：

```text
3c559c7 feat(android): integrate cloud account and sessions
c821ad5 fix(android): end manual cloud sessions
7d02949 feat(android): drive navigation by cloud role
9ac709d chore(android): use temporary public cloud api in debug
fe9ed0c fix(android): route debug cloud api through frp
7a1e46d fix(android): migrate obsolete debug cloud endpoint
35445f1 feat(android): report translation session usage
```

已实现：

- 邮箱注册、登录、刷新、登出；
- Access/Refresh Token 的安全存储抽象（不写日志）；
- 稳定 installation ID；
- 当前用户与权益查询；
- 兑换码；
- 创建/结束 Cloud Translation Session；
- Cloud Session JWT → Agent WebSocket subprotocol 握手；
- `role=user`：正式用户版导航；
- `role=admin`：默认进入测试版导航，可主动预览正式用户版；
- 未登录：仅显示注册/登录；
- 一个 Cloud Translation Session 结束时的最小化用量上报；
- 单人和面对面模式的会话生命周期收口。

角色规则：

```text
未登录             → 认证页面
role = user        → 正式用户翻译体验
role = admin       → 测试版/诊断入口，可预览用户版
```

客户端导航不是权限边界；Cloud API RBAC、权益检查与 Agent Session Token 验证才是权限边界。

### Android 用量记录

Android 与当前后端的真实接口契约为：

```http
POST /api/v1/usage-records
Authorization: Bearer <access JWT>

{
  "session_id": "<UUID>",
  "audio_seconds": 12,
  "characters": 0
}
```

当前 Android 的隐私策略：

- 按 **Cloud Translation Session** 聚合，上报最多一次；
- 上报 `session_id`、会话级 `audio_seconds` 和 `characters=0`；
- 不读取、上传或持久化 PCM、原文、译文、JWT、邮箱、密码；
- 用量请求失败不会阻断会话结束、翻译 UI、登出；
- 面对面多 turn 不会重复计费。

后端当前 `usage_records` 记录为零时并不代表翻译失败；旧安装包/旧会话不会自动补写。重新安装包含 `35445f1` 的 APK，并完成新的会话后，才应出现新的 usage record。

### Android 验证命令

```bash
cd /tmp/dngmeng-android-cloud-auth/android
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17 \
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk \
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

最近验证结果：

```text
BUILD SUCCESSFUL
```

Debug APK：

```text
/tmp/dngmeng-android-cloud-auth/android/app/build/outputs/apk/debug/app-debug.apk
```

### Android 网络规则

```text
Debug   → 可配置 http/ws，用于本机与临时联调
Release → 只接受 https/wss
```

当前 Debug 临时默认 Cloud API 地址：

```text
http://114.132.83.144:8080
```

这是临时测试配置，不可作为发布配置。App 会迁移以前保存的 `http://127.0.0.1:8080` 旧默认值；如设备仍显示旧值，应更新 APK 后重启，或在测试版服务设置中恢复默认/卸载重装。

---

## 6. Cloud API

### 代码位置与分支

实现工作树：

```text
/tmp/dngmeng-cloud-api-business/cloud-api
branch: cloud-api-business
latest commit: 421498f feat(cloud-api): allow eight character passwords
```

关键文件：

```text
cloud-api/internal/http/business.go
cloud-api/internal/http/router.go
cloud-api/internal/http/admin.go
cloud-api/internal/auth/authorization.go
cloud-api/internal/store/business.go
cloud-api/internal/store/lifecycle.go
cloud-api/internal/domain/domain.go
cloud-api/API.md
```

### 已实现业务能力

认证与账户：

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/users/me
GET  /api/v1/users/me/devices
```

权益、兑换与翻译会话：

```text
GET  /api/v1/entitlements/current
POST /api/v1/redemptions
POST /api/v1/translation-sessions
GET  /api/v1/translation-sessions
POST /api/v1/translation-sessions/{sessionID}/end
POST /api/v1/translation-sessions/{sessionID}/revoke
POST /api/v1/usage-records
```

管理员能力包括用户、权益、兑换码批次、会话/用量和审计相关接口。

### 已实现业务规则

- 邮箱/密码注册；
- Argon2id 密码哈希；
- 密码最小长度：**8 bytes**；
- Access/Refresh Token 生命周期与 Refresh Family 重放撤销；
- 注册后 3 天试用；
- 兑换码一次性使用后授予 365 天权益；
- 多设备登录允许；
- 同一用户同一时间最多一个活动翻译会话；
- 翻译会话结束、撤销、过期后释放；
- installation ID 持久化；
- 管理员 RBAC 与审计基础；
- PostgreSQL 事务性业务存储；
- 反馈仅在明确同意时保存对象存储 metadata，14 天清理模型。

### Translation Session JWT 契约

Cloud API 签发的 Token 必须符合 Agent 校验：

```text
algorithm: HS256
typ:       translation_session
issuer:    configured issuer
audience:  configured Agent audience
sub:       user_id
claims:    user_id, session_id, install_id
```

Agent 已启用本地会话 JWT 校验；Agent 与 Cloud API 运行时必须使用同一份受限的签名密钥、issuer 与 audience 配置。不要打印这些值，也不要把它们放进客户端、Git 或日志。

### Cloud API 验证命令

```bash
cd /tmp/dngmeng-cloud-api-business/cloud-api
go test ./...
go vet ./...
go build ./cmd/cloud-api
git diff --check
```

**注意：** Cloud API 分支尚未完成最终独立验收和整合，不能因为本地服务可运行就认为已适合生产发布。

---

## 7. 管理后台（Admin Web）

### 代码位置与分支

```text
/tmp/dngmeng-cloud-admin-live/cloud-api/admin-web
branch: cloud-admin-live
latest: 4f07956 fix(admin-web): parse admin list envelopes
```

本地地址：

```text
http://10.10.10.7:5180/
```

### 已实现

- 中文管理员登录页；
- `POST /api/v1/auth/login`；
- `GET /api/v1/users/me` 校验 `role=admin`；
- 普通用户被拒绝并清除会话；
- access/refresh 凭据仅在内存与 `sessionStorage`，不放 `localStorage`；
- 一次自动 refresh 重试；
- 登出调用 API 后清理会话；
- 管理员用户和审计日志列表解析真实后端 envelope：

```json
{ "users": [] }
{ "audit_logs": [] }
```

开发别名：

```text
admin → admin@123.com
```

仅在 Vite development 模式存在；生产环境绝不可启用。

### 管理后台数据原则

- 不伪造运营数据；
- API 未实现或不可用时必须显示明确的“接口待接入”/失败状态；
- 管理员账号没有公开注册接口；
- 本地管理员密码重置工具仅用于本机开发，不能提交或部署为生产通道。

---

## 8. 当前本机运行环境

### 服务监听与健康状态（最后核对）

```text
Translator Agent: 127.0.0.1:18765
Translation Web:  0.0.0.0:5173
Admin Web:        0.0.0.0:5180
Cloud API:        0.0.0.0:8080
Cloud PostgreSQL: 127.0.0.1:15432
```

已确认：

```text
GET http://127.0.0.1:18765/api/health
→ {"status":"ok","service":"translator-agent"}

GET http://127.0.0.1:8080/api/v1/ready
→ {"service":"cloud-api","status":"ready"}
```

现有的无关 PostgreSQL 使用 `127.0.0.1:5432`，**不得停止或修改**。Cloud API 独立使用 `15432`。

### 本机临时文件（不得提交）

| 路径/类型 | 用途 | 要求 |
|---|---|---|
| `agent/.env.local` | Volcengine/Qwen 本地凭据 | Git ignore、600、不输出 |
| `~/.cache/dngmeng/cloud-api.dev.env` | Cloud API 本地开发秘密 | Git ignore、600、不输出 |
| `/tmp/start-cloud-api-runtime.sh` | 临时 Cloud API 启动器 | 重启后可能消失 |
| `/tmp/start-translator-agent-runtime.sh` | 临时 Agent 启动器 | 当前加载本地 Session JWT 信任配置；重启后需要复核 |
| `~/.local/share/verba-frp/cloud-api-frpc.toml` | 临时 FRP 配置 | 600、包含认证信息、不得提交 |
| `cloud-api/cmd/local-reset-admin/` | 本地管理员密码重置临时工具 | 未提交，完成本机设置后删除 |

### FRP 临时联调

当前临时目标是：

```text
114.132.83.144:8080 → 127.0.0.1:8080（Cloud API）
```

FRP 客户端已启动并记录了 `start proxy success`。本机对自身公网 IP 的测试可能因 NAT hairpin/云网络策略超时，不能单独作为真机可达性结论；应以外部网络或真机的 `/api/v1/ready` 实测结果为准。

历史临时映射：

```text
114.132.83.144:15173 → Translation Web 5173
114.132.83.144:18765 → Translator Agent 18765
```

它们仅用于开发，不属于生产发布方案。

---

## 9. 品牌与视觉系统

根目录 `branding/` 是可替换品牌源：

```text
branding/
├── app.json
├── slogans.json
└── logo/
    ├── app-logo.svg
    ├── 商标矢量.svg
    └── 商标（非ico）.jpg
```

言枢视觉系统：

```text
页面底色    #F4F7F6
主文字      #14211F
次级文字    #60706C
品牌主色    #0F6C66
交互高亮    #D7F0EA
运行状态    #0B8A73
错误状态    #B54743
分隔线      #D9E3E0
```

UI 原则：冷静、专业、翻译工作台优先；避免泛化的 AI 紫色/玻璃拟态；面对面默认使用紧凑双语 PTT；配置应在工作开始前编辑、活跃工作期间折叠。

---

## 10. Git 状态与安全整合注意事项

### 主要分支

| 分支 | 用途 | 当前状态 |
|---|---|---|
| `main` | 现有主分支 | 当前本地 `ahead 5`，且根目录有历史未跟踪目录，严禁 bulk add |
| `android-cloud-auth` | Android Cloud API 用户端接入 | 已推送，最新 `35445f1` |
| `cloud-api-business` | Cloud API 业务实现 | 本地工作树存在，尚未最终验收/推送整合 |
| `cloud-admin-live` | 管理后台 | 本地工作树，最新 `4f07956` |

当前工作树：

```text
/home/genanalucy/dngmeng_project        main
/tmp/dngmeng-android-cloud-auth         android-cloud-auth
/tmp/dngmeng-cloud-api-business         cloud-api-business
/tmp/dngmeng-cloud-admin-live           cloud-admin-live
```

### 必须遵守

- 不把根目录未跟踪的 `android/`、`ui/`、`cloud-api/`、`docs/` 或 `.pi/` 目录不加审查地 `git add .`；
- 不提交 `agent/.env.local`、CSV、数据库配置、FRP 配置、Token、APK build output 或临时脚本；
- 不提交 `/tmp/dngmeng-cloud-api-business/cloud-api/cloud-api` 二进制文件；
- 不提交 `/tmp/dngmeng-cloud-api-business/cloud-api/cmd/local-reset-admin/` 临时密码工具；
- 集成时使用新的干净 worktree，选择性 cherry-pick/合并已验证提交；
- 每次实质改动完成并验证后，按项目习惯提交；无关变更不得进入提交。

---

## 11. 已知问题、限制与生产缺口

### 尚未完成/未最终验收

1. Cloud API 业务分支尚未进行完整真实 PostgreSQL 端到端验收，也未整合到 `main`；
2. 管理后台还需针对最终 API 契约完成全部实时页面联调；
3. Android 与当前本机 Cloud API 的真实设备端到端验收仍需执行；
4. Android 真正的 Release 版还不能使用临时 HTTP/FRP，必须先配置正式域名、HTTPS/WSS；
5. Android 真实耳机左右声道、长时间麦克风、Qwen 真实音频质量/延迟需要物理设备验证；
6. Android Studio Device Mirroring 的音频重定向会影响真实设备麦克风初始化；
7. SMS、邮件、私有对象存储上传/签名、生产反馈删除任务尚未落地；
8. 域名、TLS/WSS、托管 PostgreSQL、备份、监控、限流和告警仍是部署前置条件；
9. 当前临时服务/FRP 进程不具备重启自愈；机器重启后须从 Git/受限配置恢复并复核；
10. 以前在聊天中暴露过的密码应在本地开发完成后轮换，后续不得重复输出。

### 关键安全要求

- 不将 Provider API Key 放入 Web、Android、Git、日志或测试快照；
- 不在 URL、日志、截图或错误提示中显示 JWT；
- Session JWT 仅短期内存使用，通过 WebSocket subprotocol 传递；
- Release 仅允许 `https/wss`；
- 不为了本地联调关闭 Agent 的 session JWT 校验；
- 管理后台永远需要后端 `role=admin` 校验，前端页面隐藏不是权限控制；
- 普通翻译默认不得留存原始音频、字幕或译文正文。

---

## 12. 推荐后续执行顺序

### A. 立即联调验收

1. 使用 `android-cloud-auth` 最新提交重新安装 Debug APK；
2. 在真机/模拟器确认 Cloud API 地址为临时公网地址，而非 `127.0.0.1`；
3. 使用普通用户登录，检查：注册/登录、3 天试用或兑换、开始翻译、结束翻译；
4. 使用管理员登录，检查：默认测试版导航、预览用户版、Admin Web RBAC；
5. 完成一次新的翻译会话后，在 Cloud API 查询 `translation_sessions` 与 `usage_records`：应只有会话级一条 usage，不应有正文/PCM；
6. 验收 Agent Session JWT 绑定：错误 Token、错用户/设备/会话应被拒绝，正确 Token 可翻译。

### B. Cloud API 最终验收与整合

```bash
cd /tmp/dngmeng-cloud-api-business/cloud-api
go test ./...
go vet ./...
go build ./cmd/cloud-api
git diff --check
```

还应覆盖真实 PostgreSQL 路径：

```text
register → login → refresh → refresh replay revoke
trial → entitlement
redeem → one-time code → 365-day entitlement
translation session → Agent JWT verification → end/revoke/expiry
usage record
admin RBAC + audit
feedback consent/expiry metadata
```

### C. 生产准备

1. 部署正式 Cloud API 与 Agent；
2. 配置域名、TLS、HTTPS、WSS 和 CORS；
3. 使用 Secret Manager/部署平台安全注入密钥；
4. 配置托管 PostgreSQL、备份、监控、日志脱敏、告警；
5. 配置对象存储和 14 天反馈清理任务；
6. 用一次性、可审计 bootstrap-admin 命令替代本地临时管理员重置工具；
7. 将 Android Release 的 endpoint 改为正式 `https`/`wss` 域名，执行签名与真实设备回归。

---

## 13. 快速排障

| 现象 | 优先检查 |
|---|---|
| Android 显示 `127.0.0.1:8080` | 使用 `android-cloud-auth` 最新 APK；重启；恢复默认；必要时卸载重装 |
| `failed to connect` | Cloud API `/ready`、FRP 进程、云安全组/防火墙、外网端口，注意本机公网回环不可靠 |
| `invalid_start: invalid start request` | Agent 是否启用 session JWT 校验；Android 是否携带 cloud grant；start 的 userId/installId 与 token claims 是否绑定 |
| `TRANSLATION_AUTH_INVALID` | Token 是否过期、issuer/audience/HS256 密钥是否一致、FRP/代理是否保留 WebSocket subprotocol |
| 管理后台 `invalid_response_contract` | 后端列表接口的 envelope；已修复用户 `{users: []}` 和审计 `{audit_logs: []}` 解析 |
| 只有 session 无 usage | 确认已安装 `35445f1` 后的新 APK；usage 仅在会话结束时异步上报；检查会话是否真正结束 |
| Android Run 按钮灰色 | Gradle Sync、选择 `app`、启动 AVD/连接真机；确认打开 `/tmp/dngmeng-android-cloud-auth/android` |

---

## 14. 关键命令参考

```bash
# 查看 Android Cloud 分支
cd /tmp/dngmeng-android-cloud-auth
git status --short --branch
git log --oneline -10

# 构建 Android Debug
cd /tmp/dngmeng-android-cloud-auth/android
JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17 \
ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk \
PATH=$HOME/.local/share/verba-android-tools/jdk17/bin:$PATH \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

# 检查本地服务健康（不输出任何密钥）
curl -fsS http://127.0.0.1:18765/api/health
curl -fsS http://127.0.0.1:8080/api/v1/ready

# 查看工作树，不要执行 git add .
git worktree list
git status --short
```

---

## 15. 交接结论

当前项目已经具备：

```text
真实 Web/Android 翻译工作台
Volcengine + Qwen 四语言 Provider 路由
Agent Session JWT 鉴权
Cloud API 业务核心
Android 用户账户、角色路由、权益、会话与最小化用量上报
管理后台管理员登录和部分实时列表
```

但仍处于**本地/内测联调阶段**，不是可直接上线的生产系统。下一位执行者应优先完成 Cloud API 真实端到端验收与干净分支整合，再进行域名/TLS/部署/真实设备回归。
