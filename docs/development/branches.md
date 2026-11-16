# 分支与标签收口清单

效力：2026-11-17 任务10在整合 `27eabab` 的采样。完整SHA标识这次判断的来源；后续分支移动以Git/PR为准。`local` 是本地分支，`remote` 是GitHub实际分支（初次API核对15个；主编排者退役两条后为13个），两者同名不保证同SHA；不把远端缓存当实时远端。四个已退役切片另见[工作树说明](workspace.md#任务10收口状态)。

下表每个引用均已被退役前的独立本地bundle覆盖。提交不可达数与补丁等价都不能代替功能验收；除四个本地切片外，主编排者另按下文保全证据退役两条远端旧发布分支，其他引用保留。本轮worker最终review后再由主编排者统一处理。

| 位置 / ref | 采样SHA | 分组与处理 |
| --- | --- | --- |
| local / `refs/heads/agent/ticket04-debug-settings` | `ba0d3989881c97c30b2c87262e0abcbece7da9ca` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/android` | `8f75abd5b003f065d7f6fffc04ee5d3be82dc5d3` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/android-cloud-auth` | `35445f18f743752310e595df6378d433ec7c7fd1` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/android-test-endpoints` | `0fc66d029e9d0729cbaebd96f4ab25673c47e24b` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/android-ui-redesign` | `8e625a67ebcffe0a5776228f5ac4ba907f05391a` | 旧UI/实验来源；保留：本地研究与私有配置；远端为另一个实验分叉，不能混同 |
| local / `refs/heads/azure-auto-omit-from` | `a32f2159ed1ec5fcd6009e3c7489b79cb012e7b2` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/azure-experiments` | `aeec6aa3b8b55dffaf91b752dbbb24c2ee224f16` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/baseline/20261104/android-integration` | `8e625a67ebcffe0a5776228f5ac4ba907f05391a` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| local / `refs/heads/baseline/20261104/azure-experiments` | `aeec6aa3b8b55dffaf91b752dbbb24c2ee224f16` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| local / `refs/heads/baseline/20261104/legacy-remote-ui` | `a32f2159ed1ec5fcd6009e3c7489b79cb012e7b2` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| local / `refs/heads/baseline/20261104/local-main` | `bec885983928973849757f5d31df38b903b116a8` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| local / `refs/heads/baseline/20261104/remote-main` | `3ca49501390eefdcefb47e6da0f0fe271b1f993a` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| local / `refs/heads/chore/android-main-baseline` | `f26d6902fc8586a89d5b6bb8f7e4bda90ab1b334` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/chore/repo-organization` | `27eababe56c1704a471fa34b0f2437cd29434f8d` | 本轮整合；保留：日常整理入口与PR #2来源 |
| local / `refs/heads/chore/repo-organization-01` | `bc389e03b1bcd17fdeeaf5d495950ec3f71f14ca` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-02` | `f1796655c46648198d1a7d7e57557f4c5b022334` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-03` | `8f6aec652fe5bcfe847fa859845b18cffa572684` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-04` | `9a428d6320505cbcd7172916ee8cc2b02e5143e1` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-05` | `0a5acb795c20cc71d51c64c5b4b6c8d8776641c1` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-06` | `8960f38b4a26afb4cb4bc730e776e37d7f91d611` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-07` | `ba9f23d5ae085cd32801335ed2eaea92db2b792e` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-08` | `275e8ad1f9428d8c49a86068b5d50e9f191d1dcf` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-09` | `e55c2b82491d3349f58f12c24536d00a3714cd2c` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/chore/repo-organization-10` | `27eababe56c1704a471fa34b0f2437cd29434f8d` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/cloud-admin` | `1b9f83d7987ae51fccecc798734433f18ba25483` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-admin-live` | `4f07956d2560b44bb6fa92c9486c7cc9a319aa7f` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-api-business` | `421498fe08ae470757f0e2746b09c57efe654b98` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-auth` | `4c27e0fc17baec4578c5aee945add0dbab43cac8` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-platform` | `3bf5574e5c6627eef081484dc2b2d75dfbb18215` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-qa` | `2168819bb67fb6d54e3d720edb1186e9f45594af` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/cloud-tests` | `3932097987e07cbdf9d7e51867fa22624e991841` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/fix/internal-email-login-regression` | `188309f9556b3d0ff473cc2804b77ae2b20b4b6f` | 本轮worker；保留：最终review后由主编排者统一清理，不在任务10删除 |
| local / `refs/heads/internal-beta-integration` | `4e01129e29c0c6b3b53a11f7e240b6d63bfb20d5` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/luna-ticket02` | `fc355eca61f03a4f3d5684d807afc32757285048` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/luna-ticket03-face-manual` | `c15862a0de4828335f0d2435cd622d66296ab6b3` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/luna/ticket04-face-continuous` | `748adc1ca9870a9c884609f2762be5ae3546af59` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/main` | `bec885983928973849757f5d31df38b903b116a8` | 旧根来源；保留：远端共同主线 / 本地旧根未提交成果，各自身份不同 |
| local / `refs/heads/pi-agent-0338a7ea-29f2-4bf` | `9848f09be51dd3b0a50d7b3bdf086912575554a4` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/pi-agent-1591429d-eac5-4d8` | `087e3cb5f1b4041ada543b4cf1649260c3b036c3` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/pi-agent-36c6a7c9-a6a1-4ed` | `d53bf38ae7921b426ec325d7194a77ce037c8883` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/pi-agent-40263215-671c-40b` | `ba0d3989881c97c30b2c87262e0abcbece7da9ca` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/pi-agent-885dce99-46d4-432` | `9c69e34ab8313feb94da8738e8857197e46dc63d` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/pi-agent-9323a747-368c-4f3` | `01309815f85c29211d7a1a50c0586d743bc9cbb3` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| local / `refs/heads/release/yilu-production-v21` | `20da583d39eb39d47e2ad104143f4ab185317b1a` | 正式来源；保留：正式v21独有发布提交 |
| local / `refs/heads/test/android-phase1-baseline` | `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` | 候选来源；保留：PR #1与v30后续发布资料来源 |
| local / `refs/heads/ui-solo-slice` | `2851e7adce1e8b992e17016f869646d190bfa00d` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| remote / `refs/heads/android` | `0fc66d029e9d0729cbaebd96f4ab25673c47e24b` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| remote / `refs/heads/android-cloud-auth` | `35445f18f743752310e595df6378d433ec7c7fd1` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| remote / `refs/heads/android-debug-beta-20260920-liquid-glass` | `c1ac3f1b70de70a2263fb3041d761833a7a970dd` | 旧发布分支；已由主编排者退役远端分支：同SHA发布tag保留、bundle覆盖、无注册工作树/开放PR；exact SHA lease与atomic删除后复核head不存在 |
| remote / `refs/heads/android-ui-redesign` | `a32f2159ed1ec5fcd6009e3c7489b79cb012e7b2` | 旧UI/实验来源；保留：本地研究与私有配置；远端为另一个实验分叉，不能混同 |
| remote / `refs/heads/baseline/20261104/android-integration` | `8e625a67ebcffe0a5776228f5ac4ba907f05391a` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| remote / `refs/heads/baseline/20261104/azure-experiments` | `aeec6aa3b8b55dffaf91b752dbbb24c2ee224f16` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| remote / `refs/heads/baseline/20261104/legacy-remote-ui` | `a32f2159ed1ec5fcd6009e3c7489b79cb012e7b2` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| remote / `refs/heads/baseline/20261104/local-main` | `bec885983928973849757f5d31df38b903b116a8` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| remote / `refs/heads/baseline/20261104/remote-main` | `3ca49501390eefdcefb47e6da0f0fe271b1f993a` | 来源快照；保留：命名快照服务恢复与差异定位，不因可达性批删 |
| remote / `refs/heads/chore/repo-organization` | `27eababe56c1704a471fa34b0f2437cd29434f8d` | 本轮整合；保留：日常整理入口与PR #2来源 |
| remote / `refs/heads/internal-beta-integration` | `4e01129e29c0c6b3b53a11f7e240b6d63bfb20d5` | 历史实现/实验；保留：独有语义或恢复用途尚未逐项验收，已备份不等于可删除 |
| remote / `refs/heads/main` | `f26d6902fc8586a89d5b6bb8f7e4bda90ab1b334` | 共同主线；保留：远端共同主线 / 本地旧根未提交成果，各自身份不同 |
| remote / `refs/heads/production-v20-20260915` | `87e8ce47e406e257cd84ce97a346d3d0920c1391` | 旧发布分支；已由主编排者退役远端分支：同SHA发布tag保留、bundle覆盖、无注册工作树/开放PR；exact SHA lease与atomic删除后复核head不存在 |
| remote / `refs/heads/release/yilu-production-v21` | `20da583d39eb39d47e2ad104143f4ab185317b1a` | 正式来源；保留：正式v21独有发布提交 |
| remote / `refs/heads/test/android-phase1-baseline` | `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` | 候选来源；保留：PR #1与v30后续发布资料来源 |

## 发布标签与远端退役记录

本地现有发布标签全部保留：

- `refs/tags/android-debug-internal-beta-20260910-3b6fb7c` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260911-azure` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260912-auto-entry` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260912-continuous-auto` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260912-stability` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260913-auto-json` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260913-auto-mode-ui` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260913-diagnostic-log` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260914-log-export` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-debug-internal-beta-20260915-0225` → `87e8ce47e406e257cd84ce97a346d3d0920c1391`
- `refs/tags/android-debug-internal-beta-20260920-liquid-glass` → `c1ac3f1b70de70a2263fb3041d761833a7a970dd`
- `refs/tags/android-production-20260910-3002067` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-production-20260913-72e8f75` → `3ca49501390eefdcefb47e6da0f0fe271b1f993a`
- `refs/tags/android-production-20260915-v20` → `87e8ce47e406e257cd84ce97a346d3d0920c1391`

本地没有v29/v30标签ref不表示源码缺失；其完整来源见[版本清单](source-inventory.md)。远端正式/Debug标签与Release不受本批处理影响。

以下两条**远端分支**已由主编排者于 2026-11-16 21:31:36 UTC（北京时间11月17日）退役；本worker没有执行push：

- `refs/heads/android-debug-beta-20260920-liquid-glass` → `c1ac3f1b70de70a2263fb3041d761833a7a970dd`：与 `refs/tags/android-debug-internal-beta-20260920-liquid-glass` 相同SHA；完整历史在bundle的 `refs/remotes/origin/android-debug-beta-20260920-liquid-glass` 中，无注册工作树或当前开放PR使用此远端head。主编排者再次核对远端SHA、开放PR与标签/bundle后，使用atomic及exact SHA force-with-lease删除；随后确认两个head不存在、三个同SHA标签未变。标签、Release、bundle继续保留，不删除其他远端来源。
- `refs/heads/production-v20-20260915` → `87e8ce47e406e257cd84ce97a346d3d0920c1391`：与 `refs/tags/android-debug-internal-beta-20260915-0225`, `refs/tags/android-production-20260915-v20` 相同SHA；完整历史在bundle的 `refs/remotes/origin/production-v20-20260915` 中，无注册工作树或当前开放PR使用此远端head。主编排者再次核对远端SHA、开放PR与标签/bundle后，使用atomic及exact SHA force-with-lease删除；随后确认两个head不存在、三个同SHA标签未变。标签、Release、bundle继续保留，不删除其他远端来源。

其余历史实现分支保留，是因为尚未完成逐项语义退出判断；来源快照保留，是因为其命名本身提供恢复定位。PR #1/#2、main、正式v21和原旧根/integration来源均不得按重复引用批删。具体本机路径、保全日志和逐项Git证据留在本地执行报告。
