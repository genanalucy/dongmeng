# 检查与验收入口

效力：现行检查说明。自动检查保护现有行为；自动结果、发布记录和用户体验验收分别记录。v30 已更新，尚未完成用户验收。版本和工作树选择见[开发入口](../development/workspace.md)。

仓库根目录的统一入口无需安装 make，也可从其他目录用脚本绝对路径运行：

```bash
bash scripts/check.sh --help
bash scripts/check.sh navigation download
bash scripts/check.sh agent cloud contract admin-web
bash scripts/check.sh all
```

`make test` / `make check` 转发到 `all`。原有 `web-test` 等细分 Make 目标继续可用。`all` 覆盖所有自动检查组，缺少数据库配置时将数据库列为 SKIP，人工验收始终为 SKIP；它不表示完整产品验收。不要用辅助 Web 原型测试通过代替 Android 产品验收。

每组输出 `PASS`（该组全部命令成功）、`FAIL`（失败）、`MISSING_ENV`（所需工具/依赖/显式配置缺失）或 `SKIP`（已说明未执行），最后给出计数。失败后仍检查后续组，组内首个失败即停止。退出码：0 表示所选自动检查成功，可能含明确 SKIP；1 表示有 FAIL；2 表示缺环境或参数错误。有 FAIL 时优先返回 1。记录命令、提交、运行时间、退出码与状态行，不把“退出 0”简写成“全产品通过”。

| target | 实际运行 | 覆盖边界与前置条件 |
|---|---|---|
| `android` | Gradle `testDebugUnitTest lintDebug assembleDebug` | JDK21、SDK36/build-tools36、wrapper依赖；不运行设备测试 |
| `agent` | Agent `go test / vet / build ./...` | Go；默认构建，不做供应商真实请求 |
| `officialast` | Agent 同上，加 `-tags officialast` | 先显式准备四个官方 protobuf；不等于真实供应商翻译验收 |
| `cloud` | Cloud 主模块 `go test / vet / build ./...` | 不跨嵌套 `go.mod`，不含 integration tag |
| `contract` | `cloud-api/contracttest` 独立模块同上 | 必须单独运行，保护 Cloud/Agent 授权 token 约定；不等于跨进程集成 |
| `admin-web` | 管理端 npm `test typecheck lint build` | 独立安装管理端依赖 |
| `web` | 辅助 Web npm 同上 | 辅助原型与协议调试工具，不是完整产品 |
| `download` | 既有四项 Python 单测与发布脚本 `bash -n` | 本地清单、快照和路由迁移；不上传、不部署、不访问公网APK |
| `navigation` | 当前首页、代理入口、领域入口、文档/归档索引（含archive/indexes各批次）、testing/architecture与工作树说明的本地 Markdown 链接 | 只验证目标存在，不验证标题锚点、外部URL、裸代码路径；不扫描历史正文的旧约束 |
| `tooling` | `scripts/*.sh` Bash语法；检查CLI和导航的行为测试 | Python3/Bash；工具技能安装另见其说明，不复制本机缓存 |
| `db` | Cloud `go test -json -tags=integration -count=1 ./integration` | 显式专用PostgreSQL；包含启动独立 Agent harness 的跨进程授权测试；任何skip或没有完成测试均FAIL |
| `manual` | 输出人工验收指针 | 始终SKIP，见[真机验收](manual-acceptance.md) |

Go组通过`go list ./...`发现当前模块包，仅排除`node_modules`下第三方包；所有保留包均执行test/vet，普通源码包执行build，仅含测试的包由test编译。独立contracttest仍须单独选择。

环境准备使用[工作树环境说明](../development/workspace.md)，不复制旧 `.env`、凭据或 `local.properties`。脚本不会自动加载环境文件、启动数据库、运行独立迁移/发布命令或调用 smoke-local；显式`db`会按既有集成测试准备、修改并清理专用测试库。Go/npm/Gradle可能下载公开依赖，相关失败会保留原始输出。预检只能识别常见缺环境；依赖损坏或构建工具自行报告的错误记为FAIL。

```bash
npm --prefix web ci
npm --prefix cloud-api/admin-web ci
# 固定URL与SHA-256核对；下载供应商protobuf到被忽略目录，准备一次后复用：
bash scripts/prepare-official-ast.sh
# SDK由当前机器定位，不在仓库记录个人绝对路径：
ANDROID_HOME="$ANDROID_SDK_ROOT" bash scripts/check.sh android
```

显式数据库检查只接受由现有测试安全门验证的专用 `127.0.0.1:15432` 连接；设置 `CLOUD_API_TEST_DATABASE_URL` 后运行 `bash scripts/check.sh db`。不要把连接字符串放进命令参数、报告或截图。隔离库准备和安全门见[Cloud说明](../../cloud-api/README.md)。缺变量时显式 `db` 返回MISSING_ENV；`all` 则SKIP。数据库存在不代表测试通过，报告必须无skip。该harness不含Android或真实供应商调用。

按改动范围选择：

| 改动范围 | 最少自动入口 | 额外验收 |
|---|---|---|
| Android UI、会话、保存/导出 | `android` | 现有 Android Motion Regression 设备工作流；真机音频/文件提供方见验收表 |
| Agent协议或授权 | `agent officialast contract`，涉及账户/会话再加 `cloud db` | 两种翻译模式、取消恢复、真实环境连通性 |
| Cloud账户/历史/管理员API | `cloud contract admin-web`；存储/事务/权限加 `db` | 登录、跨设备同步与删除优先 |
| 管理端界面 | `admin-web`；接口变化加 `cloud` | 浏览器操作与权限表现 |
| Web辅助工具 | `web`；协议变化加 `agent officialast contract` | 辅助工具浏览器操作 |
| 下载站/工具/版本清单 | `download navigation` | 公网页面、更新来源与APK身份，实际发布另行授权 |
| 文档/归档/导航 | `navigation`；检查脚本/入口加 `tooling` | 对新增/移动的非默认文档显式检查链接 |

```bash
python3 scripts/check-navigation.py docs/某个现行说明.md archive/某组/README.md
```

显式检查只读取所给文档，不因存在旧文档就把历史说明升级为当前规范。已知缺链须记录来源和未验证范围，不能通过新增忽略列表掩盖。本轮实际运行证据见[整理检查记录](repo-organization-20261117.md)；以前发布时的检查数字见[下载发布说明](../deployment/download-center.md)，不能当成本轮重跑结果。
