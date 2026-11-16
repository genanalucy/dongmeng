> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.superpowers/sdd/2026-08-27-internal-beta-closure-implementation/task-1-report.md`；原始 SHA-256 `a76b0a45abd24c2128e1b8fbca38cbb7759af5190e72475c4bf881789df44f45`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Task 1 Report: Freeze Sources and Create Clean Integration Worktree

## Status

DONE_WITH_CONCERNS

## Actual commands

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

integration=[旧临时路径]
if test -e "$integration"; then
  printf 'integration path already exists\n'
  git -C "$integration" status --short --branch
  git -C "$integration" log -1 --oneline
else
  git -C [旧工作区路径] worktree add -b internal-beta-integration "$integration" 44e042b4d6f131f169b245e32172fc27f6674737
fi

cd [旧临时路径]
git status --short --branch
git rev-parse HEAD
date -u +%F
git diff --check
git check-ignore -v agent/.env.local || true
git add -- docs/reviews/internal-beta-source-manifest.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: record internal beta integration sources"
git diff --check
git diff HEAD --check
git status --short
git status --short --branch
git check-ignore -v agent/.env.local || true
git show --stat --oneline --summary HEAD
```

## Verification results

- Four source worktrees passed `git diff --check`.
- Android source was clean at `35445f1`.
- Cloud API source was at `421498f`; its only untracked entries were `cloud-api/cloud-api` and `cloud-api/cmd/local-reset-admin/`, both recorded as exclusions.
- Admin source was clean at `4f07956`.
- Integration worktree was already present on branch `internal-beta-integration` at the required baseline `44e042b4d6f131f169b245e32172fc27f6674737`; it was clean before the manifest was created.
- Staged manifest passed `git diff --cached --check`.
- Post-commit `git diff --check` and `git diff HEAD --check` passed.
- Post-commit integration status was clean on `internal-beta-integration`.
- `agent/.env.local` is ignored by `.gitignore:15:.env.*`.

## Changed files

- `docs/reviews/internal-beta-source-manifest.md`

## Commit

`97374a9 docs: record internal beta integration sources`

## Concerns

- The primary worktree `[旧工作区路径]` was not at the specified baseline during source inspection: it was at `0281332` and contained pre-existing untracked files/directories. It was not modified or used as an integration input. The integration worktree itself was already correctly pinned to the required baseline.
- The integration path already existed, so no new worktree creation command was run; its branch, baseline, and clean status were verified before work began.

## Fix round 1

### Fixes applied

- Corrected the main-worktree record: at freeze it was `0281332064a3a05f2262dfd8ad8ba3288f16b35a` on `main` with pre-existing untracked content, and was not an integration input.
- Clarified that `44e042b4d6f131f169b245e32172fc27f6674737` is the selected integration base from `main` history, not the `main` HEAD at freeze.
- Replaced abbreviated Android, Cloud API, and Admin source references with their full 40-character SHA values.
- Recorded the integration worktree path, branch, and the fact that the pre-existing path was verified clean at the selected base before reuse.

### Commands run

```bash
cd [旧临时路径]
git status --short --branch
git rev-parse HEAD
git diff --check
git -C [旧工作区路径] rev-parse HEAD
git -C [旧工作区路径] status --short --branch
git -C [旧临时路径] rev-parse HEAD
git -C [旧临时路径] status --short
git -C [旧临时路径] rev-parse HEAD
git -C [旧临时路径] status --short
git -C [旧临时路径] rev-parse HEAD
git -C [旧临时路径] status --short
git diff --check
git status --short
git add -- docs/reviews/internal-beta-source-manifest.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: clarify internal beta source freeze"
git diff --check
git diff HEAD --check
git status --short --branch
git log -1 --format='%H%n%s'
```

### Verification

- Before modification, integration worktree was clean at `97374a90824fe3a24cb4eda0252bf12237d9a946` on `internal-beta-integration`.
- Source verification observed full SHAs: Android `35445f18f743752310e595df6378d433ec7c7fd1`; Cloud API `421498fe08ae470757f0e2746b09c57efe654b98` (only the explicitly excluded untracked binary and `cmd/local-reset-admin/`); Admin `4f07956d2560b44bb6fa92c9486c7cc9a319aa7f`.
- `git diff --check`, `git diff --cached --check`, and post-commit `git diff HEAD --check` passed.
- The only staged and committed file was `docs/reviews/internal-beta-source-manifest.md`.
- Post-commit integration worktree is clean on `internal-beta-integration`.

### Fix-round commit

`1c6a2e05200f335c0a1a6f158f85440da4370503 docs: clarify internal beta source freeze`

### Remaining concern

The main worktree remains at `0281332064a3a05f2262dfd8ad8ba3288f16b35a` with pre-existing untracked files/directories. It was neither modified nor used as an integration input; the committed manifest records this distinction.
