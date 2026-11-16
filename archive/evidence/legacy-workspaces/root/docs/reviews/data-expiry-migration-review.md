> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `docs/reviews/data-expiry-migration-review.md`；原始 SHA-256 `e90badab18f69b83adceccee7c80de0757aec71b395a6e970679b36c77bc745f`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 数据过期清理与迁移安全审查

> 范围：`agent/`、`cloud-api/`；只读审查，不代表问题已修复。
> 重点：TTL、过期删除、定时清理任务、schema migration 的回滚性、破坏性及存量数据兼容性。

## 结论

当前实现有“逻辑过期判断”，但没有“物理清理闭环”：仓库内未发现 `DELETE FROM`、sweeper、cron、worker 或对象存储生命周期配置。`feedback_artifacts` 的 14 天约束只限制写入的 `expires_at`，到期记录仍可读取且不会删除；refresh token、translation session、旧 entitlement 和限流键也会持续累积。

迁移体系目前只有一个初始 up/down SQL 文件，没有版本表、校验和、事务包装或应用启动时的版本检查。up 迁移失败可留下半初始化 schema；down 迁移会删除全部业务表和类型，属于不可恢复的数据销毁，不应作为常规生产回滚手段。

## 问题清单（按优先级）

### P0 / 严重：14 天反馈到期没有删除闭环，且到期元数据仍可读取

**证据**

- `cloud-api/migrations/000001_initial.up.sql` 仅有 `expires_at <= created_at + interval '14 days'` CHECK。
- `cloud-api/internal/httpapi/server.go` 创建时校验最长 14 天，但 `feedbackArtifactGet` 不判断当前时间。
- `cloud-api/internal/store/postgres.go` 和 `memory.go` 的读取只检查 owner/id，不检查 `expires_at`。
- 仓库没有对象删除 API、后台任务或对象存储 lifecycle 配置。

**风险**

“最长 14 天”目前只是客户端提交期限限制，不是数据删除承诺。数据库元数据会无限保留；若 `object_key` 指向仍存在的对象，到期后仍可通过 API 获取定位信息。对象存储中的音频/反馈内容也没有仓库内可验证的删除保证，形成隐私与合规高风险。

**建议修复**

1. P0 先让所有读取在 `now >= expires_at` 时返回 `404` 或 `410`，并覆盖 Postgres/Memory/API 测试。
2. 增加幂等删除状态机（如 `active -> delete_pending -> deleted`）和定时 sweeper；先删对象，再清理或 tombstone 元数据，失败重试并告警。
3. Bucket 配置强制最多 14 天 lifecycle，作为 worker 失败的兜底；应用不得延长对象生命周期。
4. 对撤回同意、用户主动删除、用户删除账号采用同一删除队列；明确备份最大保留期。

### P0 / 严重：初始 up migration 非事务化，失败会留下不可判定的半迁移状态

**证据**

- `migrations/000001_initial.up.sql` 没有 `BEGIN/COMMIT`。
- `Makefile` 用 `psql -v ON_ERROR_STOP=1 -f` 直接执行，无 migration version 表或 checksum。
- SQL 多数 `CREATE TYPE/TABLE` 不具幂等性；失败后重跑会在已创建对象处再次失败。
- Docker entrypoint 只在新 volume 首次初始化时执行脚本。

**风险**

中途发生权限、语法、断连或约束错误时，前面的 DDL 可能已经提交。部署系统无法知道迁移是否完整，也无法安全重跑；应用可能在缺表/缺约束 schema 上启动。已有 Docker volume 还可能静默停留在旧 schema。

**建议修复**

1. 使用成熟迁移器维护 `schema_migrations`、版本锁和 checksum；应用启动时拒绝未知/落后 schema。
2. 每个可事务化迁移放入单事务；需要 `CREATE INDEX CONCURRENTLY` 等非事务 DDL 时拆成明确阶段。
3. CI 在全新数据库验证 up，在含代表性存量数据的数据库验证 forward migration，并验证失败注入后的原子性。
4. 部署流程不得依赖 Docker entrypoint 自动升级已有 volume。

### P0 / 严重：down migration 是全量数据销毁，不能作为安全回滚

**证据**

- `000001_initial.down.sql` 使用 `DROP TABLE ... CASCADE` 删除全部业务表，再删除 enum 类型。
- `make migrate-down` 可直接执行该脚本，只有 README 文字提示“destructive”，没有环境门禁、备份确认或 dry-run。

**风险**

一次误操作即可删除用户、密码哈希、权益、兑换、会话、反馈和审计记录；`CASCADE` 还可能删除未在脚本中显式列出的依赖对象。schema rollback 不等于应用 rollback，删除的数据无法由重新部署恢复。

**建议修复**

1. 生产禁用自动 down；回滚优先采用向前兼容的 corrective migration 和应用版本回退。
2. 若保留破坏性 reset，只允许明确的开发数据库命令，要求数据库名/环境双重确认并使用不同目标名。
3. 生产执行任何 destructive migration 前强制备份、恢复演练、人工审批和影响预览；避免宽泛 `CASCADE`。

### P1 / 高：translation session 过期记录不清理，且 usage 写入不检查 session 是否过期

**证据**

- Cloud API 固定创建 `now + 5 minutes` 的 `translation_sessions.expires_at` 和 JWT，没有截断到 `entitlement.expires_at`；临近权益到期时授权会越过权益边界。
- session 无消费/结束/删除状态和清理任务。
- `CreateUsageRecord` 只验证 session id 与 user id，不要求 `expires_at > now`。
- `usage_records` 外键级联到 session；若未来直接清理 session，会同时删除 usage 历史。
- Agent 的 JWT verifier 只限制连接建立时 token 有效；已建立连接没有基于 token `exp` 的运行截止逻辑。

**风险**

会话/JTI 无限增长；客户端可在 session 过期后继续追加 usage，且临近到期 entitlement 可签出越界授权。未来清理 session 时可能意外删除需要保留的权威用量。授权开始 TTL 与活动连接最大时长没有形成一致的数据生命周期。

**建议修复**

1. 签发时使用 `exp = min(now + session_ttl, entitlement.expires_at)`；定义 session 状态与 `consumed_at/finished_at/retention_until`，并建立明确的连接最大时长。
2. 权威 usage 由 Agent/internal API 幂等上报，拒绝过期、未消费或归属不匹配的 session。
3. 明确 usage 的法务/计费保留期；若 usage 需长期保留，不要依赖 `ON DELETE CASCADE`，可归档或保留脱敏 session 主键。
4. 为 `translation_sessions(expires_at)` 和清理查询建立索引，批量、小事务删除并监控 backlog。

### P1 / 高：refresh token 逻辑失效但不物理清理，索引与约束不支持安全回收

**证据**

- refresh 时检查 `expires_at` 和 `revoked_at`，但 `refresh_tokens` 永不删除。
- 旋转会为每次 refresh 增加一行；复用只更新 family 的 `revoked_at`。
- 无 `expires_at` 或 `revoked_at` 清理索引；`replaced_by_id` 自引用外键没有删除策略。

**风险**

高频刷新会持续放大表、唯一索引和 family 查询成本。简单删除可能受自引用 FK 阻塞，或破坏复用检测所需窗口。

**建议修复**

1. 定义“过期/撤销后保留多久用于复用检测”的窗口，然后清理整条 family 或先解除引用。
2. 增加适合批处理的过期索引；使用 `FOR UPDATE SKIP LOCKED`/小批次删除避免长事务和表膨胀。
3. 增加约束：`expires_at > created_at`；`replaced_by_id` 非空时 token 应已撤销，并评估自引用 FK 的显式删除策略。

### P1 / 高：迁移约束不足，未来加约束时可能与存量脏数据不兼容

**证据**

- `translation_sessions(user_id, entitlement_id)` 没有复合 FK，数据库允许引用其他用户的 entitlement。
- `feedback_artifacts(user_id, consent_id)` 同样没有复合 FK。
- `usage_records(user_id, translation_session_id)` 的归属仅由应用 `INSERT ... SELECT` 保证。
- `entitlements.source_redemption_code_id` 未唯一，trial/package 与 source 的组合未被完整约束。
- `updated_at` 默认写入但没有统一更新 trigger，字段语义可能与实际数据不一致。

**风险**

应用旁路、脚本或未来 bug 可写入跨用户关联。等数据量增长后再加复合 FK/UNIQUE/CHECK，迁移会因已有脏数据失败或需要长时间锁表。

**建议修复**

1. 先写只读 preflight 查询统计违规行并制定修复规则；不要直接添加强约束。
2. Postgres 可先 `ADD CONSTRAINT ... NOT VALID`，分批清洗后 `VALIDATE CONSTRAINT`，降低持锁时间。
3. 增加 `(id,user_id)` 唯一键及对应复合 FK；给 redemption source 增加一致性/唯一性约束。
4. 明确 `updated_at` 由应用更新还是 trigger 维护，并补迁移测试。

### P1 / 高：过期清理缺少必要索引、批处理和可观测性设计

**证据**

- `feedback_artifacts` 只有 `(user_id, created_at DESC)` 索引，没有 `expires_at` 索引。
- `translation_sessions` 只有 `(user_id, created_at DESC)`；refresh token 只有 `(user_id,family_id)`。
- 没有清理游标、失败重试、删除计数、最老过期年龄或告警。

**风险**

即使后续添加定时 SQL，也会演变为全表扫描和大事务删除，导致锁、WAL、replica lag、autovacuum 压力及线上抖动。

**建议修复**

按每类数据设计 `(expires_at,id)` 或适合状态机的 partial index；小批量 claim/delete，限制单轮时间，记录删除数、失败数、backlog 和 oldest-expired-age；上线前压测并设置 autovacuum/归档策略。

### P2 / 中：过期 entitlement、consent、兑换与审计数据没有明确保留策略

**证据**

- `ActiveEntitlement` 正确排除过期权益，但过期行永久保留。
- feedback consent 是追加事件，未定义撤回后的清理或历史保留期。
- redemption code/batch 与 audit log 都没有 retention policy。

**风险**

数据库长期增长，且隐私、运营、审计数据被混在同一“永久保留”默认策略中。盲目清理又可能破坏兑换凭证、争议处理或审计要求。

**建议修复**

建立数据分类表：业务目的、法定依据、在线保留、归档保留、删除触发、备份期限、责任人。权益/兑换/审计通常不能与短期 session/反馈使用同一 TTL；必要时归档脱敏记录而非直接级联删除。

### P2 / 中：进程内 rate limiter 的过期键不会删除

**证据**

- `cloud-api/internal/httpapi/server.go` 的 `rateLimiter.entries` 只在同一 key 再次访问时重置，没有后台淘汰。

**风险**

攻击者持续制造不同 IP/route key 可使 map 随进程生命周期增长，形成内存消耗风险。它不是持久化迁移问题，但属于明确的 TTL 清理缺失。

**建议修复**

MVP 可增加有上限的 TTL cache/周期淘汰；多实例生产改用具原生 TTL 的共享 limiter，并限制可信代理来源，避免伪造 IP 扩大 key 空间。

### P2 / 中：迁移缺少 PostgreSQL 集成测试和部署兼容矩阵

**证据**

- README 明确单元/HTTP 测试仅使用 `internal/store.Memory`。
- 没有验证真实 migration、FK/CHECK、回滚或存量数据升级的测试。
- Compose 使用 Postgres 17，而项目说明中其他资料可能按 Postgres 16 规划；仓库未声明生产兼容版本矩阵。

**风险**

Memory store 无法暴露 SQL 约束、锁、事务和版本差异问题；开发初始化成功不能证明真实升级安全。

**建议修复**

CI 使用目标生产主版本运行：fresh up、重复执行预期、代表性存量数据 forward migration、约束验证、应用旧/新版本兼容窗口。生产版本需明确且至少覆盖当前与目标升级版本。

## 已存在的有效控制

- refresh token 在使用时检查撤销和 `expires_at`；旋转使用行锁与事务。
- entitlement 查询采用半开区间语义：`starts_at <= now AND expires_at > now`。
- session JWT verifier 要求 `exp/iat`、最大寿命与可配置时钟偏差。
- feedback artifact 在 API 与数据库两层限制到期时间晚于创建且不超过 14 天。
- 注册、refresh rotation、兑换批次和兑换在 Postgres 中使用事务；兑换码行使用 `FOR UPDATE`。
- README 已明确 down migration 是 destructive，虽不足以构成生产门禁，但避免了完全无提示。

## 推荐实施顺序

1. **P0 隐私止血**：到期 artifact 不可读；对象存储 14 天 lifecycle；实现可重试删除 worker 与监控。
2. **P0 迁移基线**：引入版本化迁移器、事务/锁/checksum、schema 版本启动检查；生产禁用 destructive down。
3. **P1 生命周期模型**：定义 session/usage、refresh family、entitlement、consent、audit 的差异化 retention。
4. **P1 数据库准备**：先跑 preflight，再补复合归属约束和 `(expires_at,id)` 清理索引。
5. **P1 安全清理**：小批量、幂等、可恢复的 worker；失败重试、backlog/oldest-age 告警及 vacuum 观察。
6. **P2 验证体系**：真实 PostgreSQL migration/rollback-drill/存量升级测试；定期恢复演练。

## 最低验收标准

- 到期反馈在 API、对象存储和数据库三个层面均不可恢复访问，删除失败可观测且最迟 14 天由 lifecycle 兜底。
- 任一迁移失败不会留下应用可启动的半迁移 schema；已应用版本可追踪且 checksum 不可静默漂移。
- 生产回滚不执行全库 drop；破坏性操作有备份、审批、目标校验和恢复演练。
- refresh/session/反馈清理走索引和有界批次，不出现长事务全表删除。
- 加约束前能报告并处理存量违规数据；真实 PostgreSQL CI 覆盖 fresh install 与存量升级。
