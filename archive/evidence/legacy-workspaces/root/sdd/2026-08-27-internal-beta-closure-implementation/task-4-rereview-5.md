> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-4-rereview-5.md`；原始 SHA-256 `9b0b1f5df3e27354179e0f31d18302f493667e606a61c5a18291133965e0eab7`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 4 fix round 5 只读复审

审查对象：`[旧临时路径]` commit `40164839efc94258bc9a0d93923ba25800369670`，修复范围 `b1aa16d..4016483`。

## Critical

无。

## Important

无。

`ValidateDatabaseURL` 现在仅允许唯一的 URL query 形态 `sslmode=disable`，并通过 `pgx.ParseConfigWithOptions` 的 `ConnStringAllowedKeys` 拒绝其他 URL/keyword 参数；解析后又要求有效主目标严格为获准 host/port、`Fallbacks` 为空且 `RuntimeParams` 为空。因而 `standard_conforming_strings`、`options`、未知 startup runtime parameter 等 URL 路径均 fail closed。

环境来源也不能保留该绕过：`PGOPTIONS`、`PGAPPNAME`、`PGTZ` 以及 service 配置中的 `standard_conforming_strings`/`options` 等会进入 `RuntimeParams` 并被拒绝；环境或 service 形成的 fallback 会被拒绝；URL 中显式结构字段和 `sslmode=disable` 覆盖对应环境/service 值，解析后的实际 host/port 仍会复核。`Run` 在建 pool 前再次解析并执行同一目标、fallback、runtime parameter 校验，随后才加入受控的 `search_path`。

未发现目标校验绕过，也未发现变更后的固定 sentinel、`Run` 错误包装或新增测试失败信息泄露 DSN/密码；新增测试只输出固定错误，测试名不含秘密。未发现该提交引入新的 Critical/Important。

**最终判定：可以进入真实 `127.0.0.1:15432` E2E。**

只读复审期间未修改 `[旧临时路径]`，未运行 Docker 或数据库命令。静态执行 `git diff --check b1aa16d..4016483` 通过；未执行真实数据库测试。
