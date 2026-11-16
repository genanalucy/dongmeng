> **历史证据，非现行操作或验收结论。** 来源：`079108bff4b55d1b3783ca8e99e3376a35804ee0` 的 `docs/operations/shared-invitations.md`。原文中的“当前/本次”仅指记录当时，未测项保持未测；本轮未连接生产或真机。现行入口：[当前说明](../../../../../docs/operations/shared-invitations.md)；来源与内容处理见[批次索引](../../../../indexes/06-operations-and-test-materials.md)。

## 本次部署记录（2026-11-11 UTC）

- 实现提交 `c634bf9`，Cloud API test/vet/build、签发CLI与迁移器构建通过。
- 专用 PostgreSQL17 隔离实例实际执行 `TestSharedRedemption*` 与 `TestSharedInvite*` 成功，未跳过；含 HTTP 兼容、10并发唯一领取、截止边界、取得用户锁前跨截止点、停用、签发及旧码兼容。另运行真实迁移器单测通过。
- 生产迁移账本校验及 `000015` 应用成功，受限API账号具有领取表 SELECT/INSERT 权限；Cloud API 已替换并重启，Agent/Caddy保持active，本机与sslip公网health返回正常。
- 备份：`/opt/dngmeng/backups/shared-invite-20261111T145959Z/`，包含数据库、自部署前二进制及迁移目录。迁移为增量，回滚不删除领取记录。
- 已创建并只读验证 `internal-beta-2026-shared` 批次：哈希匹配、shared=true、启用、截止UTC2026-12-31T16:00、365天。码原文仅在受限交付文件，不写本文、Git或常规日志。
- 不发布新APK、不更新OTA、不自动替真实用户领取。此次后端服务为正式与测试共用，API请求/响应保持兼容；正式域名既有Cloudflare重定向风险未在本次处理。
