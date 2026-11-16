> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `cloud-api-architecture-review.md`；原始 SHA-256 `03bc33714a98c36b7a453929b8fb6871277cdf93ca815d59ea558c461e6d4141`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../docs/architecture/README.md)。

# Cloud API Architecture Review

> 评审范围：当前工作树中的 `cloud-api/`、`agent/`、数据库迁移及现有产品规格。本文只评审架构与 MVP 决策，不代表现有功能已完成生产验收。

## 1. 结论摘要

当前 `cloud-api` 已具备可用的控制面骨架：REST、Postgres、Argon2id 密码、短期 access token、旋转 refresh token、3 天 trial、365 天兑换包、翻译会话授权、反馈同意与审计日志。`translator-agent` 已具备实时数据面骨架：浏览器 WebSocket、PCM 队列、AST 协议转换和供应商凭据隔离。

但二者目前没有真实集成，不能把现状视为可上线闭环。外部试点前至少解决以下 P0：

1. `translator-agent` 不验证 Cloud API 签发的 translation token，任何允许 Origin 的页面都可建立翻译会话。
2. 生产客户端若继续运行本地 Agent，就必须持有长期供应商 API Key；服务方密钥无法安全下发到用户设备。
3. 兑换码生成时按原文哈希，兑换时先 `ToUpper` 再哈希；包含小写字符的已生成兑换码无法命中。
4. 反馈记录最多 14 天到期，但没有对象存储生命周期、删除任务或撤回同意后的清理闭环。
5. `/v1/usage-records` 由用户 access token 调用，客户端可伪造用量，不能用于额度、计费或风控。
6. 关键跨表归属主要靠应用 SQL 保证，数据库无法阻止错误 entitlement、session、consent 组合。

总体建议：保留 Cloud API 作为**控制面**；把生产 `translator-agent` 部署为云端**实时数据面**。现有 localhost Agent 仅保留为开发/自带 Key 模式。Cloud API 不代理 PCM，translator-agent 不拥有用户、权益和兑换业务。

## 2. Cloud API / translator-agent 边界

| 能力 | Cloud API（控制面） | translator-agent（数据面） | Browser / App |
| --- | --- | --- | --- |
| 账号、密码、角色 | 拥有 | 不接触 | 登录并安全保存 token |
| trial、兑换、权益 | 判定并持久化 | 只消费授权结果 | 展示状态、发起兑换 |
| translation grant | 签发、记录、单次消费 | 严格验证并绑定连接 | 仅在 WSS 首包提交 |
| 供应商 API Key | 由服务端 Secret Manager 管理 | 运行时读取 | 永不获得 |
| AST WebSocket / Protobuf | 不处理 | 拥有 | 不处理 |
| 麦克风、重采样、声道、播放 | 不处理 | 不处理 | 拥有 |
| PCM / 字幕 / TTS 流 | 不持久化、不转发 | 仅内存中保序转发 | 采集与播放 |
| 权威 usage | 接收并持久化 | 测量并通过内部接口上报 | 仅可展示本地估算 |
| 反馈元数据与保留策略 | 拥有 | 默认不采集 | 明示同意后上传 |

推荐生产链路：

```text
App --HTTPS/access token--> Cloud API
App <--session_id + 5min translation grant + wss_url-- Cloud API
App --WSS/首包 grant + start--> cloud translator-agent
translator-agent --验证签名 + 原子 consume--> Cloud API internal API
translator-agent <----WSS/Protobuf----> Volcengine AST
App <----字幕/PCM----> translator-agent
translator-agent --可信 usage/finish--> Cloud API internal API
```

关键约束：

- 浏览器 WebSocket 不要把 token 放在 query string；URL 常进入代理和访问日志。浏览器用 WSS 首个 JSON 消息携带，Android/iOS 可用 `Authorization` header。
- WSS 在授权完成前不得连接供应商、接收 PCM 或分配大队列。
- Origin allowlist 是浏览器侧纵深防御，不是身份认证。
- translation grant 单次消费并绑定 `session_id + user_id + connection`；断线重连需明确策略，不能静默复用。
- Cloud API 只处理低频 JSON；不要为“统一入口”让它代理实时音频，否则会放大延迟、内存与故障域。
- 如果坚持生产 localhost Agent，必须先确认供应商支持可限权、极短期、可撤销的临时凭据；当前长期 API Key 模式不应分发。

## 3. REST API 评审

现有路由分组清楚，统一 `/v1`、JSON error envelope、请求体上限、精确 CORS、分页上限和 `no-store` 都适合 MVP。建议保持资源式命名，但补齐信任边界与幂等语义。

| 路由 | 当前用途 | 评审与建议 |
| --- | --- | --- |
| `POST /v1/auth/register` | 注册并创建 trial | 保持事务；增加邮箱验证可延期，但必须有 trial 防滥用策略 |
| `POST /v1/auth/login` | 签发 access/refresh | 返回 `expires_in`；未知用户也执行恒定成本校验，降低时序枚举 |
| `POST /v1/auth/refresh` | refresh rotation | 复用即撤销 family 是正确方向；处理“服务成功但响应丢失”的安全重试窗口 |
| `POST /v1/auth/logout` | 撤销一个 refresh | 可幂等返回 `204`；后续补“全部设备登出” |
| `POST /v1/auth/sms/verify` | development stub | 生产必须路由不可用；MVP 不要把 stub 扩展成真实短信系统 |
| `POST /v1/redemptions` | 消费兑换码 | 应支持 `Idempotency-Key`；同一用户重试同一码宜返回原 entitlement，而非模糊 `409` |
| `POST /v1/translation-sessions` | 检查权益并签 grant | 返回 `wss_url`、`expires_in`；grant 到期不得晚于 entitlement 到期 |
| `POST /v1/usage-records` | 客户端上报 usage | 从公共 API 移除权威性；改为受 mTLS/service auth 保护的 internal route |
| `POST /v1/feedback/consents` | 记录同意事件 | 需定义当前有效状态、撤回语义和版本；历史 granted 记录不可永久复用 |
| `POST /v1/feedback/artifacts` | 登记 object key | 不接受客户端自选 key/到期时间；服务端生成 key、过期时间和预签名上传 |
| `GET /v1/feedback/artifacts/{id}` | 返回元数据 | 过期或删除中返回 `404/410`；禁止返回永久公开对象 URL |
| `POST /v1/admin/redemption-code-batches` | 生成 365 天码 | 明文码只返回一次；强制 MFA/再认证、审计、下载防缓存 |
| `GET /v1/admin/users` | 管理员分页 | MVP 可保留；避免默认返回不必要身份字段 |
| `GET /v1/admin/audit-logs` | 审计查询 | 使用稳定游标优于大 offset；审计存储应追加写 |

建议补充的最小路由：

- `GET /v1/me` 与 `GET /v1/entitlements/current`：客户端登录后恢复状态，避免依赖注册响应。
- `DELETE /v1/feedback/artifacts/{id}`：用户主动删除；响应可为 `202`，后台完成对象与元数据清理。
- internal `consume/start/finish/usage` 路由：仅 translator-agent 可调用，不暴露给普通 access token。

所有创建型请求应规定幂等键保留期、重复请求响应和冲突 code。`Content-Type` 应明确要求 `application/json`。公开错误不要泄露账号存在性、数据库约束名或供应商原始错误。

## 4. Postgres 约束与事务

现有优点：身份唯一、refresh hash 唯一、兑换码 hash 唯一、兑换行 `FOR UPDATE`、兑换状态一致性 CHECK、entitlement 时间 CHECK、反馈最长 14 天 CHECK、外键和主要索引均已存在。

上线前建议补强：

| 范围 | 风险 | 推荐约束/事务 |
| --- | --- | --- |
| trial | 仅注册代码保证一次 | `UNIQUE(user_id) WHERE kind='trial'`；trial 时长由服务端固定 |
| email | 小写规范只在 HTTP 层 | 使用 `citext` 或持久化 canonical identifier，禁止旁路写入大小写重复邮箱 |
| entitlement 来源 | package/source 对应关系不完整 | package 必须有 source code、trial 必须无 source；`source_redemption_code_id` 非空时唯一 |
| session 归属 | entitlement 可理论上属于另一用户 | 给 entitlement 建 `(id,user_id)` 唯一键，session 使用复合外键 |
| usage 归属 | user/session 归属靠 INSERT SELECT | 使用 `(translation_session_id,user_id)` 复合外键；每 session 一条则加 UNIQUE，否则使用唯一 event id |
| consent 归属 | artifact/consent 同用户靠查询 | 复合外键 `(consent_id,user_id)`；有效授权仍需事务检查或不可变 grant 模型 |
| translation grant | 只有 `jti/expiry` | 增加 `status`、`consumed_at`、`finished_at`、失败原因和原子状态迁移 |
| refresh token | 状态组合可不一致 | CHECK：`replaced_by_id IS NOT NULL` 时 `revoked_at IS NOT NULL`；expiry 晚于 created |
| timestamps | `updated_at` 多数不会自动更新 | 明确由每条 UPDATE 维护，或统一 trigger；不要让字段名暗示不存在的行为 |
| migrations | `CREATE EXTENSION pgcrypto` 可能需高权限 | 部署前置创建 extension；应用迁移账号与运行账号分权 |

跨表 CHECK 无法直接表达时，用复合 FK、事务锁和少量可测试 trigger，不要只依赖 handler 校验。所有授权时间以数据库/服务端 UTC 为准，测试边界采用半开区间 `[starts_at, expires_at)`。

## 5. Token 模型

### 现状

- access JWT：HS256、15 分钟、独立 secret，含 `iss/aud/sub/jti/iat/exp/scope`。
- refresh token：32 字节随机值、数据库仅存 SHA-256、30 天、按 family 旋转并检测复用。
- translation JWT：HS256、5 分钟、另一 secret，含 session、entitlement、user、jti 和 `scope=translation`。
- API 鉴权会重新读取用户与角色，避免仅信任 JWT 内角色；这是正确取舍。

### 建议

1. translation token 改用 Ed25519/ES256 等非对称签名；Cloud API 保管私钥，translator-agent 只持公钥/JWKS。不要把 HS256 签名 secret 分发给数据面实例或客户端设备。
2. access 与 translation 使用不同 `aud`、key、`kid` 和验证器；scope 必须精确匹配，不能只判断非空。
3. translator-agent 校验 `alg/iss/aud/exp/nbf/iat/jti/sub/scope/session_id/entitlement_id`，并调用原子 consume；JWT 有效不等于 session 未使用。
4. translation token 的 `exp = min(now + 5m, entitlement.expires_at)`。5 分钟只表示允许开始连接；已授权连接的最大运行时长另设上限。
5. access token 仅放内存；移动端 refresh token 放 Keychain/Android Keystore。Web 若上线，优先评估 `Secure + HttpOnly + SameSite` cookie 与 CSRF 策略，禁止 `localStorage` 长期保存 refresh token。
6. 密钥来自 Secret Manager，支持轮换和双 key 验证窗口；日志、URL、错误、指标和 tracing 均不得记录 token。
7. 高熵 refresh token 使用快速哈希是合理的；密码继续使用 Argon2id，不要互换两种策略。

## 6. 3 天 trial 与 365 天兑换

应把产品语义写死并由数据库测试：

- 注册成功在同一事务创建一次 trial；建议精确定义为 `72 hours`，而不是受数据库 session timezone/DST 影响的模糊日历日。
- 每个兑换码只消费一次；当前 `FOR UPDATE + redeemed_by IS NULL` 能处理同一码并发兑换。
- **先修复大小写规范**：生成和兑换必须调用同一个 canonicalization 函数后再哈希。可选择全大写展示，也可保持大小写敏感，但两端必须一致并有集成测试。
- 批次只能生成 `duration_days=365`，数据库已有 CHECK；随机码约 120 bit，数据库只存 hash，方向正确。
- 建议权益“保值叠加”：`starts_at = max(now, 用户现有最晚 expires_at)`，`expires_at = starts_at + 365*24h`；这样 trial 中兑换不会损失剩余 trial，多码也不会重叠浪费。
- 叠加计算必须锁用户行或使用按用户 advisory lock；否则两个不同兑换码并发时会从同一基准开始。
- 若业务决定“兑换立即覆盖 trial”或“已有 package 时拒绝”，也可以，但必须在 API、UI 和测试中明确，不能沿用当前隐式重叠行为。
- `ActiveEntitlement` 取最晚到期记录适合读取，但不等同于正确叠加。

最低测试：注册回滚、trial 边界、同邮箱并发、同码并发、不同码同用户并发、大小写/空格、trial 中兑换、已过期权益、grant 不越过 entitlement expiry。

## 7. 反馈数据与 14 天删除

当前实现只限制 `expires_at <= created_at + 14 days`，没有删除动作；这不是“14 天删除”的完整实现。建议采用三层兜底：

1. 对象存储 bucket 默认 private、服务端加密，并设置不可被应用延长的生命周期规则：对象创建后最迟 14 天删除。
2. Cloud API 服务端生成 object key 与 `expires_at`，签发短期、限大小/类型的 PUT URL；客户端不能声明任意已有 key。
3. 定时 sweeper 查询到期、撤回同意或用户主动删除的 artifact：先请求对象删除，再清理/标记数据库记录；失败重试并告警。

同意模型必须修正：当前用户即使后来提交 `granted=false`，仍可引用历史 `granted=true` consent 创建 artifact。MVP 可维护单行“当前 consent 状态 + version + granted_at/revoked_at”，artifact 固化 consent version；撤回后禁止新上传，并立即排队删除关联对象。

建议删除状态：`active -> delete_pending -> deleted`，记录 `deletion_requested_at/deleted_at`，但不在 tombstone 保存内容、原文或可恢复 URL。对象存储生命周期是最终兜底，数据库 job 提供可观测性。备份也需定义最长保留及不可单独恢复已删除内容。

默认不保存 PCM、字幕或翻译正文。反馈必须逐次明示范围；日志和 tracing 不得成为绕过 14 天策略的第二份内容副本。

## 8. 安全清单

现有可保留：

- [x] Argon2id 密码哈希与长度限制。
- [x] access/translation 签名 secret 分离，refresh token 旋转并只存 hash。
- [x] JSON unknown-field 拒绝、body limit、HTTP 超时、统一非详细 5xx。
- [x] 精确 CORS allowlist、`nosniff`、`no-store`、request ID。
- [x] localhost Agent 默认绑定 `127.0.0.1`、检查 Origin、PCM 队列有界。
- [x] 供应商凭据不进入 Browser，Agent 日志不记录正文或 PCM。

外部试点前必须完成：

- [ ] 全链路 HTTPS/WSS；入口代理只信任明确的 upstream，并清洗伪造的 `X-Forwarded-For`。
- [ ] translation grant 强验证、单次 consume、会话最大时长和服务间认证。
- [ ] 生产供应商 Key 仅存在云端 Secret Manager；不打包到客户端/local Agent。
- [ ] 登录、注册、refresh、兑换和管理员接口使用分布式限流；当前内存 limiter 多实例不一致且 map 无清理。
- [ ] 登录未知账号执行 dummy Argon2 校验；响应、状态和时序尽量一致。
- [ ] 管理员 MFA/再认证、最小权限、admin 建立流程及兑换码导出审计。
- [ ] 数据库 TLS、最小权限运行账号、加密备份、恢复演练和迁移回滚演练。
- [ ] 私有对象存储、短期预签名、上传大小/MIME 校验、14 天 lifecycle 与删除告警。
- [ ] 日志字段 allowlist；禁止 email、token、兑换明文、PCM、字幕、对象签名 URL。
- [ ] 依赖/镜像扫描、固定版本、SAST、secret scanning 和发布产物 SBOM。
- [ ] auth、权限绕过、并发兑换、token replay、反馈撤回/删除的集成测试。

后续增强：刷新 token 设备管理、异常登录通知、细粒度审计、WAF/滥用检测、多区域 key 轮换和独立安全评估。

## 9. MVP 取舍

### MVP 保留

- Go + REST JSON + Postgres 单区域部署。
- email/password、15 分钟 access、30 天旋转 refresh。
- 注册即一次 72 小时 trial；管理员生成一次性 365 天兑换码。
- Cloud API 控制面与云端 translator-agent 数据面分进程部署。
- 每个翻译 session 一个 WSS；PCM 只在内存中经过数据面。
- 明示同意后才创建反馈 artifact，服务端强制最多 14 天。
- 基础管理员批次、用户和审计查询；不做完整运营后台。

### MVP 明确延期

- 真实短信、社交登录、支付/订阅、退款、复杂套餐和精细余额扣减。
- 多区域 active-active、事件总线、数据仓库、复杂风控模型。
- 历史翻译、录音回放、AI 总结、全文搜索和内容训练管线。
- RLS、完整 ABAC、终端设备远程管理和高级审计 UI。
- localhost Agent 自动更新与面向终端用户的服务方 Key 下发。

### 不可延期的上线门槛

1. 决定生产数据面部署位置，消除客户端长期供应商 Key。
2. 接通 translation grant 验证/consume，并把 usage 改为数据面权威上报。
3. 修复兑换 canonicalization，明确并实现 72 小时/365 天叠加语义。
4. 建立反馈撤回、主动删除、定时删除和 bucket lifecycle 闭环。
5. 补数据库复合归属约束、唯一约束、迁移和并发集成测试。
6. 完成 TLS、Secret Manager、分布式 auth 限流和管理员保护。

## 10. 推荐实施顺序与验收

1. **P0 边界**：确定云端 translator-agent；定义 WSS authorize/start 协议和非对称 translation grant。
2. **P0 权益**：修兑换大小写、叠加事务与约束；补 entitlement/session 状态。
3. **P0 隐私**：对象存储生命周期、服务端 key、consent 撤回和 delete worker。
4. **P0 信任**：internal consume/usage API、mTLS 或 workload identity、单次 session 状态机。
5. **P1 加固**：分布式限流、key rotation、管理员 MFA、日志 allowlist、备份恢复。
6. **P1 契约**：OpenAPI、错误码表、幂等规则、SDK/客户端安全存储说明。

MVP 架构验收应证明：无权益无法获得 grant；grant 不可重放；用户不能伪造权威 usage；不同用户的 entitlement/consent/session 不能交叉引用；同一码并发只成功一次且同用户重试幂等；trial 与多码叠加不丢时长；反馈撤回后不能上传且对象最迟 14 天不可访问、不可恢复；供应商 Key、token、PCM 和字幕均不出现在客户端包、URL、日志或数据库中。
