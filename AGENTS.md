<!-- codebase-memory-mcp:start -->
# Codebase Knowledge Graph (codebase-memory-mcp)

This project uses codebase-memory-mcp to maintain a knowledge graph of the codebase.
ALWAYS prefer MCP graph tools over grep/glob/file-search for code discovery.

## Priority Order
1. `search_graph` — find functions, classes, routes, variables by pattern
2. `trace_path` — trace who calls a function or what it calls
3. `get_code_snippet` — read specific function/class source code
4. `query_graph` — run Cypher queries for complex patterns
5. `get_architecture` — high-level project summary

## When to fall back to grep/glob
- Searching for string literals, error messages, config values
- Searching non-code files (Dockerfiles, shell scripts, configs)
- When MCP tools return insufficient results

## Examples
- Find a handler: `search_graph(name_pattern=".*OrderHandler.*")`
- Who calls it: `trace_path(function_name="OrderHandler", direction="inbound")`
- Read source: `get_code_snippet(qualified_name="pkg/orders.OrderHandler")`
<!-- codebase-memory-mcp:end -->

## Development baseline

Android 是主要开发方向；恢复开发、切换工作树、选择测试包或发布前，阅读 `docs/development/baselines.md`。共享基线为 GitHub `main`，旧 `android-ui-redesign` 远端含实验分叉，按基线文档选择来源。

## 变更方案与交付

- 后续功能实施前，先用中文说明用户可见行为、影响模块和验收步骤，由用户确认方案。本轮目录与 GitHub 整理已获授权，按已确认范围执行，不逐文件重复审批；整理期间暂停新增功能，必要故障修复独立说明范围、来源和验证。
- 所有修改完成后都向用户提供中文交付说明：目的与实际变化、影响模块及上下游、旧实现处理、实际验证与未验证部分。按实际情况区分新增、修改、删除和保留；界面、行为和缺陷修复分别说明。只有核实过的范围才能声称已清理。
- PR 使用 [Summary / Evidence / Merge Danger 模板](.github/pull_request_template.md)，交付说明沿用其中字段，按规模简写；提供提交或差异入口，验证注明执行目录、命令、结果及证据来源，区分本次实测、既往记录和用户人工验收。参见 [真实整理示例](docs/development/change-report-example.md)。
- 日常从共同主线 `main` 开临时任务分支，通过 PR 记录检查和适用的用户验收，再整合回主线；当前整理候选的来源与入口见 [工作树说明](docs/development/workspace.md)。单人维护可由项目所有者审阅、验收与合入，不强制第二人审批。合入源码、人工验收、部署和标签 / Release 发布分别记录，合入不自动授权部署或发版。

## Agent skills

### Issue tracker

工作项使用本地 Markdown，位于 `.scratch/<feature>/`。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认五个 triage 标签。见 `docs/agents/triage-labels.md`。

### Domain docs

采用单一上下文：根目录 `CONTEXT.md` 与 `docs/adr/`。见 `docs/agents/domain.md`。

## superpowers skills overrides

### subagent-driven-development

Modified agent selection and escalation flow:

1. **BLOCKED** → Orchestrator collects fix_hint, escalates via `subagent_type: expert`
2. **`expert` subagent BLOCKED** → mark task failed, return to user

Otherwise follow the skill exactly as written.
