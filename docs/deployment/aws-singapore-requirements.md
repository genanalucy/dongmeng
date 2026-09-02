# AWS 新加坡云入口需求（中等预算混合部署）

**区域：** `ap-southeast-1`（Singapore）  
**用途：** 提供全球公网 HTTPS 入口、TLS/WAF/限流、静态 IP、监控和到本地虚拟机的加密隧道终止；业务核心仍在本地 VM。

## 1. 推荐架构

```text
Android / 全球用户
        │ HTTPS 443
        ▼
Route 53 / DNS → CloudFront（可选） → AWS WAF → EC2 反向代理
                                                    │ 加密 WireGuard 隧道
                                                    ▼
                                      本地 VM：Cloud API / Agent / PostgreSQL
```

- 第一期采用**单台 EC2 入口**，不采用 ALB、NAT Gateway、ECS、RDS、ElastiCache、Cognito 或 SMS。
- EC2 仅反向代理健康的 Cloud API 请求；不得持有 PostgreSQL 或 Provider 数据。
- 本地 VM 主动连接 AWS，AWS 不主动访问家庭/办公网络公网端口。
- 未来拆分：API 迁 ECS/Fargate、数据库迁 RDS、实时 Agent 保留本地或独立扩展。

## 2. 建议资源与规格

| AWS 资源 | 中等预算建议 | 用途 |
|---|---|---|
| EC2 | `t4g.small`（2 vCPU / 2 GiB） | Nginx/Caddy、WireGuard、基础监控代理。预留性能瓶颈时升级 `t4g.medium`。 |
| EBS | gp3 30 GB | 系统、代理日志、短期诊断数据。 |
| Elastic IP | 1 个 | 稳定入口 IP，绑定 EC2。 |
| Route 53 | 托管域名记录 | 域名解析和健康/切换准备。 |
| ACM | 公共 TLS 证书 | HTTPS，证书本身免费。 |
| WAF | 1 Web ACL + 基础托管规则 | 限制常见攻击、速率规则、地理/机器人策略按需启用。 |
| CloudWatch | 日志、指标、告警 | 健康、隧道、实例、代理 4xx/5xx。 |
| S3 | 私有备份/日志归档桶 | 仅用于本地备份副本与审计归档；生命周期策略。 |
| Secrets Manager 或 SSM Parameter Store | 少量 secret | 隧道密钥/代理认证配置；不保存本地数据库副本。 |

## 3. 网络要求

### VPC

- 1 个 VPC，至少 2 个 AZ 的公共子网（当前单 EC2 可在一个 AZ，预留迁移能力）。
- 初期不创建 NAT Gateway；EC2 在公共子网使用 Elastic IP。
- 不创建 RDS/私有数据库子网，避免第一期固定成本。

### Security Group

- 入站：仅 `443/tcp` 面向互联网；`80/tcp` 仅用于 ACME 重定向（如需要）。
- SSH：默认关闭公网 SSH；使用 AWS Systems Manager Session Manager。必要时临时限固定管理 IP。
- WireGuard UDP 端口仅对本地 VM 的稳定出口 IP 放行；若出口 IP 不固定，使用隧道方案的强认证与额外访问控制。
- 出站：仅系统更新、AWS 服务、本地隧道对端和必要运维目标；禁止宽泛管理端口。

## 4. 入口、TLS 与防护

- 反向代理使用 Nginx 或 Caddy，强制 HTTPS、HSTS、TLS 1.2+、安全 header。
- 代理 WebSocket/流式请求时，显式设置超时与 upgrade header；实时翻译路径需压测。
- 只将通过 WireGuard 的私网地址作为 upstream；本地隧道不可用时返回统一 `503`，不暴露内网地址或调试信息。
- WAF 至少启用 AWS 托管核心规则、IP reputation、合理速率限制；登录/注册/验证码（未来）使用更严格限额。
- Cloud API 的应用级限流与 AWS WAF 限流双层保留。

## 5. 身份、机密与运维

- EC2 使用 IAM Role，最小权限访问 CloudWatch、SSM、指定 Secrets Manager secret、指定 S3 bucket。
- 禁止长期 AWS access key 落盘；管理员使用 IAM Identity Center/MFA。
- 不将数据库 DSN、JWT secret、Provider key 复制到 EC2；入口只保存隧道/代理所需的最小 secret。
- 使用 SSM Patch Manager 或自动化更新窗口；镜像/配置版本化并可回滚。
- CloudWatch Logs 保留 30 天（初期）；S3 归档 90 天后转低成本存储，按合规调整。

## 6. 监控、告警与灾备

- 告警：EC2 状态失败、CPU/磁盘、TLS 续期、WAF 阻断异常、代理 5xx、Cloud API `/readyz`、隧道断开。
- CloudWatch Synthetics 或外部探针：每分钟访问公开健康端点；不含认证信息。
- 入口实例故障时：允许人工替换 EC2 + 重新绑定 Elastic IP；记录恢复步骤与目标恢复时间。
- 本地 VM 仍为单点：AWS 入口无法消除本地断网/断电风险，必须告知业务方。

## 7. 第一阶段不部署的项目

- Amazon Cognito、AWS End User Messaging SMS、Amazon SNS/SES 验证码。
- RDS、ElastiCache、ECS/Fargate、ALB、NAT Gateway。
- 多区域、Multi-AZ 自动故障转移。

手机号/SMS 由未来用户量和目标国家合规决定；当前认证采用用户名 + 邮箱 + 密码，登录支持用户名/邮箱。

## 8. 预算（新加坡，中等预算粗估）

| 项目 | 估算 USD/月 |
|---|---:|
| EC2 `t4g.small` + 30GB gp3 | $20–30 |
| Elastic IP / 公网 IPv4 / 少量流量 | $5–15 |
| Route 53 / DNS / ACM | $1–3 |
| WAF 基础规则与请求量 | $8–25 |
| CloudWatch、S3、Secrets/SSM | $8–25 |
| 预留的流量/日志波动 | $10–25 |
| **AWS 云入口合计** | **约 $52–123/月** |

- 不含域名注册、GST、翻译/语音 Provider、第三方隧道服务及本地服务器成本。
- AWS 新加坡通常比美国低价区域高；上线前必须在 AWS Pricing Calculator 以 `ap-southeast-1` 和实际流量复核。
- 设置 AWS Budgets：月度 $100、$150、$200 分级告警（金额可由业务方调整）。

## 9. 后续升级路径

| 触发条件 | 升级 |
|---|---|
| 本地 API/数据库可用性不足 | API 先迁 ECS Fargate，RDS PostgreSQL Single-AZ。 |
| 需要生产高可用 | ALB + ECS 双任务跨 AZ + RDS Multi-AZ。 |
| 登录/验证码防刷需求上升 | Redis、AWS End User Messaging SMS/SES、验证码 hash 与限流。 |
| 全球内容/下载流量上升 | CloudFront、WAF 扩展与区域化策略。 |

## 10. 交付验收

- DNS/HTTPS/WAF/反向代理部署，公网只暴露必要端口。
- EC2 不保存数据库和 Provider secret；IAM 最小权限/MFA/SSM 可用。
- WireGuard 隧道重连、健康检查、反向代理故障 `503` 已验证。
- 日志脱敏、CloudWatch 告警、预算告警、备份桶生命周期配置完成。
- 记录部署、回滚、紧急断开隧道和替换入口实例的操作手册。
