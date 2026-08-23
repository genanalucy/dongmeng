# Translation session WebSocket authorization

Agent 的 translation-session JWT 鉴权是显式启用的生产安全边界。`server.Options.SessionVerifier == nil` 时保持当前 loopback 开发行为，不要求 token；生产部署必须通过配置构造并注入 verifier，不能把 Origin allowlist 当作身份认证。

## 信任契约

Agent 当前只接受 `HS256`，并严格验证：

- JOSE header `typ=translation_session`；
- 配置的 `iss` 与唯一 `aud`；
- `sub`、`user_id`、`session_id`、`install_id`；
- `exp`、`iat`、最大 token 寿命与时钟偏差；
- 首个 `start` 消息中的 user/session/install 与已签名 claims 完全一致。

`TRANSLATION_SESSION_ISSUER` 和 `TRANSLATION_SESSION_AUDIENCE` 必须与签发服务约定的精确值一致，不能使用面向 access token 的其他 audience。`TRANSLATION_SESSION_HS256_KEY` 必须来自与签发方一致的部署 Secret，且至少 32 bytes；它不是 Provider API Key，不得下发给浏览器或移动客户端。

当前 verifier 仅支持一个 HS256 key，不支持 `kid` 或新旧 key 并行验证。密钥轮换必须由签发方与全部 Agent 实例协调完成；需要无中断双 key 窗口时，应先实现 key ring/`kid`，不能靠同时配置多个环境值模拟。

## 开发默认行为

`TRANSLATION_SESSION_AUTH_ENABLED` 缺失、空字符串或去除首尾空白后为 `false` 时，鉴权关闭。关闭状态不会读取 key、issuer、audience 或时间参数，现有本地客户端可不携带 subprotocol 和 auth-only `start` 字段继续连接。

该默认值只用于本机开发兼容。公网或生产 Agent 在鉴权关闭时会接受允许 Origin 的未认证连接，因此不得把默认关闭当作安全的生产配置。

## 生产启用配置

部署系统应通过 Secret Manager、容器 Secret 或同等受控机制注入下列变量；不要把值写入 Git、镜像、命令行参数、部署日志或普通配置文件。

| Name | Required | Meaning |
|---|---:|---|
| `TRANSLATION_SESSION_AUTH_ENABLED` | yes | 去除首尾空白后必须为 `true`；其他非空值启动失败 |
| `TRANSLATION_SESSION_HS256_KEY` | yes | 与签发方一致的 HS256 key，至少 32 bytes |
| `TRANSLATION_SESSION_ISSUER` | yes | 精确匹配 token `iss`，不允许首尾空白 |
| `TRANSLATION_SESSION_AUDIENCE` | yes | token 唯一允许的 `aud`，不允许首尾空白 |
| `TRANSLATION_SESSION_CLOCK_SKEW_SECONDS` | no | 非负整数；默认 `30`，`0` 表示不容忍偏差 |
| `TRANSLATION_SESSION_MAX_LIFETIME_SECONDS` | no | 正整数；默认 `300` |

启用但缺少必需项、key 太短、布尔值非法或 duration 非法时，Agent 在监听端口前以 `INVALID_SESSION_AUTH_CONFIGURATION` 退出。错误不会包含 key。

建议上线顺序：

1. 确认签发方已经按上述 header/claims 契约签发短期 token。
2. 通过受控 Secret 渠道向所有 Agent 实例发布相同的 key、issuer 和 audience，但暂不把公网流量切入未验证实例。
3. 客户端先具备下述 subprotocol 和 `start` 绑定字段支持。
4. 在隔离环境验证成功、缺 token、错签名、过期和绑定不匹配路径。
5. 以滚动或蓝绿方式启用；负载均衡池中不要长期混合“鉴权开启”和“鉴权关闭”实例。
6. 检查拒绝率、时钟同步和代理 header 脱敏，再扩大流量。

`GET /api/health` 只表示进程存活，不证明 session JWT 配置正确；生产探针应另有带测试签发链路的受控合成检查，但不得记录测试 token。

## Browser-safe token transport

鉴权开启时，客户端在 `Sec-WebSocket-Protocol` 中提供两个值：

```text
translation.v1, translation.jwt.<compact-JWT>
```

浏览器示例：

```js
new WebSocket(url, ["translation.v1", `translation.jwt.${token}`]);
```

Agent 在 upgrade 前提取 credential，随后从请求中移除 credential-bearing protocol，并只协商 `translation.v1`；响应不会回显 JWT。不要使用 query string、cookie 或 `Authorization` 作为旁路，当前实现只接受文档化的 subprotocol 方式。

反向代理、Ingress、APM 和 tracing 在请求到达 Agent 前仍能看到原始 `Sec-WebSocket-Protocol`，必须对整个入站 header 做脱敏。禁止记录请求 header 全量、token 任一完整段或包含 token 的连接错误。

鉴权开启时，首个 `start` 消息还必须提供与 signed claims 绑定的上下文：

```json
{
  "type": "start",
  "sessionId": "123e4567-e89b-12d3-a456-426614174000",
  "userId": "user-123",
  "installId": "install-456",
  "mode": "s2s",
  "sourceLanguage": "zh",
  "targetLanguage": "en",
  "targetAudioFormat": "pcm",
  "targetAudioRate": 16000
}
```

验证成功前 Agent 不启动 Provider。token 只应保存在客户端短期内存中；连接失败后应获取新 session token，不要长期持久化或反复重放旧 token。

## 错误与排障

| Stage | Result | Meaning |
|---|---|---|
| 非允许 Origin | HTTP `403`，不 upgrade | Origin 检查先于 token 处理 |
| 缺失或 transport 格式错误 | HTTP `401`，不 upgrade | 未提供两个规定的 subprotocol，或 compact JWT 外形无效 |
| JWT 签名/claims/绑定错误 | WS event `TRANSLATION_AUTH_INVALID` 后关闭 | 已 upgrade，但 verifier 拒绝；Provider 不会启动 |
| 启用配置错误 | 进程启动失败 | 检查变量是否存在和契约是否一致，不打印变量值 |

对外错误故意不区分错签名、过期、issuer/audience 或具体绑定字段，避免泄露验证细节。排障应使用固定错误码、部署版本和聚合指标，不应临时打开 token/header 日志。

常见检查顺序：主机时钟同步、enabled 状态、issuer/audience 精确值、签发与验证 key 是否属于同一版本、客户端 subprotocol、`start` 绑定字段，以及代理是否保留 WebSocket subprotocol 协商。

## 回滚与密钥轮换

安全回滚优先顺序：

1. 停止新流量或回切到上一版“仍开启鉴权”的已知良好 Agent。
2. 回滚签发方或客户端到与当前 Agent trust contract 兼容的版本。
3. 修复配置后滚动重启，并用受控 token 验证。

把 `TRANSLATION_SESSION_AUTH_ENABLED=false` 只作为隔离网络内的紧急开发回退；它会重新允许未认证 WebSocket，属于安全降级，不是生产常规回滚。若不得不使用，必须先从公网/生产负载均衡移除实例、限制 Origin/网络入口、记录审批，并在恢复鉴权后重新验证。

单 key 轮换期间，新签发 key 在旧 Agent 上会全部失败，旧 token 在新 Agent 上也会失败。当前可选方案是短暂停发/排空新会话后原子切换并滚动重启；若业务要求无中断轮换，应先增加 `kid` 与双 key 验证窗口，再执行轮换。
