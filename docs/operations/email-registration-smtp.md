# 邮箱验证码 SMTP 运维手册

本手册适用于单台 EC2 上的 Cloud API 邮箱验证码注册。SMTP 只接受本机 Cloud API 的提交；它不是公网邮件提交服务。

## 安全边界

- PostgreSQL 仅使用 `127.0.0.1:15432`；禁止改回或开放 `5432`。
- Postfix 的 `inet_interfaces` 必须为 `loopback-only`，并以 systemd 管理。
- Cloud API 生产环境只连接 `SMTP_HOST=127.0.0.1`、`SMTP_PORT=25`。
- 安全组和主机防火墙不得开放入站 TCP `25`、`465`、`587`；公网仅允许 Caddy 的 `80`、`443`。
- 保持 Postfix 的默认非转发边界：禁止任意外部/非本地目的地 relay。不要为“解决投递问题”放宽 `mynetworks`、`relay_domains` 或启用公网 submission。
- Postfix 配置、邮件队列、日志、环境文件、数据库备份和私钥均不得提交到 Git 或复制到工单。

## Cloud API 私有环境变量

`/etc/dngmeng/cloud-api.env` 由 root 管理，权限为 `640`，不得在终端、日志或版本库显示其内容。启用验证码注册时应配置：

```ini
EMAIL_VERIFICATION_ENABLED=true
SMTP_HOST=127.0.0.1
SMTP_PORT=25
SMTP_FROM=<经批准的发件人地址>
SMTP_CONNECT_TIMEOUT=5s
SMTP_SEND_TIMEOUT=10s
EMAIL_VERIFICATION_RATE_LIMIT_SECRET=<至少 32 字节的独立随机值>
```

`EMAIL_VERIFICATION_RATE_LIMIT_SECRET` 必须与 token 密钥不同。使用 root 的安全随机源直接在 EC2 写入；不要经过本地 shell 历史、聊天、Git 或远程命令参数传递。修改前先以 root 创建带时间戳的、受限权限的备份。重启前通过 `systemd` 的环境文件读取方式验证权限和语法，不打印变量值。

## Postfix 配置与验收

1. 安装并启用发行版的 `postfix.service`，不用容器或自建监听包装器。
2. 通过 root 受限备份保存 `/etc/postfix/main.cf` 和相关 service 配置后，设置 `inet_interfaces = loopback-only`；保持 `inet_protocols = all` 仅在 IPv4、IPv6 回环均受限时可接受。若环境不需要 IPv6，显式使用 `inet_protocols = ipv4`。
3. 保持 `mynetworks` 仅包含 loopback，并确认 `smtpd_recipient_restrictions`（或发行版等价规则）拒绝非授权目的地 relay。
4. 重启后用 `ss -ltnp` 检查 TCP `25` 仅绑定 `127.0.0.1` 或 `::1`；不得有 `0.0.0.0:25`、`[::]:25`、或任何 `465`/`587` 监听。
5. 使用 `postconf -n`、`systemctl is-active postfix`、`postqueue -p` 和脱敏的 `journalctl` 检查状态。禁止把邮件正文、完整收件人、队列 ID 或配置机密复制出主机。
6. 从 loopback 进行 SMTP 连通性检查，并验证非本机来源不能连接；同时复核 AWS Security Group 和主机防火墙中未允许入站 `25`、`465`、`587`。

若 Postfix 接受本机邮件但上游投递失败，Cloud API 必须将其视为可重试发送失败；绝不能在 API、Android UI 或日志中回显验证码。

## 部署和回滚

1. 记录当前 `cloud-api.service` 状态，备份当前 binary、unit override/unit file 和环境文件到 root-only、带时间戳的目录。
2. 在 EC2 构建经审阅提交的 Cloud API binary；构建失败不触碰运行中的 binary。
3. 在执行前创建数据库的 root-only 一致性备份/快照；migration 命令仅允许 `up`，并使用 EC2 的 `127.0.0.1:15432` 数据库连接。不得执行 down migration。
4. 原子替换 binary，写入受限环境文件后重启 `cloud-api.service`。依次确认 systemd 活跃、本机 `/healthz`、再确认经 Caddy 的公网 HTTPS health/API 路径。
5. 若 binary 启动、migration 或 health 检查失败，立即恢复已备份 binary 与 service/environment 配置，并重启恢复服务。对已应用的 migration 不执行自动 down；保留备份并按变更管理流程处置。

## 投递 smoke 与日志卫生

仅当 EC2 已存在经批准的、受控测试收件人配置时，才可发起一次受控真实邮件 smoke；不得询问、猜测、创建或输出收件人。只记录成功/可重试失败和经过脱敏的 Postfix 状态，不记录验证码、密码、token、DSN、完整邮箱、邮件正文或邮件队列内容。

无域名阶段以 EC2 IP/主机名尝试出站 SMTP。AWS 常限制出站 TCP `25`，且收件方可能拒收、延迟或归入垃圾箱；记录此限制和 Postfix 队列的脱敏状态。若出站 `25` 不可用，保持本机安全边界不变，并将其作为投递未就绪状态处理。

## 有域名后的切换

获得域名后，先在变更窗口内：

1. 设置正式 `myhostname` 与批准的发件地址；
2. 发布 SPF；
3. 部署 DKIM 签名；
4. 发布 DMARC；
5. 向 IP 提供方申请 PTR；
6. 使用受控收件人复测投递、垃圾箱表现、队列和 API 可重试失败路径。

这些步骤不改变验证码 API、数据库迁移、限流或 loopback-only SMTP 边界。
