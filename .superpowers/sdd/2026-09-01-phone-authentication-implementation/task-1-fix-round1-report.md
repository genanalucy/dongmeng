# Task 1 Fix Round 1 Report

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
