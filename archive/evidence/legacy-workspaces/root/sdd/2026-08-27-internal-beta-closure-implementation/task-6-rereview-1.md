> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-6-rereview-1.md`；原始 SHA-256 `52deaacb8d1eb967758c6b52e3029905c60ab3d1015452185ddfeb43caf470d1`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 6 高风险复核（round 1）

## 结论

**暂不批准代码完成态。**

- Critical：**0**
- Important：**1**

提交 `6b30078` 已实质修复初审 I1、I2、I3、I5，并完成 I4 的 query、编码、搜索和基本分页/空 next 行为；但新增分页实现没有处理并发请求的返回顺序。搜索按钮在加载期间仍可提交，且 `loadPage` 没有 request generation、取消或 stale-response 防护，较旧响应可覆盖较新搜索/分页结果。这会使管理员界面显示与当前搜索词、页码不一致的数据，属于必须修复的管理数据正确性问题。

本次只做静态、只读评审：未修改被评审 worktree，未构建、未运行测试/服务/数据库/浏览器，也未调度代理。仅写入本评审报告。代码批准与真实浏览器/API 验证明确分开；即使修复下述代码问题，真实浏览器、真实 Cloud API、数据库和反向代理联调仍属于延后验证。

## Important finding

### I6. 列表请求可乱序提交并由旧响应覆盖新查询结果

**文件：** `cloud-api/admin-web/src/components/AdminListPanel.tsx`

`loadPage(nextOffset, nextSearch)` 在每次调用后直接写入共享的 `state`、`offset` 和 `lastNonEmptyData`，没有为请求分配递增 generation，也没有使用 `AbortController` 或在完成时确认该响应仍对应当前 query。

与此同时：

- 初始加载由 `useEffect` 发起时，搜索表单仍可操作；
- 搜索按钮没有随 `state.kind === 'loading'` 禁用；
- 用户可在前一搜索尚未返回时继续提交不同搜索；
- `submitSearch` 会立即把当前 search/offset 设为新值，但任何较旧请求随后仍可覆盖表格状态。

可复现的静态时序：

1. 初始无筛选请求 A：`offset=0, q=''` 发出；
2. 用户立即提交 `q=admin@example.com`，请求 B 发出；
3. B 先返回并显示筛选结果；
4. A 后返回，执行 `setState({kind:'ready', data:A})`，把表格覆盖为未筛选数据；
5. 组件内部 `search` 仍是 `admin@example.com`，后续刷新/翻页又按筛选条件请求，UI 的搜索条件、页码和当前行暂时互相矛盾。

两个快速搜索、搜索与刷新交错时也存在同类问题。旧的非空响应还会污染 `lastNonEmptyData`，使后续 empty-next 回退可能恢复错误 query 的数据。现有测试均让 Promise 立即、按发起顺序完成，未覆盖反序完成。

**最小修复：**

- 在组件内维护递增 request generation；每次 `loadPage` 捕获 generation，完成后仅当它仍为最新值时才允许修改 `state`、`offset` 或 `lastNonEmptyData`；或使用可正确清理的 `AbortController`，并仍防御无法取消/已完成的旧响应。
- `lastNonEmptyData` 应与当前 `{q, offset}`/已确认页面绑定，不能由 stale response 更新。
- 搜索提交可以在 loading 时禁用以减少重入，但不能单独替代 stale-response 防护。
- 增加 deferred Promise 测试：旧请求在新请求之后完成，断言新结果、query 和页码不被覆盖；再覆盖旧空/非空响应不能触发错误 empty-next 回退。

## I1–I5 复核

### I1 — 已修复：logout Authorization、refresh body 与本地清理

- `CloudApiClient.logout` 以 `authenticated=true` 调用 `post`，请求同时包含 `Authorization: Bearer <access-token>` 和 `{"refresh_token":"..."}`。
- `App.logout` 使用 `authenticatedClient`，并在 `finally` 调用 `endSession(null)`；无论网络或服务端结果如何都会清除内存 session 及两个 `sessionStorage` token。
- API 测试和 App 测试都断言了 header/body；App 测试明确检查本地 access token 清除。
- 401 refresh 后重试仍沿用原 refresh body；服务端 refresh rotation 保留旧 token 记录，而 family revoke 按旧 token hash 找到 family 并撤销整族，因此未发现由该重试导致的撤销绕过。

未发现 token 被放入 URL、错误文本、响应 header 或 UI。客户端错误展示只使用服务端 error code、经服务端校验/生成的 request ID 或浏览器网络错误；后端 access log 明确不记录 query/header/body/IP。

### I2 — 已修复：audit 安全 DTO 不传递任意 metadata

- `adminAuditLogResponse` 是 HTTP 专用 DTO，只列出 `id/admin_id/action/target_type/target_id/metadata/created_at`。
- `safeAuditLogResponse` 不复制 `domain.AuditLog.Metadata`，而是为每项新建固定空 map；开放的 `map[string]any` 存储 metadata 无法沿该转换进入响应。
- 真实 router 测试向 store fixture 注入 canary secret，断言 body 不含 canary 且解码后的 metadata 为空。

因此任意 metadata、嵌套对象或 secret 不会因该端点下载到浏览器。DTO 的其他字段均为文档所需审计标识字段；未发现额外存储字段被整体序列化。

### I3 — 已修复：真实 router RBAC/契约测试

新增 `cloud-api/internal/http/admin_test.go` 通过真实 `NewRouter` 和真实测试 `TokenIssuer` 覆盖两个 GET 管理员端点：

- 缺 token → 401；
- invalid token → 401；
- 有效 user role → 403；
- 有效 admin role → 200；
- disabled user → 401；
- users/audit object envelope 顶层唯一键；
- users `q/limit/offset` 与 audit `limit/offset` 透传；
- store error → 500，且错误详情不回显；
- audit canary metadata 脱敏。

测试对 users/audit 的每个必需 item 字段断言仍可更精确（当前 users 主要借助 `domain.User` 解码并检查 email，audit 主要检查 ID/metadata），但响应结构由具体 struct/DTO 静态约束，未发现字段缺失或额外敏感字段；本轮不另列 Important。

### I4 — 功能已接入，但被 I6 的竞态阻塞批准

已确认：

- `listUsers()` / `listAuditLogs()` 保留无参向后兼容默认 `{limit:50, offset:0}`；
- users 仅发送可选 `q` 和 `limit/offset`，audit 不发送 `q`；
- 使用 `URLSearchParams`，测试覆盖 `+`、`@`、空格、`&` 的编码；
- 搜索提交会 trim 并重置 offset=0；
- 上一页/下一页固定 50 条；短页禁用 next；
- 整页后的空 next 不自动循环请求，会恢复上一页数据和 offset；
- 列表/分页按钮具备可访问名称，搜索 input 与 label 关联，table header 使用 `scope="col"`，区域设置 `aria-busy`。

但 I6 意味着上述状态在并发返回时并不可靠。另有非阻塞可访问性改进空间：页码/空页回退没有 live announcement，空 next 后焦点可能留在变为 disabled 的按钮；建议真实浏览器键盘和 screen reader 验证时检查。

### I5 — 已修复：两份文档对齐

`cloud-api/API.md` 与 `cloud-api/admin-web/API_EXPECTATIONS.md` 对 users/audit 一致记录：

- access JWT、401/403；
- users 的 `q/limit/offset` 和 audit 的 `limit/offset`；
- limit 默认 50、最大 100，offset 默认/下限；
- `{"users":[...]}` 与 `{"audit_logs":[...]}` object envelope；
- audit `target_id` 可选；
- HTTP metadata 固定 `{}`，不暴露原始 metadata。

旧的 501/裸数组候选契约已移除。未发现两份文档在本轮精确 API 上冲突。

## 其他高风险检查

- **RBAC bypass：**未发现。管理员路由仍位于外层 `require` 认证组内，并由 `admin` 检查 JWT role；前端 `/me` 仅控制会话/UI，不是授权边界。
- **双重 `require`：**`api.admin` 仍再次包装 `a.require`，因此 admin 请求会验证 JWT 并调用 `UserEnabled` 两次。它增加一次存储读取，并可能在两次检查结果变化时更保守地返回 401，但不会放宽权限或绕过 disabled 检查；本轮未构成 Critical/Important 行为问题。后续可让 `admin` 对缺失 principal fail closed 后只检查 role，并用真实 router tests 锁定 middleware 顺序。
- **DTO correctness：**audit 响应未整体序列化 domain object；`target_id` 通过 `omitempty` 与文档的可选字段一致；metadata 始终编码为非 nil 空对象 `{}`。
- **Token 泄漏：**未发现 Authorization/refresh token 进入 URL、错误响应、header、UI 或 access log 的新路径。
- **分页可达性：**正常顺序完成时，第 51 条以后可访问；短页和 empty-next 的基本行为正确。批准阻塞点仅为请求乱序导致的数据/query/page 不一致。

## 批准门槛与延后验证

代码批准前需修复 I6，并加入反序完成测试。修复后仍应由获准执行验证的一方提供新鲜的 web test/typecheck/lint/build 证据；本次只读复核不采用报告中的历史通过声明替代现场验证。

即使代码随后获批，以下仍明确延后，不能由静态评审宣称通过：真实浏览器交互与键盘/辅助技术体验、真实 Cloud API、真实 PostgreSQL refresh-family 撤销、CORS/反向代理部署路径及端到端登录/搜索/分页/退出流程。
