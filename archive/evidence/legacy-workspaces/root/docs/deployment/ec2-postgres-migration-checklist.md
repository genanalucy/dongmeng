> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `docs/deployment/ec2-postgres-migration-checklist.md`；原始 SHA-256 `6a293851816b045d1f4583c0b07386458bf9081b5b03c6eed0e69b86a945445e`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# EC2 PostgreSQL 迁移与 S3 备份检查清单

> 只读审计结论；不含 DSN、凭据、主机名、账号 ID 或其他秘密值。EC2 SSH 与 AWS 实机检查本次未能完成，相关项必须在迁移前由运维只读验证。

## 已确认的项目事实

- 数据库引擎：PostgreSQL（Go 驱动 `github.com/jackc/pgx/v5 v5.7.4`）。
- 应用只通过环境变量名 `DATABASE_URL` 连接；不得在日志、工单或命令历史中输出其值。
- 开发容器使用 `postgres:17-alpine`，默认端口映射为 PostgreSQL 标准端口 `5432`。
- 文档存在版本不一致：`docs/基础设施与第三方服务申请清单.md` 指定 PostgreSQL 16，`docs/deployment/local-vm-requirements.md` 与开发 Compose 指定 PostgreSQL 17。源腾讯数据库的实际版本与端口尚未验证；迁移前须用数据库查询确认，不能仅按文档推断。
- 当前只有 `cloud-api/migrations/000001_initial.up.sql` 和破坏性的 down SQL；没有迁移版本表、checksum、锁或应用启动版本校验。
- `make migrate-up` 实际执行 `psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f migrations/000001_initial.up.sql`。该 up SQL 没有显式事务且不可重复执行；生产不得执行 `make migrate-down`。
- 初始迁移创建 `pgcrypto` 扩展、两个 enum、13 张业务表、外键、约束与索引。

## 迁移前只读核验

- [ ] 在腾讯源库记录（保存到受限运维记录，不写入仓库）：`SELECT version();`、`SHOW server_version;`、`SHOW port;`、`SHOW server_encoding;`、`SHOW timezone;`。
- [ ] 确认源/目标均为 UTF-8、时区策略一致，并决定统一支持 PostgreSQL 16 还是 17；目标主版本不得低于源库主版本，跨主版本逻辑迁移先做兼容测试。
- [ ] 确认目标数据库为空，或完成对象冲突检查；当前初始 migration 遇到已有 type/table 会失败，不能安全重跑。
- [ ] 对源库做只读 schema 清单：扩展、enum、表、列、PK/FK/CHECK/UNIQUE、索引、序列、函数、owner、grant；与 `000001_initial.up.sql` 对照。
- [ ] 检查源库是否已有迁移版本表。项目本身未定义版本表，因此不能把“表存在”当成可靠版本号。
- [ ] 迁移前后至少核对：13 张预期业务表均存在；`pgcrypto` 可用；enum 值正确；约束和索引齐全；各表行数一致；关键 FK orphan 计数为 0。
- [ ] 使用 `pg_dump --schema-only` 和 `pg_restore --list`/受控 diff 做结构核验；禁止把 dump 内容或连接串输出到普通日志。

## 目标数据库角色与权限

建议分离 owner/migrator 与 runtime 角色：

- [ ] 数据库 owner/migrator：目标数据库 `CONNECT`、目标 schema `USAGE, CREATE`，以及创建 type/table/index/constraint 所需权限；必须能执行 `CREATE EXTENSION IF NOT EXISTS pgcrypto`。若托管/受限环境不允许应用角色创建扩展，由数据库管理员预装 `pgcrypto`，不要授予 superuser。
- [ ] migrator 应拥有其创建的对象，或具备后续 `ALTER` 权限；不授予集群级 superuser、复制或绕过 RLS 权限。
- [ ] runtime：数据库 `CONNECT`、schema `USAGE`，业务表所需 `SELECT, INSERT, UPDATE, DELETE`；如存在 sequence，再按实际需要授予 sequence 权限。runtime 不应拥有 `CREATE EXTENSION`、`CREATE TABLE`、`DROP` 或 schema `CREATE`。
- [ ] 配置 `ALTER DEFAULT PRIVILEGES` 时必须由实际对象 owner 执行并限定目标 schema；验证新建对象不会意外授予 `PUBLIC`。

## 与当前 cloud-api 兼容的安全迁移流程

1. [ ] 先在隔离的空目标库测试 `psql -X "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 -f migrations/000001_initial.up.sql`；`-1` 为当前全部可事务 DDL 提供单事务保护。不要对已有/部分初始化库盲目重跑。
2. [ ] 若迁移现有数据，优先用与目标 PostgreSQL 主版本兼容的 `pg_dump`/`pg_restore`；使用 custom format、校验退出码，备份文件加密并限制文件权限。
3. [ ] 安排写入停机窗口：先完成预演，再停止 cloud-api 写流量，确认无活跃写事务，取得最终一致性逻辑备份。
4. [ ] 在目标库恢复 schema 与 data；若直接恢复完整 dump，则不要再执行会冲突的初始 up migration。若先执行项目 migration，则仅恢复 data，并在预演中验证 FK/加载顺序。
5. [ ] 运行结构、约束、行数及关键业务抽样校验；使用只读事务执行。
6. [ ] 仅在受限 secret 配置中替换 EC2 服务的 `DATABASE_URL`；不打印值。启动前验证网络/TLS 与 `pgxpool` ping，启动后检查健康端点和脱敏日志。
7. [ ] 小流量验证注册、登录、refresh、权益/兑换、会话、usage、feedback 和 admin 审计路径；监控错误率、连接数、锁与磁盘。
8. [ ] 回滚采用停止新端写入、恢复旧连接与应用版本；不得执行 `000001_initial.down.sql`。切换后源库保持只读保留，直到验收和回滚窗口结束。
9. [ ] 后续应引入正式迁移器（版本表、checksum、锁、事务）和应用启动 schema 版本检查；当前仓库无法可靠报告 migration version。

## EC2 IAM 与 S3 备份前置条件

以下命令只应在 EC2 上运行；输出可能含账号/ARN，应保存在受限运维记录，不复制到本文：

- [ ] `aws sts get-caller-identity` 成功，证明实例通过 IAM role/instance profile 取得临时身份；不得配置长期 access key。
- [ ] 仅列环境变量名确认是否存在 AWS/S3 配置，不读取值，例如：`env | cut -d= -f1 | sort`；重点关注 `AWS_REGION`、`AWS_DEFAULT_REGION`、`S3_BUCKET`/项目约定桶变量名。不要运行 `env`、`set` 或打印变量值。
- [ ] 使用已批准的私有桶与固定前缀做只读访问核验：`aws s3api head-bucket --bucket "$S3_BUCKET"`，以及仅列指定备份前缀；不要全账号枚举、创建桶或写测试对象。
- [ ] 若无明确桶变量/批准桶名，停止检查并向管理员索取非秘密的 bucket name、region、prefix；不得自行创建资源或凭据。
- [ ] 实例角色最小权限：指定 bucket/prefix 的 `s3:ListBucket`（带 prefix 条件）、`s3:GetObject`、`s3:PutObject`，以及备份保留流程需要时的受限删除/分段上传权限；禁止 `s3:*` 和跨桶访问。
- [ ] Bucket 必须 Block Public Access、禁用 ACL 公共访问、启用 versioning（按恢复策略）、默认 SSE-S3 或 SSE-KMS、HTTPS-only bucket policy、访问审计与生命周期规则。
- [ ] 使用 SSE-KMS 时，实例角色还需对指定 KMS key 的最小 `kms:Encrypt`/`kms:Decrypt`/`kms:GenerateDataKey` 权限，key policy 与 S3 region 匹配。
- [ ] 生命周期需同时满足数据库恢复窗口和隐私期限；反馈对象最长 14 天的产品约束不能被数据库备份无限期绕过。明确备份保留、过期删除、legal hold 与责任人。
- [ ] 备份过程：一致性 `pg_dump` → 本地加密临时文件（权限 `600`、足够磁盘）→ TLS 上传指定 prefix → 校验对象大小/checksum → 安全清理临时文件；记录成功指标但不记录 DSN/数据内容。
- [ ] 至少每月在隔离数据库做恢复演练，验证 dump 可解密、可恢复、schema 完整、行数/约束合理，并记录 RPO/RTO。

## 尚未验证/迁移门禁

- [ ] 腾讯源库实际 PostgreSQL 版本、端口、规模、扩展及网络/TLS 设置。
- [ ] EC2 上 PostgreSQL client、AWS CLI、磁盘空间、systemd 服务名与配置文件位置。
- [ ] EC2 是否挂载 IAM role，以及 `sts:GetCallerIdentity` 是否成功。
- [ ] 已批准 S3 bucket、region、prefix、KMS key、实际 IAM policy 与 bucket policy。
- [ ] EC2 到目标 PostgreSQL 的安全组、路由、DNS、TLS 和证书校验。

以上任一门禁未确认时，不进行生产切换或宣称 S3 备份可用。
