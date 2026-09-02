# 本地服务器虚拟机需求（中等预算混合部署）

**用途：** 承载翻译业务核心、Cloud API、翻译 Agent 与 PostgreSQL；通过主动加密隧道接受 AWS 新加坡入口转发。

## 1. 业务范围

- Go Cloud API：账户、权益、使用统计、翻译会话授权。
- 翻译 Agent：实时音频/翻译 Provider 编排。
- PostgreSQL：用户、权益、refresh token、会话与使用记录。
- 不承载公网 HTTPS；不直接暴露数据库、Agent 或 API 端口。
- 第一期不部署短信验证码、Redis、Cognito 或手机号验证服务。

## 2. 建议规格

| 项目 | 中等预算建议 | 说明 |
|---|---|---|
| 虚拟机 | 8 vCPU / 16 GB RAM | 500 初始注册用户、轻量 API、有限实时翻译并发的起点。 |
| 磁盘 | 250 GB NVMe/SSD，RAID/快照优先 | 数据库、日志、容器镜像与本地备份。 |
| 网络 | 上行稳定 ≥100 Mbps；低丢包；公网出口稳定 | 实时语音与加密隧道质量关键。 |
| 操作系统 | Ubuntu Server 24.04 LTS 或等价受支持 Linux | 最小化安装、自动安全更新。 |
| 电力 | UPS ≥30 分钟；建议双网络或 4G/5G 备用 | 降低本地断电/断网导致的服务中断。 |
| 备份 | 异机/对象存储每日加密备份，保留 30 天 | 定期做恢复演练。 |

## 3. 网络与安全边界

```text
移动端 → AWS HTTPS/WAF → 加密隧道（本地主动建立） → Cloud API / Agent
                                                   └→ PostgreSQL（仅本机私网）
```

- 本地**不开放入站公网端口**给 API、Agent、PostgreSQL。
- 只允许本机出站至 AWS 隧道端点、必要 Provider、系统更新和备份目的地。
- PostgreSQL 仅监听 loopback/本地私网；禁止 `0.0.0.0` 与公网安全组暴露。
- 使用 WireGuard 优先；可选 FRP/Cloudflare Tunnel，但必须使用强认证、TLS、最小权限和连接存活监控。
- Cloud API、Agent、数据库各自运行在独立 systemd service 或容器；最小 Linux 用户权限。
- 密钥、DSN、JWT key 放在受限权限文件或专用 secret store；权限 `600`，不得写日志、提交 Git 或发送到聊天工具。

## 4. 部署与运行要求

- Cloud API 使用 systemd 自动启动、失败自动重启、健康检查 `/readyz`。
- Agent 与 Cloud API 分别设置 CPU/内存限制、重启策略和健康探针。
- 数据库使用版本化 up migration；生产禁止 down migration。
- 每次发布：备份/快照 → 健康检查 → 小流量验证 → 监控 30 分钟 → 可回滚镜像/二进制。
- 翻译 Provider 凭据仅在本地 secret 文件中提供给 Agent；不得进入 API 响应或日志。

## 5. PostgreSQL 运维

- 初期单实例 PostgreSQL 17，使用受控版本化 migration。
- 每日逻辑备份 + 文件系统/卷快照；备份加密且放异机/对象存储。
- 至少每月一次从备份恢复到隔离环境验证。
- 监控连接数、慢查询、磁盘利用率、WAL、备份成功率与恢复时间。
- 账户中心、权益和 refresh token 数据是高优先级备份对象；不存音频二进制。

## 6. 监控与告警

- 主机：CPU、RAM、磁盘、温度（可获取时）、网络丢包/延迟、UPS 状态。
- 服务：Cloud `/readyz`、Agent 连接、隧道在线、PostgreSQL 可用、备份结果。
- 告警：隧道断开、健康检查失败、磁盘 >80%、备份失败、重复服务重启、异常 5xx。
- 日志脱敏：禁止完整手机号、邮箱、密码、token、验证码、DSN、授权头、Provider key。

## 7. 容量与升级触发条件

以下任意条件出现时，评估迁移数据库或 API 至 AWS：

- 峰值实时翻译并发 >20；
- 本地上行长期 >60% 或频繁丢包；
- 单机 CPU/RAM 长期 >60%；
- 每月出现不可接受的断电/断网中断；
- 日活 >1,000 或对外承诺可用性 SLA；
- 数据恢复演练无法满足业务恢复目标。

## 8. 交付验收

- 本地无公网开放 PostgreSQL/API/Agent 端口。
- 加密隧道重连可用，AWS 健康检查可识别本地离线。
- Cloud API/Agent/systemd 在重启后自动恢复。
- 备份成功且恢复演练记录可用。
- 安全扫描、更新策略、日志脱敏和告警渠道完成。
