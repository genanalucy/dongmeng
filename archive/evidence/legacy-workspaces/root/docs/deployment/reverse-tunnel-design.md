> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `docs/deployment/reverse-tunnel-design.md`；原始 SHA-256 `ae128dc4e88eb5a8849191f36d50b08d59ef08af222e3e474e2bd8db10971e5b`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 反向隧道部署设计（本地 VM → AWS EC2）

## 1. 结论

采用 **FRP TCP 反向隧道 + TLS + 强随机 token**。本地 VM 上的 `frpc` 主动通过唯一允许的出站端口 `443/tcp` 连接 EC2；EC2 上 Caddy 仍是唯一业务 HTTPS 终止点，只把公开 Cloud API 路径转发到一个仅监听 loopback 的 FRP 远端端口。

由于 Caddy 与 `frps` 不能在同一 IP 上同时直接绑定 `443`，在 EC2 最前端使用 **HAProxy 的 TCP/SNI 分流**：

- `legacy-service.example.invalid:443` → `127.0.0.1:8443` → Caddy（TLS 由 Caddy 终止）；
- `legacy-service.example.invalid:443` → `127.0.0.1:7000` → `frps`（FRP TLS）；
- FRP 创建的业务远端端口只绑定 `127.0.0.1:18080`，Caddy upstream 为该地址。

公网安全组只开放 `443/tcp`；如 Caddy 需要 HTTP-01 或 HTTP 重定向，可额外开放 `80/tcp`，但优先使用 TLS-ALPN-01/DNS challenge，从而保持只开放 443。不得建立 PostgreSQL、Translator Agent、Vite、管理接口或 debug 端口的 FRP proxy。

## 2. 已检查的当前状态

### 2.1 现有本地 FRP 配置

文件：`[旧工作区路径]`

已确认：

- `frpc` 当前连接一个外部地址的 `443/tcp`；
- 已启用 FRP transport TLS 和 token 认证；
- 当前定义了三个临时 TCP proxy，分别指向本机 `5173`、`18765`、`8080`；
- 配置中存在明文弱 token。本文不复制地址、token 或其他秘密。

该配置只能视为临时调试配置，不应原样用于生产。尤其是 `5173`（Vite）、`18765`（Translator Agent）和原始 `8080` debug 映射必须删除，不能在 EC2 上形成公网监听端口。

### 2.2 当前 Cloud API 运行启动配置名称

本机现有 systemd user unit：

- unit：`dngmeng-cloud-api.service`
- `EnvironmentFile`：`%h/.cache/dngmeng/cloud-api.dev.env`
- `ExecStart`：`%h/.local/lib/dngmeng/cloud-api`

Cloud API 当前代码识别的运行时配置名：

- `APP_ENV`
- `HTTP_ADDRESS`
- `DATABASE_URL`
- `JWT_ISSUER`
- `JWT_AUDIENCE`
- `JWT_ACCESS_SECRET`
- `JWT_SESSION_SECRET`
- `CORS_ALLOWED_ORIGINS`
- `HTTP_BODY_LIMIT_BYTES`

TTL 在当前实现中不是环境变量，而是代码默认值。生产部署应令 `HTTP_ADDRESS=127.0.0.1:8080`；环境文件保持仅服务账号可读，不复制到 EC2。EC2 不需要也不得持有 `DATABASE_URL`、JWT secret 或 Provider credential。

### 2.3 项目部署约束

下列文档一致要求本地主动建隧道、EC2 只做入口，且数据库/API/Agent 不直接公网暴露：

- `README.md`
- `docs/deployment/local-vm-requirements.md`
- `docs/deployment/aws-singapore-requirements.md`
- `cloud-api/README.md`

原 AWS 文档偏向 WireGuard；但本地网络只允许出站 TCP 80/443，不能依赖 WireGuard UDP，因此本设计改用 FRP/TLS over TCP 443。

## 3. 精确端口与进程设计

```text
Internet client
  HTTPS legacy-service.example.invalid:443
        |
        v
EC2 HAProxy :443 (TCP mode, inspect TLS ClientHello SNI)
        |
        +-- SNI legacy-service.example.invalid
        |      -> 127.0.0.1:8443 Caddy
        |              -> http://127.0.0.1:18080
        |                       ^
        |                       | FRP proxy endpoint; loopback only
        |
        +-- SNI legacy-service.example.invalid
               -> 127.0.0.1:7000 frps
                         ^
                         | TLS FRP control/data connection
                         | outbound TCP 443
Local VM                 |
  frpc ------------------+
    -> 127.0.0.1:8080 dngmeng-cloud-api.service
    -> no other proxy

Local VM PostgreSQL -> loopback/Unix socket only; never enters tunnel
Translator Agent    -> private/local only; never enters this tunnel
```

### EC2

| 进程 | 监听地址 | 用途 |
|---|---|---|
| HAProxy | `0.0.0.0:443`（以及 IPv6 等价地址，如启用） | 按 TLS SNI 做 TCP passthrough；不解密、不记录认证 header |
| Caddy | `127.0.0.1:8443` | 为业务域名终止 HTTPS，设置安全 header、限流/请求约束并代理 Cloud API |
| `frps` | `127.0.0.1:7000` | FRP 控制与数据面；由 HAProxy 的 tunnel SNI 分支抵达 |
| FRP remote proxy | `127.0.0.1:18080` | 仅供 Caddy 使用的 Cloud API upstream |

`frps` 必须配置 proxy bind address 为 loopback。不能使用 `0.0.0.0:18080`，EC2 Security Group 也不能开放 `18080`、`7000` 或 `8443`。

HAProxy 必须采用 fail-closed ACL：只接受上述两个完整 SNI；无 SNI、未知 SNI 或 ClientHello 检查超时直接拒绝。FRP 客户端应发送 `legacy-service.example.invalid` 作为 TLS server name，并关闭会破坏标准 TLS ClientHello/SNI 识别的 FRP custom TLS first byte（具体键名随所固定 FRP 版本确认，现代版本通常为 `transport.tls.disableCustomTLSFirstByte = true`）。部署前固定相同的 `frpc`/`frps` 版本并验证该选项。

### 本地 VM

| 进程 | 监听/连接 | 用途 |
|---|---|---|
| `dngmeng-cloud-api.service` | `127.0.0.1:8080` | 唯一被隧道代理的应用服务 |
| `frpc` systemd service | 出站 `legacy-service.example.invalid:443` | 建立一个名为例如 `cloud-api-production` 的 TCP proxy |
| PostgreSQL | Unix socket 或 `127.0.0.1:5432` | 仅 Cloud API 访问 |
| Translator Agent | loopback/受限私网 | 不经该公网入口暴露 |

FRP proxy 的 `localIP/localPort` 只能是 `127.0.0.1:8080`，`remotePort` 为 `18080`。生产配置不得包含现有临时 proxy 名称或 `5173`、`18765`、`8080` 的公网 remote port。

## 4. Caddy upstream 与公开面

Caddy 业务站点的核心 upstream 应为：

```caddyfile
legacy-service.example.invalid {
    encode zstd gzip

    # 仅发布明确允许的 Cloud API 路径；健康端点可单独决定是否公开。
    handle /api/* {
        reverse_proxy 127.0.0.1:18080 {
            health_uri /readyz
            health_interval 10s
            health_timeout 2s
        }
    }

    respond 404
}
```

以上是设计示例，实际 `/readyz` 是否存在须按部署二进制验证；不存在时使用 Cloud API 已实现的无敏感健康端点，或移除主动健康 URI、改用外部合成探针。Caddy 不应代理以下内容：

- PostgreSQL；
- `translator-agent` 的 health/WebSocket 端口；
- Vite 开发服务器；
- pprof、expvar、debug、admin-only 监听端口；
- FRP dashboard/API；
- secret/config 文件路径。

保留并校验客户端 IP 头：HAProxy 使用 TCP passthrough 时不应伪造 HTTP headers；Caddy 只信任明确配置的上游代理。限制请求体、header 和超时；对登录、注册、refresh、兑换等端点实施应用层与入口层双重限流。隧道断开或 upstream 不健康时统一返回 `503`，不得回显 loopback 地址、FRP 状态或内部错误。

## 5. 控制平面、认证与密钥轮换

### FRP 认证

- 使用至少 32 个随机字节生成的独立 FRP token，不复用 JWT、数据库或 AWS credential。
- token 只能保存在两端 root/专用服务账号可读的 secret 文件（权限 `0600`），不放在 unit、命令行、Git、Caddyfile、日志或本文中。
- `frps` 强制 TLS；`frpc` 校验服务器证书和 hostname。条件允许时使用私有 CA 并固定 CA，而不是关闭证书验证。
- `frps` 禁用 dashboard；如必须启用，仅监听 loopback，并通过 SSM Session Manager 临时访问。
- 限制允许的 proxy 名称和 remote port 范围；只允许 `cloud-api-production`/`18080`。使用专用非登录 Linux 用户和 systemd sandbox。

### 无中断轮换

FRP 单 token 配置通常没有双 token 宽限。采用蓝绿轮换：

1. 在 EC2 启动第二个仅 loopback 的 `frps`（例如 `127.0.0.1:7001`），配置新 token；HAProxy 临时增加新的 tunnel SNI（例如 `tunnel-next...`）到该端口。
2. 本地启动第二个 `frpc`，用新 token 建立第二个 proxy 到另一个 loopback remote port（例如 `18081`）。
3. 验证 `18081` 健康后，将 Caddy upstream 切换到 `18081`，观察错误率。
4. 停止旧 `frpc/frps`，删除旧 token、旧 SNI 和旧 `18080` listener。
5. 记录轮换时间与 token 版本标识，但不记录 token 值。建议每 90 天及人员/主机变更后轮换。

若所固定 FRP 版本支持可靠的 OIDC client-credentials，可接入自管 IdP 取代静态 token；对当前单隧道规模，强随机 token + TLS + loopback/端口 allowlist 的攻击面和运维复杂度更低。

## 6. 健康、重连和告警

- `frpc`、`frps`、HAProxy、Caddy 均由 systemd 管理：`Restart=always`、有界 `RestartSec`，并设置启动频率限制，避免紧密重启循环。
- `frpc` 使用 FRP keepalive；TCP keepalive/heartbeat 参数必须低于中间防火墙的空闲超时。建议约 10 秒 heartbeat、30–60 秒超时，并以所固定版本支持的配置项为准。
- systemd 在 `network-online.target` 后启动 `frpc`；网络恢复后自动重连。DNS 和证书失败不得降级为明文。
- EC2 每 30 秒从 Caddy/loopback 检查 API readiness；公网每分钟检查一个不含版本、依赖或 secret 细节的健康端点。
- 告警至少覆盖：FRP 连接断开、Caddy 连续 `502/503`、公网健康失败、反复重启、TLS 到期、磁盘/日志异常、未知 SNI 激增。
- 日志记录服务状态、proxy 名称、连接时间和聚合错误；不得记录 token、Authorization、JWT、请求体、DSN、Provider key 或完整个人数据。
- 明确接受当前单本地 VM/单 EC2 的单点风险；隧道不能解决本地断电、断网或主机故障。

## 7. 方案比较

| 方案 | 出站仅 443 | 保留 EC2 Caddy 为公网入口 | 安全/可控性 | 运维复杂度 | 结论 |
|---|---:|---:|---|---|---|
| FRP/TLS + SNI 分流 | 是 | 是 | 可严格限制到单一 loopback upstream；需妥善管理 token/版本 | 中等 | **推荐** |
| Cloudflare Tunnel | 是（通常优先 QUIC，受限时回退 HTTP/2 over TCP 443） | 否/不自然 | Cloudflare Access/托管控制面成熟，但 TLS 与公网入口通常在 Cloudflare，EC2 Caddy 会被绕过或成为多余跳点 | 低至中 | 若接受 Cloudflare 成为入口则是优秀替代，不符合当前 EC2 Caddy 拓扑 |
| SSH reverse tunnel | 是（需让 SSH 抵达 443） | 是 | 公钥认证成熟，可用 `PermitOpen`/受限用户；但同样需要 SNI/TLS 封装才能与 Caddy 共用 443 | 中至高 | 可作应急替代，不推荐作为首选生产数据面 |

### Cloudflare Tunnel

优点是无需 EC2 入站隧道 listener、自动重连、短生命周期凭据和托管控制平面。缺点是公开 hostname 通常直接由 Cloudflare Edge 转到本地 `cloudflared`，不再经过 EC2 Caddy；若再绕回 EC2 会增加没有必要的跳数与故障域。它还引入第三方控制面、账户/API token 和供应商依赖。只有在明确决定“Cloudflare 取代 EC2 Caddy/WAF 作为公网入口”后再选。

### SSH reverse tunnel

不得把裸 `sshd` 与 Caddy 同时绑定同一 IP 的 443。若采用，应在 HAProxy 前端使用 TLS/SNI 封装（例如专用 TLS wrapper），再转给 loopback `sshd`；使用专用无 shell 用户、`AllowTcpForwarding remote`、禁止 PTY/agent/X11、限制可监听地址和目标，并使用短期 SSH 证书。`autossh` 或 systemd 可重连。相比 FRP，这需要额外 TLS wrapper/证书和更细的 sshd 限制，且反向转发误配置更容易产生通用跳板。

## 8. 部署验收清单

1. EC2 Security Group 从公网只见 `443/tcp`（及确有需要的 `80/tcp`）；`7000/7001/8443/18080/18081/5432/8080/18765/5173` 均不可公网连接。
2. HAProxy 对未知/无 SNI fail closed；业务与隧道 hostname 分流正确。
3. Caddy 是业务 HTTPS 终止点，证书链和自动续期正常，upstream 仅为 FRP loopback 端口。
4. 本地 `frpc` 只有 Cloud API 单 proxy；PostgreSQL、Agent、Vite、debug/admin 端口没有 tunnel 定义。
5. Cloud API 只监听 loopback；PostgreSQL 只监听 loopback/Unix socket。
6. EC2 上不存在数据库 DSN、JWT secret、Provider key或 Cloud API 环境文件副本。
7. 停止 `frpc` 后公网快速返回统一 `503`；恢复网络后自动重连并恢复服务。
8. token 轮换演练、服务重启演练、TLS 续期检查和告警均通过。
9. FRP/Caddy/HAProxy 配置中无 secret，secret 文件权限为 `0600`，日志抽查无敏感字段。

## 9. 版本与上线前确认

FRP 配置键在不同版本间有变化。上线前固定并记录 `frpc`/`frps` 相同版本，依据该版本官方文档确认：TLS server name、证书校验、custom TLS first byte、proxy bind address、remote-port allowlist、heartbeat 和 token secret-file 的准确键名。不要为“先跑通”而关闭 TLS 验证、扩大 remote port 范围或监听 `0.0.0.0`。
