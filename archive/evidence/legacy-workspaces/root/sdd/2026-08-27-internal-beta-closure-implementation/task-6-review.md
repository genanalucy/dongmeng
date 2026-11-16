> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-6-review.md`；原始 SHA-256 `c9da51d4cab535d75edb1dd0525679eeeea25960ef764e896b5a316c55171801`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 6 高风险只读评审

## 结论

**不批准 Task 6 进入完成态。**

- Critical：**0**
- Important：**5**

后端管理员路由确实位于已认证路由组内，并额外检查 access JWT 的 `role=admin`；前端也会通过 `/api/v1/users/me` 清理非管理员会话。因此没有发现“只靠前端角色判断”或普通用户可直接读取管理员数据的 Critical 漏洞。

但当前实现仍有五项会影响 Task 6 最低交付或安全契约的重要问题：退出登录请求未携带 access token，服务端实际不会撤销 refresh family；审计接口把存储层任意 `metadata` 原样发给浏览器；后端缺少计划要求的管理员 HTTP/RBAC 契约测试；Web 无法访问默认前 50 条以外的用户和审计记录；Task 6 的 Admin API 文档仍声明旧的 501/裸数组契约。

本次为静态只读评审，按要求没有修改实现、构建、运行测试、服务、数据库或代理。

## Important findings

### I1. `logout()` 没有发送 access token，后端 refresh family 实际不会被撤销

**文件：**

- `cloud-api/admin-web/src/api/cloudApi.ts`：`CloudApiClient.logout`、`post`
- `cloud-api/admin-web/src/App.tsx`：`logout`
- `cloud-api/internal/httpapi/router.go`：`POST /api/v1/auth/logout` 注册在 `require` 组内

`CloudApiClient.post()` 默认 `authenticated = false`，而 `logout()` 没有覆盖该默认值。因此退出请求只有 refresh token JSON body，没有 `Authorization: Bearer <access-token>`。

后端把 `/api/v1/auth/logout` 放在 `api.require` 保护的组中，故请求会在到达 `api.logout` 前返回 401。`App.logout()` 又在 `finally` 中无条件清理本地 session，使 UI 看起来已成功退出，但服务端 refresh token family 仍有效。若 refresh token 已被获取，它仍可继续换取 access token，直到过期或发生其他撤销。

**最小修复：**

1. 让 `post()` 可显式接收 `authenticated`，并由 `logout()` 传 `true`；或为 logout 直接调用 `request(..., authenticated: true)`。
2. 保持前端 `finally` 本地清理，避免网络故障使用户留在控制台；但不要把本地清理等同于服务端撤销成功。
3. 在 `cloudApi.test.ts` 断言 logout 同时发送 refresh token body 和 access-token Authorization header。
4. 在 `App.test.tsx` 的退出测试中检查 Authorization header，而不只检查 URL 被调用和 sessionStorage 被清空。

### I2. 审计接口把任意存储层 `metadata` 原样暴露给浏览器，不满足计划的安全子集要求

**文件：**

- `cloud-api/internal/httpapi/admin.go`：`auditLogs`
- `cloud-api/internal/domain/domain.go`：`AuditLog.Metadata map[string]any`
- `cloud-api/admin-web/src/api/cloudApi.ts`：`parseAuditLog`

`auditLogs` 直接把 `store.ListAuditLogs` 返回的 `domain.AuditLog` 序列化。`Metadata` 是开放的 `map[string]any`，handler 没有 DTO 投影、键白名单或值类型限制。前端虽然不在表格中展开 metadata，但响应已经下载到浏览器，`parseAuditLog` 也接受任何对象内容；“不显示”不等于“不暴露”。

这与 Task 6 Step 1 的明确要求冲突：浏览器只能收到安全对象子集，不能收到可能含密钥或敏感值的原始 metadata。当前已知 writer 主要写入标识符，也不能作为长期安全边界；历史数据、未来 action 或人工写入都可能扩张内容。

**最小修复：**

- 在 HTTP 边界定义专用 `adminAuditLogResponse` DTO。
- 最保守方案是在本轮列表响应中固定返回 `metadata: {}`；若业务确需字段，仅逐 action 白名单允许已确认安全的 ID/计数/状态字段，并拒绝嵌套任意对象、token、email、object key 等内容。
- 不应把脱敏责任放到 React 渲染层。
- 后端测试需构造含 canary secret 的存储结果，断言响应中不出现 canary，同时仍符合 `{"audit_logs":[...]}` envelope。

### I3. 计划要求的管理员 HTTP/RBAC 契约测试尚不存在

**文件：**

- `cloud-api/internal/httpapi/router_test.go`
- `cloud-api/internal/httpapi/middleware_test.go`
- 建议新增或扩展 `cloud-api/internal/httpapi/admin_test.go`

现有 router tests 只覆盖 health/readiness/config/CORS/logging/无 store 时不注册业务路由；middleware tests 只覆盖 limiter/client IP。没有测试以下 Task 6 明确验收矩阵：

- 无 token → 401；
- 无效 token → 401；
- 有效 `role=user` token → 403；
- 有效 `role=admin` token → 200；
- users 精确 object envelope：`{"users":[]}`；
- audit 精确 object envelope：`{"audit_logs":[]}`；
- users 的 `q,limit,offset` 和 audit 的 `limit,offset` 被正确传给 store；
- item 必需字段和审计 metadata 脱敏。

现有代码看起来具备基本 RBAC，但没有可重复证据防止路由组移动、middleware 组合变化、envelope 回退或 metadata 泄漏。这直接未完成 Task 6 Step 1/2，而不仅是一般性的测试增强。

**最小修复：**

- 用 fake `businessStore` 和真实测试 `TokenIssuer` 通过 `NewRouter` 测完整 HTTP 边界，不要只单测 `api.admin`。
- 对两个 GET 端点使用表驱动 401/403/200 用例。
- 200 用例同时断言 store 参数、Content-Type、精确 envelope 顶层键及安全 item shape。
- 加入 disabled user/存储错误的 fail-closed 用例；至少确认不会因 JWT 中的 admin role 绕过 `UserEnabled`。

### I4. Web 没有任何 users 查询或 users/audit 分页入口，默认前 50 条之后永久不可达

**文件：**

- `cloud-api/admin-web/src/api/cloudApi.ts`：`listUsers()`、`listAuditLogs()`
- `cloud-api/admin-web/src/App.tsx`：`loadUsers`、`loadAuditLogs`
- `cloud-api/admin-web/src/components/AdminListPanel.tsx`
- `cloud-api/admin-web/src/api/cloudApi.test.ts`
- `cloud-api/admin-web/src/App.test.tsx`

后端 users 接受 `q,limit,offset`，audit 接受 `limit,offset`，并在参数缺省时固定返回前 50 条。前端始终调用无 query-string 的 URL，`AdminListPanel` 也只有刷新按钮。因此：

- `q` 缺失本身不构成 RBAC 或 envelope 违约；若 Task 6 只要求在小型 fixture 中证明一个 admin 能解析列表，它不是单独的 blocker。
- **但缺少分页是 Important：**任何第 51 条及以后用户/审计记录都无法从控制台访问。设计的最低定义是“管理员能访问已实现的后台列表”，而不是“管理员只能看到存储层默认第一页”。这会使实际内测管理/审计结果不完整。
- 用户查询是后端已明确提供的最小管理能力，应与分页一起接入；不需要为此重做导航、表格、复杂筛选器或引入新的状态库。

**最小、避免 UI 重做的实现范围：**

1. 在 `cloudApi.ts` 增加窄类型：
   - `Pagination = { limit: number; offset: number }`
   - `UserListQuery = Pagination & { q?: string }`
2. 用 `URLSearchParams` 生成 query；`listUsers(query)` 发送 `q,limit,offset`，`listAuditLogs(query)` 发送 `limit,offset`。不要字符串拼接未编码的 q。
3. `AdminListPanel` 只增加共享的上一页/下一页能力，固定 page size（例如 50）即可；无需总数、页码跳转或服务端改 envelope。无 total 时，可在返回条数小于 limit 时禁用下一页；若恰好整页，允许下一次得到空页后回退。
4. users 页面增加一个提交式邮箱搜索框；提交或清空时把 offset 重置为 0。不要做 debounce/自动补全等额外 UI。
5. audit 页面只复用分页，不显示无意义的搜索框。
6. 加 API 测试验证 URL 编码及每类允许参数；加 App/面板测试验证搜索重置 offset、上一页/下一页，以及 audit 不发送 q。

### I5. `API_EXPECTATIONS.md` 仍记录旧的 501/裸数组契约，Task 6 文档对齐未完成

**文件：**

- `cloud-api/admin-web/API_EXPECTATIONS.md`
- `cloud-api/API.md`

后端和前端代码已采用 object envelopes，但 Admin 文档仍称 router 只实现探针、管理员读取为 501，并展示裸数组；还明确说 UI 不猜测分页/筛选。这与实际代码、`cloud-api/API.md` 以及 Task 6 的稳定契约相互矛盾。

这会直接误导后续测试、运维验收和前端维护，并违反 Task 6 Step 3 “两份 API 文档记录相同 envelope”的交付要求。

**最小修复：**

- 将 users 成功响应改为 `{"users":[...]}`，记录 `q,limit,offset`。
- 将 audit 成功响应改为 `{"audit_logs":[...]}`，记录 `limit,offset`。
- 删除“当前仍为 501”“裸数组候选契约”“router 只实现探针”等过期声明。
- 明确 401/403、默认/最大 limit，以及 audit metadata 仅为服务端安全投影。

## RBAC 与双重 `require` 评估

### 当前 RBAC

路由结构是：外层 `api.require` → 内层 admin group 的 `api.admin`。`require` 校验 bearer token、access JWT、subject UUID 和数据库中的 `UserEnabled`，并把 principal 放入 context；`admin` 再检查 role。因此普通用户获得 403，未认证或 invalid/disabled 用户获得 401，后端是权限权威，前端 `/me` 判断只用于 UI/session 清理。

未发现前端可提交 role、通过 sessionStorage 把普通用户变成管理员、或绕过后端 role 检查的路径。JWT role 在 15 分钟 access-token TTL 内保持签发时值属于当前 stateless token 设计；本轮也没有角色变更端点，故不单列为 Important。

### 双重 `require`

`api.admin()` 内部再次调用 `a.require(...)`，而 admin group 已经嵌套在外层 `r.Use(api.require)` 中。因此每个 admin 请求会：

- 解析和验证同一个 JWT 两次；
- 调用 `UserEnabled` 两次；
- 第二次覆盖相同 principal。

它**不是权限绕过，也不削弱 fail-closed 行为，因此不是 Critical/Important blocker**。主要影响是无必要的数据库读、延迟和测试复杂度。在本轮最小修复中可以顺手移除，但不要为此重构整个 middleware 系统。

建议让 `admin` 只读取已有 principal 并 fail closed：无 principal 返回 401，非 admin 返回 403；继续保证它只挂在 `require` 后。用完整 router tests 锁定 middleware 顺序。若团队更看重 defense-in-depth，也可暂时保留双重校验；它不应阻塞 Task 6，但需要接受每个 admin 请求额外一次 DB 查询的成本。

## 其他已检查但不列为 Critical/Important 的事项

- 前端在 `sessionStorage` 而不是 `localStorage` 保存 token，符合本 Task 已批准约束；仍需正常的 XSS 防护，但不是本次发现的新违约。
- 401 最多 refresh 一次，第二次 401 会触发会话清理；非管理员 `/me` 也会清理 sessionStorage，符合计划。
- 403 不自动清除会话是合理的：它可能是资源级拒绝，不应与身份失效混同。
- object envelope 解析严格，不存在 bare-array fallback；契约错误会显示显式失败而非伪造行。
- audit metadata 不在 UI 展开是好的展示层防护，但不能替代 I2 的服务端数据最小化。
- `token_type` 当前只检查非空、发送时固定使用 Bearer。可进一步要求响应精确为 `Bearer`，但真实后端固定返回该值，本轮不提升为 Important。
- 内测默认 HTTP base URL 符合已批准的受控内测边界；不能据此宣称生产就绪。

## 文件级最小修复清单

### `cloud-api/internal/httpapi/router.go`

- 保持 admin group 在 authenticated group 内。
- 可选移除 `admin` 内的重复 `require` 后，以 router tests 锁定顺序。

### `cloud-api/internal/httpapi/business.go`

- 若移除双重 require，使 `admin` 仅检查 context principal，并对 principal 缺失 fail closed。
- users 现有 `q,limit,offset` 逻辑可保留；无需修改 endpoint/envelope。

### `cloud-api/internal/httpapi/admin.go`

- 将 `AuditLog` 转换为安全 response DTO；metadata 固定空对象或严格白名单投影。

### `cloud-api/internal/httpapi/*_test.go`

- 新增真实 router 层 admin 401/403/200、envelope、query 透传、disabled user、store error、metadata canary 脱敏测试。

### `cloud-api/admin-web/src/api/cloudApi.ts`

- 修复 logout 使用 access token。
- 给 users/audit 增加 typed query 参数和 URLSearchParams 编码。
- 可选把 `token_type` 收紧为 `Bearer`。

### `cloud-api/admin-web/src/api/cloudApi.test.ts`

- 覆盖 logout Authorization + refresh body。
- 覆盖 users `q,limit,offset` 编码、audit `limit,offset`、不发送未定义参数。
- 保留严格 envelope 和单次 refresh 测试。

### `cloud-api/admin-web/src/App.tsx`

- 将 users loader 接收 q + pagination，audit loader 接收 pagination。
- 不改变 frontend RBAC 只作反映、backend RBAC 才是权威的现有边界。

### `cloud-api/admin-web/src/App.test.tsx`

- 对 logout 断言 Authorization header。
- 增加 users 搜索/翻页和 audit 翻页的集成测试。
- 保留非 admin 清理 session 测试。

### `cloud-api/admin-web/src/components/AdminListPanel.tsx`

- 仅增加通用 offset pagination；可通过可选 props 为 users 开启提交式 q 搜索。
- 不需要重写表格、导航、样式系统或引入数据获取依赖。

### `cloud-api/API.md` 与 `cloud-api/admin-web/API_EXPECTATIONS.md`

- 对齐实际 object envelopes、参数、401/403、分页默认/上限和安全 metadata 规则。

## 修复后验收门槛

在实施者获准运行验证后，Task 6 至少应提供以下新鲜证据：

1. Cloud HTTP tests：两个 admin GET 的 401/403/200、精确 envelope、参数透传和 metadata canary 不泄漏。
2. Admin Web API tests：logout header、单次 refresh、严格 envelope、users/audit query string。
3. App/panel tests：非 admin 清 session、用户搜索、users/audit 翻页、错误/空态。
4. Task 6 计划中的完整 Go test/vet/build 与 Admin Web test/typecheck/lint/build 均通过。

在 I1–I5 修复且上述验证通过前，不应把 Task 6 标记为 complete。
