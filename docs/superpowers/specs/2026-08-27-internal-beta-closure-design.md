# 言枢智能内测闭环设计

**状态：** 已获用户确认，待实现计划审批

**日期：** 2026-08-27
**范围：** Android 普通用户翻译主路径、Cloud API 控制面、Translator Agent 授权与数据面、Admin Web 最小 RBAC 验收，以及来自已提交 Git 历史的干净整合。

## 1. 目标与完成定义

本轮交付内测闭环，而非生产上线。首要成功目标是：Android 普通用户能够注册或登录、获得试用或兑换权益、发起翻译、说中文后获得英文字幕或语音，并正常结束会话。

内测闭环完成必须同时满足：

1. Cloud API、Agent、Android、Admin Web 和现有 Web 的可执行自动化检查通过；
2. Cloud API 在真实 PostgreSQL 上完成账户、权益、翻译会话、结束和用量的最小业务路径；
3. Agent 仅在正确的短期会话 JWT 与首包绑定字段通过时启动 Provider；
4. Android 真机或模拟器至少完成一次中译英真实翻译，能够正常收尾；
5. 单个 Cloud Translation Session 最多产生一条聚合 usage 记录；
6. 普通用户不能读取管理员数据，管理员能访问已实现的后台列表；
7. 所有整合内容可追溯至已提交的 Git commit，且不包含机密、构建产物、本机配置或临时脚本；
8. 已知限制和生产前缺口有明确记录。

## 2. 不在本轮范围

下列事项独立作为生产准备阶段，不以本轮内测完成为前提：

- 正式域名、HTTPS/WSS、公开公网服务；
- Android Release 签名、应用商店发布；
- 云端高可用、自动扩缩容、完整监控告警与备份恢复演练；
- 短信、邮件、支付；
- 私有对象存储的真实上传、签名 URL 与 14 天物理删除 worker；
- 新增翻译功能或大规模 UI 重设计。

## 3. 权威代码来源与整合原则

当前根目录 `main` 是 Web 和 Translator Agent 基线。Android、Cloud API、Admin Web 的权威实现来自独立 worktree 的已提交 Git 历史：

| 组件 | Worktree | 分支 | 已知基线提交 |
|---|---|---|---|
| 主线 Web + Agent | `/home/genanalucy/dngmeng_project` | `main` | 以开始整合时的已验证 HEAD 为准 |
| Android Cloud 接入 | `/tmp/dngmeng-android-cloud-auth` | `android-cloud-auth` | `35445f1` |
| Cloud API 业务实现 | `/tmp/dngmeng-cloud-api-business` | `cloud-api-business` | `421498f` |
| Admin Web | `/tmp/dngmeng-cloud-admin-live` | `cloud-admin-live` | `4f07956` |

整合仅能从这些分支中已经提交、已经验证的 commit 选择性合并。根目录未跟踪的 `android/`、`cloud-api/`、`docs/`、`ui/` 和 `.pi/` 不作为实现来源，也不得执行 `git add .`。

必须排除：`agent/.env.local`、其他环境文件、CSV、FRP 配置、APK、Gradle/Node 缓存、二进制、临时启动脚本、临时管理员重置工具和任何数据库导出。

## 4. 系统边界与稳定协议

### 4.1 控制面与数据面

- **Cloud API：**账户、RBAC、trial、权益、兑换、翻译会话授权、用量、管理审计和反馈元数据；不处理或长期保存流式 PCM。
- **Translator Agent：**音频流、Provider 路由、字幕、TTS PCM、WebSocket 会话 JWT 校验；不承担账户和权益长期存储，不向客户端暴露 Provider 密钥。
- **Android/Web：**采集 PCM、显示字幕、播放 PCM、调用控制面并连接 Agent；不持有 Provider 密钥。
- **PostgreSQL：**业务元数据和状态；不保存普通翻译流程的原始 PCM。

### 4.2 Provider 路由

| 语言组合 | Provider |
|---|---|
| `zh ↔ en` | Volcengine AST |
| 任意包含 `fr` 或 `vi` | Qwen `qwen3.5-livetranslate-flash-realtime` |

Provider 不可用时必须返回明确错误；不得静默回退到 Mock 或其他 Provider。

### 4.3 Cloud API 会话流程

```text
Android/Web
  → 注册或登录
  → 查询当前用户与权益
  → 创建 Cloud Translation Session
  → 获得 session_id 与短期 Session JWT
  → 使用该 JWT 建立 Agent WebSocket
  → 正常结束会话并进行最多一次聚合 usage 上报
```

内测必须验证：无有效权益不能创建会话；同一用户同一时间最多一个活动会话；会话结束、撤销、过期后释放；usage 上报失败不阻断客户端收尾。

### 4.4 Agent WebSocket 授权

会话 JWT 仅通过 WebSocket subprotocol 传递：

```text
translation.v1, translation.jwt.<compact-JWT>
```

不得放入 query string、Cookie 或 `Authorization` header。Agent 只协商 `translation.v1`，不回显 JWT。

首个文本 `start` 消息包含会话及音频参数；启用 Cloud API 会话授权时，必须包含 `userId` 与稳定 `installId`。Agent 需要校验：

- JWT 算法为 HS256、`typ=translation_session`；
- issuer、audience、过期时间；
- JWT 的 user、session、install claims；
- 首包 `userId`、`sessionId`、`installId` 与 claims 的完全绑定；
- 未通过校验时拒绝连接且不接触 Provider；
- 错误响应、日志、指标和 URL 不包含 JWT。

### 4.5 PCM framing 核验

交接文档写有“16 kHz、20 ms、2560 bytes”，但 mono PCM16 的实际计算为：

```text
16,000 samples/s × 2 bytes/sample = 32,000 bytes/s
20 ms = 640 bytes
80 ms = 2,560 bytes
```

现有 Web 与 Agent 基线使用 `16 kHz / mono / PCM16 / 2,560 bytes`，即 80 ms。M0/M1 必须核验 Android 的真实包长：

- 若 Android 同样发送 2,560 bytes，则将规范统一为 80 ms；
- 若 Android 发送 640 bytes，则在整合前统一 framing 或引入明确版本协商与测试；
- 不以无版本约束的“同时接受两种包长”掩盖协议分裂。

## 5. 失败处理与安全约束

| 场景 | 要求 |
|---|---|
| Cloud API 离线 | 客户端显示明确网络/服务失败，不伪造登录或权益 |
| 无权益 | 不创建 session，不连接 Agent |
| 无效、过期或绑定不符 JWT | Agent 拒绝，且不连接 Provider |
| Agent 离线 | 客户端明确不可开始或连接失败 |
| Provider 故障 | 返回准确错误，不回退 Mock |
| 网络中断 | 停止录音、收口 UI、释放资源，不重复 usage |
| usage 上报失败 | 不阻断会话与 UI 结束 |
| 普通用户访问管理接口 | 后端拒绝；后台清理非管理员会话 |
| 后台接口 envelope 不匹配 | 显示明确错误，绝不伪造运营数据 |

只使用 Cloud PostgreSQL 的 `127.0.0.1:15432` 进行本轮验证；严禁停止、修改或迁移无关的 `127.0.0.1:5432` 实例。

## 6. 里程碑与依赖

### M0：冻结来源与协议基线

**工作：**检查四个 worktree 的提交、未提交改动、构建入口和协议字段；建立可合并 commit、协议差异、排除项清单；创建干净整合 worktree。

**完成条件：**每项实现可追溯到 Git commit；不依赖根目录未跟踪残留；明确 Android、Cloud API、Agent 的 JWT/subprotocol/start/PCM 契约。

### M1：组件级质量基线

**工作：**

- Agent：`go test ./...`、`go vet ./...`、构建和 JWT 正反例；
- Cloud API：`go test ./...`、`go vet ./...`、构建、迁移和 PostgreSQL 配置路径；
- Android：`testDebugUnitTest`、`lintDebug`、`assembleDebug`；
- Admin Web：测试、类型检查、lint、production build；
- Web：现有 test/typecheck/lint/build 回归。

**完成条件：**本地可执行检查全通过，或外部依赖阻塞有明确、可复现的根因和边界；Android 生成可安装 Debug APK。

### M2：控制面与 Agent 授权链路

**工作：**在 Cloud PostgreSQL 上验证注册、登录、trial/兑换、创建 session、JWT 签发、Agent 正确授权、错误/过期/错误签名/错误 user-install-session 拒绝、finish、usage 聚合和 Admin RBAC。

**完成条件：**正确授权能进入 Agent `ready`；错误授权不接触 Provider；一个 session 最多一条 usage；普通用户无法读取管理数据。

### M3：Android 真机翻译验收

**工作：**确认 Debug endpoint 不指向错误的 `127.0.0.1`，检查 Provider 受限配置存在性而不读取其值，准备 APK 和一页式操作单，执行真实中译英会话并核对会话/usage 状态。

**用户操作：**安装 Debug APK、授予麦克风、注册或登录普通用户、进入单人同传、说一句中文、确认英文字幕或语音、结束并反馈成功或页面错误截图。

**完成条件：**至少一次中译英返回字幕或语音；正常结束；不崩溃或永久卡在录音/连接状态；Cloud API 的会话和 usage 状态正确。

### M4：Admin 验收与干净整合提交

**工作：**验证管理员登录和用户/审计数据读取、普通用户拒绝、真实 envelope 解析与不可用状态；在干净 worktree 选择性整合 M0-M3 已验证提交；重跑检查、审查 diff 和状态；提交整合结果。

**完成条件：**Git worktree 干净；所有整合来源明确；验证重新通过；不包含机密、缓存、二进制或无关文件；已知生产缺口记录完整。

依赖顺序：

```text
M0 → M1 → M2 → M3 → M4
```

若 M1 失败，不进入 M2；若 M2 的授权、会话或 usage 不正确，不进入 M3；仅在 M3 完成后才能宣告内测体验闭环。

## 7. 验收矩阵

| 层级 | 最低验证 | 通过标准 |
|---|---|---|
| Agent | 单测、vet、build、JWT 正反例 | 授权失败不接触 Provider |
| Cloud API | 单测、vet、build、真实 PostgreSQL | 账户、权益、session、usage、RBAC 可复现 |
| Android | 单测、lint、Debug APK | 登录、会话、subprotocol、结束/usage 生命周期通过 |
| Admin Web | test、typecheck、lint、build | admin 可访问、普通用户拒绝、真实 envelope 可解析 |
| Web | test、typecheck、lint、build | 现有功能无回归 |
| 跨端协议 | JWT 正反例和 framing 核验 | user/session/install 三重绑定一致 |
| 真机翻译 | 至少一次中译英 | 字幕或语音返回且正常结束 |
| 隐私 | 日志/业务数据抽查 | 不含密码、token、PCM、字幕正文 |

## 8. 最终交付物

1. 干净可重复构建的集成分支和 Git commit；
2. 组件版本/commit 清单与验证命令结果；
3. Android Debug APK 的构建位置（APK 本身不提交）；
4. Cloud API + Agent + Android + Admin Web 的内测验收记录；
5. 一页式真机操作单；
6. 已知限制与生产准备待办。

## 9. 生产前遗留项

内测成功不代表生产就绪。下一阶段另行规划：正式域名与 TLS、HTTPS/WSS、云端 Agent 和 Secret Manager、托管 PostgreSQL、备份、监控告警、对象存储与删除 worker、Android Release 签名/分发、容量与限流策略。
