# Account Center 安全身份资料接口报告

> **历史归档（任务 05）**：原路径 `docs/superpowers/account-center-safe-identity-profile-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## 实现

新增受 access token 保护的 `GET /api/v1/account/identity`。

- Store 仅按 JWT subject 查询启用用户。
- 返回 `username`、实际 `email` 和 `masked_phone`；完整手机号、密码、hash、token 均不会进入 DTO。
- 手机号掩码格式为 `+86138****8000`；legacy 无手机号时省略 `masked_phone`，用户名回退为 `旧版用户`。
- PATCH 身份更新接口保持完整四字段请求契约不变。

## 测试与验证

- RED：新 profile 测试在 domain `AccountIdentity`/Store 方法尚未定义时编译失败。
- GREEN：`go test ./internal/http -run 'TestAccountIdentityProfile'` 通过。
- 全量：`go test ./...` 通过。
- `git diff --check` 通过。

未连接数据库、未运行 migration 或服务，未 push。
