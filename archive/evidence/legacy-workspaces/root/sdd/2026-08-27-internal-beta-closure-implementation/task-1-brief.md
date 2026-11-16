> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-1-brief.md`；原始 SHA-256 `887f938a7dc5bb5e1861edfff4bc72284538a30541d9c48becaab820dfef8285`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

## Task 1: 冻结来源并创建干净集成 worktree

**Files:**
- Create: `docs/reviews/internal-beta-source-manifest.md`
- Do not copy: root untracked `android/`, `cloud-api/`, `docs/`, `ui/`, `.pi/`.

**Consumes:** 当前已批准规范和四个权威提交。

**Produces:** 一个仅包含已提交历史的 integration worktree；来源清单供后续任务使用。

- [ ] **Step 1: 验证四个权威 worktree 的 commit 与污染情况**

Run:
```bash
for repo in \
  [旧工作区路径] \
  [旧临时路径] \
  [旧临时路径] \
  [旧临时路径] do
  printf '\n== %s ==\n' "$repo"
  git -C "$repo" status --short --branch
  git -C "$repo" log -1 --oneline
  git -C "$repo" diff --check
 done
```

Expected: `main` is `44e042b`; Android is clean at `35445f1`; Cloud API source is `421498f` while its local binary and `cmd/local-reset-admin/` remain explicitly untracked and excluded; Admin is clean at `4f07956`.

- [ ] **Step 2: 创建隔离整合 worktree，不修改现有 worktree**

Run from repository root:
```bash
integration=[旧临时路径]
test -e "$integration" && { echo "integration path already exists"; exit 1; }
git worktree add -b internal-beta-integration "$integration" 44e042b
```

Expected: `git -C "$integration" status --short --branch` only shows `internal-beta-integration` and no working-tree changes.

- [ ] **Step 3: 写入来源和排除项清单**

Record the exact source refs, date, and exclusions in `docs/reviews/internal-beta-source-manifest.md`. It must state that `cloud-api/cloud-api`, `cloud-api/cmd/local-reset-admin/`, `agent/.env.local`, all `.env*`, APKs, Gradle/Node caches, FRP configuration, and root untracked directories are not integration inputs.

- [ ] **Step 4: Verify source manifest and worktree cleanliness**

Run:
```bash
git -C [旧临时路径] diff --check
git -C [旧临时路径] status --short
git -C [旧临时路径] check-ignore -v agent/.env.local || true
```

Expected: no diff errors; no unintended files; local credential file is ignored.

- [ ] **Step 5: Commit only the source manifest**

```bash
cd [旧临时路径]
git add -- docs/reviews/internal-beta-source-manifest.md
git diff --cached --check
git commit -m "docs: record internal beta integration sources"
```
