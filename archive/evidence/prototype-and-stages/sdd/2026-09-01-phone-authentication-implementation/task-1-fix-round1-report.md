# Task 1 Fix Round 1 Report

> **历史归档（任务 05）**：原路径 `.superpowers/sdd/2026-09-01-phone-authentication-implementation/task-1-fix-round1-report.md`；来源快照 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（2026-11-17 整理起点）。
> 本文是旧阶段约束、目标或过程证据，不作为当前规范；原有待实施、未勾选和未执行状态保留。正文命令与源码路径均基于原仓库根目录及当时版本，不能直接作为当前操作步骤。
> 当前入口：[项目首页](../../../../../README.md)、[模块地图](../../../../../docs/architecture/README.md)、[本批效力与完整性索引](../../../../indexes/05-prototype-documents.md)。归档调整与前后哈希见索引。

## 范围

仅处理 `task-1-review.md` 的 1 项 Important 与 2 项 Minor；未修改生产代码或 migration 内容。

## 修改

- 替换 `PhoneCredentialsInput` 的 `%+v` 失败断言，分别断言手机号规范化和密码保留；失败消息不格式化手机号或密码。
- 扩展 repository migration runner 测试，明确断言 `000004` 是事务内 migration。
- 新增表驱动边界测试：
  - phone：空值、第二位 `3`/`9`、裸 `86`、双 `+86`；
  - username：精确 3/32、33、全大写规范化；
  - phone credentials：密码过短、超过最大字节数、无效 UTF-8。
- 所有新增失败消息均不输出手机号或密码。

## 验证

在 `cloud-api` 执行且通过：

```text
go test ./internal/domain ./internal/migrate
go test ./...
git diff --check
```

未运行数据库 migration/down migration，未连接数据库或外部服务，也未使用代理。
