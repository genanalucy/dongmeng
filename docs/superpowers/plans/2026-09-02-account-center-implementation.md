# 账户中心实施计划

## Task 1：Cloud 账户数据与安全服务

- 审计现有 entitlement/session/usage schema，确定可复用字段；必要时新增非破坏性 migration。
- 新增 account overview、个人 usage 列表与 identity update 的领域/Store/Service 接口。
- identity update 在事务中验证当前密码并写入 username/email/phone；统一冲突/凭据错误，不泄露字段。
- TDD：owner isolation、聚合、legacy 用户、密码错误、三类唯一冲突、回滚和安全 DTO。

## Task 2：Cloud HTTP 路由与受控验证

- 实现 `/api/v1/account/overview`、`/usage`、`/identity`，接入现有 access token middleware。
- 覆盖请求 decode、分页、401/403、公开响应脱敏及 admin/refresh/session 不回归。
- 仅安全 `127.0.0.1:15432` DSN 时执行 migration/E2E；否则记录阻塞。

## Task 3：Android Cloud 数据层与状态

- 扩展 `AccountApi`/`CloudApi` 为 overview、usage、identity update 请求与安全 DTO。
- 扩展 `AccountViewModel` 状态/操作，保留 Keystore、refresh、logout 和认证行为。
- 纯表单策略：编辑身份时手机号掩码、重新输入完整手机号、当前密码不入持久状态。
- 单测状态、请求形状、错误与零派发。

## Task 4：Android 我的/使用权益/账户设置 UI

- 以用户名重构“我的”主页；实现权益/使用页、历史/服务设置入口和设置身份页。
- Compose 语义/可访问性、48dp、加载空态错误、分页；不显示敏感身份。
- 更新验收清单；运行 unit/Compose compile/lint/assemble，真机未可用时记录未验证。

## Task 5：复审、发布与验收

- 每任务独立审查，Cloud+Android 最终审查。
- 完成受控 PostgreSQL E2E 与真机 smoke 后，更新内测 Release 手册（不得上传配置或敏感数据）。
