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
