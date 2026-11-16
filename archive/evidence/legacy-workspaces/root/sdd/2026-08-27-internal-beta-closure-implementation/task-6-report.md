> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-6-report.md`；原始 SHA-256 `f6d50fcde5331b23b15c910f4d98db2cbe8d769bb74852691091e7e7a1a49943`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 6 实施报告

日期：2026-08-28
Worktree：`[旧临时路径]`

## 完成内容

- `CloudApiClient.logout` 使用 authenticated request，同时发送 access `Authorization` header 与 `refresh_token` JSON body；`App.logout` 保留 `finally` 本地 session 清理。
- `listUsers` / `listAuditLogs` 增加窄类型 query，默认 `{limit: 50, offset: 0}`，通过 `URLSearchParams` 安全编码。
- `AdminListPanel` 接入固定 50 条分页；users 页面增加提交式邮箱搜索，提交/清空重置 offset；audit 页面复用翻页但不发送 `q`。无 total 时短页禁用 next；整页后的空 next page 恢复上一页且不循环请求。
- audit HTTP handler 使用专用安全 DTO，固定向浏览器返回 `metadata: {}`，保留 `audit_logs` object envelope。
- 通过真实 `NewRouter` 新增管理员 users/audit 401、invalid token、403、admin 200、disabled user、envelope、query 透传、store failure 和 canary metadata 脱敏测试。
- 更新 `cloud-api/API.md` 与 `cloud-api/admin-web/API_EXPECTATIONS.md`，删除旧 501/裸数组描述，统一参数、默认/最大值、401/403 与安全 metadata 契约。

## 验证

未连接数据库、未启动真实服务、未使用代理；所有 HTTP 测试使用 fake store 与测试 token issuer。

- `cloud-api`: `go test ./...` 通过。
- `cloud-api`: `go vet ./...` 通过。
- `cloud-api`: `go build ./cmd/cloud-api` 与 `go build ./cmd/migrate` 通过；生成的本地二进制已删除，未提交。
- `admin-web`: `npm test` 通过，3 个 test files / 15 tests。
- `admin-web`: `npm run typecheck` 通过。
- `admin-web`: `npm run lint` 通过。
- `admin-web`: `npm run build` 通过；`node_modules` 与 `dist` 未纳入 Git。
- `git diff --check` 通过。
- 工件检查使用精确禁止路径复核；`android/app/build.gradle.kts` 是正常已跟踪 Gradle 源文件，不是 `app/build/` 构建产物。

## 延后风险

- 未运行真实浏览器、真实 Cloud API、数据库、反向代理或 provider。
- 未完成 Android 真机/模拟器翻译验收；HTTP/WS 内测边界、HTTPS/WSS、生产 secret 注入、托管数据库、监控备份与正式签名仍是后续工作。
