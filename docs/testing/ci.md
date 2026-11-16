# CI 触发与覆盖边界

效力：现行配置说明；来源是仓库的四份 [workflow](../../.github/workflows/) 与 [路径选择 CLI](../../scripts/ci-scope.py)，不是 GitHub 运行成功或分支保护设置的证明。v30 仍为用户已更新、尚未完成用户验收。

`Android CI`、`Android Motion Regression` 与 `Product checks` 对所有 PR、推送到 `main` / `android` / `android-ui-redesign` / `test/android-phase1-baseline`、手动触发生效。它们均调用 `Select CI checks`，外层不设 `paths` 过滤，避免消费者 workflow 在选择之前就被漏掉。纯文档 PR 会启动选择与导航，Android job 显示跳过；不能要求这种 PR 的 Android job 必须实际运行。远端 required checks / branch protection 本轮没有修改或核实。

PR 从 base/head 的 merge-base 比较；push 比较完整 before/after 范围。`git diff --no-renames -z` 保留删除路径、改名前后路径及带空格/换行的文件名。手动运行、首次推送没有 before SHA 时选择全部；Git 比较失败会令选择 job 失败，不悄悄缩小范围。CI 不设 GitHub `paths` 过滤，因此不受该过滤器仅检查前 300 个文件的限制。

| 代表性变化 | `Product checks` 调用的 `bash scripts/check.sh` target | Android job |
|---|---|---|
| `README.md`、`docs/**`、`archive/**`（含归档源码）、模块内 `.md/.rst/.txt` | `navigation` | 跳过 |
| `android/app/src/**`、Android 构建配置 | `navigation` | `verify`、`motion` |
| `agent/internal/**`、`cloud-api/internal/**`、两侧 Go 依赖、`cloud-api/contracttest/**`、`migrations/**`、`docker-compose.yml` | `agent officialast cloud contract admin-web web navigation`，另有 `database` job 调用 `db` | `verify`、`motion` |
| `cloud-api/admin-web/src/**` / 管理端锁文件 | `admin-web navigation` | 跳过 |
| `web/src/**` / 辅助 Web 锁文件 | `web navigation` | 跳过 |
| `site/download/releases.json`、下载站页面 | `download navigation` | 跳过 |
| `scripts/prepare-download-release.py` 等普通脚本 | `download navigation tooling` | 跳过 |
| `scripts/prepare-official-ast.sh` | 与服务端变化相同 | `verify`、`motion` |
| `.pi/**`、`openspec/**` 非文档、忽略规则 | `navigation tooling` | 跳过 |
| `branding/**`、`ui/**` 参考资源 | `navigation` | 跳过 |
| workflow、`scripts/check.sh`、`scripts/ci-scope.py`、`Makefile`、未分类活动路径 | 全部非 Android 自动 target，另加 `db` | `verify`、`motion` |
| `android/**` + 下载脚本 | `download navigation tooling`，按路径取并集 | `verify`、`motion` |

表中产品目录规则均排除第一行的文档/归档情形。服务端规则有意保守：即使改动看似仅影响供应商实现，也检查既有消费者；这不等于已建立逐函数的影响分析。模块依赖来源见[模块地图](../architecture/README.md)。

`Product checks / checks` 按 target 分开执行、互不因其他组失败而取消。Go 使用 1.25.0；两个前端用 Node 22 和各自 `npm ci`；`officialast` 先运行已有固定 URL/SHA-256 准备脚本，然后调用现行 `officialast` target。没有加载本机 `.env` 或服务凭据。每组上传 `check-<target>` 日志；独立 `database` 上传 `check-database`。

数据库使用每个 job 新建的 PostgreSQL 16 服务，只映射 `127.0.0.1:15432`，服务健康检查通过后开始步骤。YAML 中的用户名、密码仅属于该一次性测试服务；初始化用户具有建角色权限，供既有权限测试创建隔离角色。显式 `db` target 保留安全门和 JSON 结果检查：任意 skip、无已完成测试、编译或测试失败都会让 job 失败；不会因为连接变量存在就报告通过。没有连接生产环境。

| 实际执行层次 | 能说明什么 | 不能说明什么 |
|---|---|---|
| Agent 默认/officialast、Cloud `go test/vet/build` | 各模块现有包行为、编译与静态检查 | 非数据库完整链路、非真实供应商翻译 |
| 独立 `cloud-api/contracttest` 模块 | Cloud 签发与 Agent 解析授权约定一致 | 非网络跨进程、非 Android 客户端 |
| `db` integration suite | 专用 PostgreSQL 迁移、存储、权限、并发治理；Cloud HTTP 与独立 Agent harness 进程的授权握手 | Cloud HTTP 使用测试服务器、Agent 使用 fake provider；不含真实供应商、Android/音频链路 |
| 两个前端 target | 各自 npm 单测、类型、lint、构建 | 非真实浏览器人工操作与服务器部署 |
| `Android CI / verify` | 既有 JVM 单测、lint、Debug 构建 | 非设备/供应商/跨设备验收 |
| `Android Motion Regression / motion` | 既有 API35 x86_64 模拟器中显式列出的 10 个测试类 | 新测试类不会自动加入显式列表；非真实手机、麦克风、SAF 文件提供方 |
| `download`、`navigation`、`tooling` | 本地发布清单/路由模拟、当前本地 Markdown 链接、脚本与路径选择行为 | 不部署、不下载公网 APK、不验证外部 URL；导航默认范围见统一入口 |

Android 原有 job id、Gradle/模拟器步骤、wrapper 校验及 `android-verification` / `android-motion-regression` artifact 保留。路径扩围会增加服务端 PR 的 Android 检查成本；文档专用 PR 无需全产品重测。

本地可复现选择器：

```bash
git diff --name-only --no-renames -z BASE HEAD | python3 scripts/ci-scope.py
python3 scripts/ci-scope.py --all
bash scripts/check.sh tooling navigation download
```

`tooling` 包含代表性文档、提供方、消费者、组合变化、未知路径、手动全选与真实 Git 改名的行为测试；同时检查三个实际 workflow 均接入共享选择、没有外层路径过滤。YAML/Actions 表达式另用 actionlint 1.7.7 校验，不能以普通 YAML 解析代替。此处是本地验证入口；GitHub runner 是否成功须查看对应运行，真机验收仍按[人工清单](manual-acceptance.md)独立记录。本轮结果见[整理检查记录](repo-organization-20261117.md)。
