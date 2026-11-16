> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-6-fix-round-1-report.md`；原始 SHA-256 `6f5fb4ad397794745befa67f6e3ad65292bef95535035697cfa6d66efea99cfe`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 6 I6 fix round 1 report

日期：2026-08-28
代码范围：`[旧临时路径]`

## 修复

`AdminListPanel` 为每次 `loadPage` 分配递增 request generation。异步请求完成后，只有 generation 仍为最新值时，才可以更新 `state`、`offset` 或最近非空页面缓存。

最近非空缓存现绑定 `search`、`offset` 和 `data`。empty-next 回退只允许恢复相同搜索词且恰为上一页的数据，因此旧查询、旧成功、旧空响应或旧错误均不能污染当前查询、页码或回退数据。

未将搜索按钮禁用作为并发正确性的替代措施；现有可访问名称、`aria-busy`、固定 50 条分页和无敏感数据展示均保持不变。

## 回归测试

`AdminListPanel` 使用 deferred Promise 覆盖：

- 初始请求 A 后提交新搜索 B，B 先完成、A 后完成时仍显示 B；
- 旧非空结果不污染 B 的 empty-next 回退；
- 旧空响应不显示空态；
- 旧 error 响应不覆盖 B 为错误态。

这些测试在修复前复现了旧响应覆盖新搜索以及旧空响应显示空态的问题；修复后通过。

## 验证

在 `cloud-api/admin-web` 执行，均通过：

```text
npm test                 # 3 test files, 18 tests passed
npm run typecheck
npm run lint
npm run build
```

`npm run build` 完成 Vite 生产构建。`git diff --check` 通过；Git 变更白名单仅包含：

```text
cloud-api/admin-web/src/components/AdminListPanel.tsx
cloud-api/admin-web/src/components/AdminListPanel.test.tsx
```

未发现未跟踪的 `dist/**` 或 `node_modules/**` 构建工件，未纳入提交。

## 限制

遵循任务约束，未运行真实浏览器、Cloud API、数据库，且未派遣子代理。
