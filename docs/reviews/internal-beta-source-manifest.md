# Internal Beta Integration Source Manifest

- Frozen date (UTC): 2026-08-27
- Main worktree observed at freeze: `0281332064a3a05f2262dfd8ad8ba3288f16b35a` (`main`); it had pre-existing untracked content and was not used as an integration input.
- Selected integration base: `44e042b4d6f131f169b245e32172fc27f6674737`, chosen from `main` history; it is not a statement of the `main` HEAD at freeze.
- Android cloud-auth source: `35445f18f743752310e595df6378d433ec7c7fd1` (`android-cloud-auth`)
- Cloud API business source: `421498fe08ae470757f0e2746b09c57efe654b98` (`cloud-api-business`)
- Cloud admin live source: `4f07956d2560b44bb6fa92c9486c7cc9a319aa7f` (`cloud-admin-live`)

## Integration worktree

- Path: `/tmp/dngmeng-internal-beta-integration`
- Branch: `internal-beta-integration`
- The path already existed when Task 1 began. It was verified clean and at selected integration base `44e042b4d6f131f169b245e32172fc27f6674737`, then reused; no existing authoritative worktree was modified.

## Integration rule

Only content reachable from the committed history at the selected integration base and source refs above is an integration input. Working-tree changes and untracked files are excluded, even when they are present in a source worktree.

## Explicit exclusions

The following are not integration inputs and must not be copied, staged, or committed:

- `cloud-api/cloud-api` local binary
- `cloud-api/cmd/local-reset-admin/`
- `agent/.env.local`
- all `.env*` files
- APKs
- Gradle caches and Node caches
- FRP configuration
- root untracked directories, including root `android/`, `cloud-api/`, `docs/`, `ui/`, and `.pi/`
- CSV files, database exports, temporary scripts, binaries, and other generated or local-only artifacts
