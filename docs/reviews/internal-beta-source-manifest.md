# Internal Beta Integration Source Manifest

- Frozen date (UTC): 2026-08-27
- Integration baseline: `44e042b4d6f131f169b245e32172fc27f6674737` (`main`)
- Android cloud-auth source: `35445f1` (`android-cloud-auth`)
- Cloud API business source: `421498f` (`cloud-api-business`)
- Cloud admin live source: `4f07956` (`cloud-admin-live`)

## Integration rule

Only content reachable from the committed history at the source refs above is an integration input. Working-tree changes and untracked files are excluded, even when they are present in a source worktree.

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
