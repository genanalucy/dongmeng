# 2026-11-17 整理检查记录

效力：本轮技术验证证据，验收状态不随自动检查通过而改变。源码基线为 v30 `cd920a1` 及发布资料 `dc587ef`，任务08从整理分支 `8d06231` 建立隔离工作树；本文件及检查入口的准确版本以所在Git提交为准。

本轮未读取或复制私有环境文件，未连接生产服务、部署、上传APK或执行收费语音；使用公开依赖、既有本机SDK与构建缓存。make 不在本机PATH，实际使用Bash入口，未宣称运行过make。图MCP不可用，配置与脚本调查采用Git对象和必要文件阅读回退。

| 实际命令/目标 | 本轮结果 | 证据与限制 |
|---|---|---|
| `bash scripts/check.sh android`（命令级指定SDK） | PASS | 441 JVM测试，0失败/错误/跳过；lint 0Error、50Warning、2Hint；Debug构建成功。未执行设备测试 |
| `bash scripts/check.sh agent` | PASS | 默认Go test/vet/build，部分结果使用Go缓存 |
| `bash scripts/prepare-official-ast.sh` 后 `officialast` | PASS | 固定URL/SHA-256准备四个protobuf；tagged test/vet/build；没有真实供应商请求 |
| `cloud` | PASS | test/vet/build当前包；已排除管理端node_modules的第三方Go示例；test-only包参加test/vet，由test完成编译 |
| `contract` | 初次FAIL，修复后PASS | 独立模块缺既有captcha依赖的校验记录。仅补与Cloud现有版本一致的间接依赖和go.sum，不改版本、业务或token约定 |
| 两个目录分别 `npm ci` 后 `web admin-web` | PASS | Web 126项/16文件，管理端34项/4文件；各自typecheck/lint/build通过；不是Android验收 |
| `download` | PASS | 既有4项Python单测、部署脚本语法；没有部署、下载或核验真实APK |
| `tooling` | PASS | 8项CLI行为测试、3项导航行为测试、scripts全部Bash语法；关键状态/依赖发现按红绿循环实现 |
| 显式 `db` 且无测试连接变量 | MISSING_ENV（退出2） | 没有连接数据库或启动服务，不表示跨进程测试已通过；stub测试只验证报告中skip会被拒绝 |
| `manual` | SKIP | 未测试真实文件提供方、声音、长期翻译、跨设备历史同步与删除优先；v30用户尚未验收 |

初次真实执行揭示Bash条件上下文不能依赖`set -e`保留前序错误；已以“test失败、随后工具可成功”的红绿用例保护，改为显式传播失败，避免假PASS。Cloud包发现也通过“业务与test-only包保留、npm依赖包排除”的CLI行为用例保护。文档机械迁移没有新增实现镜像测试。

依赖安装的npm审计输出各报告16项（11 moderate、5 high），本轮未升级依赖或执行自动修复；功能检查通过不代表依赖安全审计通过。本机没有KVM，未启动模拟器；没有运行会自动加载本机环境的smoke-local，也未运行公网/真实供应商链路。人工步骤见[验收入口](manual-acceptance.md)。

最终整合复核：任务08已合并整理分支 `079108b`（任务03/04）。在该隔离工作树的检查入口收尾版本，显式移除数据库连接变量、命令级指定当前机器SDK，运行 `bash scripts/check.sh all`，最终汇总 `PASS=10 FAIL=0 MISSING_ENV=0 SKIP=2`、退出0；两项SKIP为数据库与人工验收。完整本机日志保留在仓库外任务08执行证据中。

首次整合all收尾曾碰到新增“临时DB报告创建失败”用例的有意red窗口，tooling记为FAIL；恢复green并冻结源码后才得到上面的最终all结果。随后仅将默认导航范围补至 `archive/indexes/*.md` 批次索引、澄清文档和强制恢复验收措辞，定点重跑 `navigation tooling`：8项CLI、3项导航测试通过；11份当前文档、123个本地目标，0断链；Bash语法与`git diff --check`通过。没有因此重复或冒称执行DB和设备验收。

## 任务09：CI 与真实数据库集成

任务09从 `2b892a6` 开始，完成前合并整理分支 `c0e7331`（含05/07及独立故障修复11）。[CI 触发矩阵](ci.md)基于实际 workflow 与路径 CLI；四份 YAML 通过 actionlint 1.7.7。8项路径行为测试包含三个实际调用 workflow 的接入/外层过滤检查，以及实际 Git 改名后执行共享 workflow 的选择步骤；两份 Android 原 job 从 `runs-on` 起逐字节相同，原检查与 artifact 保留。任务09交付时尚未在 GitHub runner 执行，后续真实运行见下节；没有修改远端分支保护。

使用 CI 声明的 Go 1.25.0 运行 `agent officialast cloud contract` 全部 PASS；官方 protobuf 仍经原固定 URL/哈希脚本准备。Node 22.23.1 各自 `npm ci` 后，Web126项/管理端34项及各自 typecheck/lint/build PASS，未升级依赖；安装仍报告既有16项审计问题。合入05/07/11后的 `cloud contract navigation tooling download` 最终 PASS5；tooling 为8项检查CLI、3项导航、8项CI路径与4项技能安装行为测试。追加本节后定点导航为15份文档616个本地目标，无断链。

实际新建一次性 PostgreSQL16、仅映射 `127.0.0.1:15432`，健康检查确认最终 TCP 监听就绪；Go1.25.0 显式 `db` 最终 PASS，93个测试通过事件、0跳过，36.460秒。完整执行含数据库迁移/业务/权限/并发与 Cloud HTTP→独立 Agent harness 授权；其中真实数据库、HTTP和进程已运行，供应商仍为 fake provider，没有 Android/真实供应商端到端。任务自建容器已停止并移除、端口释放。

此前本地初轮早于容器就绪而失败；就绪后真实套件又暴露旧 fixture 与既有登录缺口，所有红记录均保留在仓库外任务09证据中。`2af0bc0` 仅修三个集成测试的旧数据、日期、CAPTCHA/登录输入及 issuer fixture，保留全部断言；内部手机号占位邮箱在正确密码下可登录的问题由任务11独立提交 `188309f` 修复，任务09合入后用原拒登断言验证，不为 CI 绿色删除测试或放松门禁。最终结果对应 `09-db-final.log`、`09-final-targeted.log`、`09-go125.log`、两个前端日志与 `09-trigger-matrix.json`，与任务08未配置数据库的记录分开归属。

任务09在本地未重跑主体未变、已由08验证的 Android JVM/lint/Debug 构建，也没有启动本地模拟器或真机（无KVM）、真实音频/文件提供方、长期翻译、跨设备同步/删除优先、公网 APK 核验、部署或发布。自动检查通过不改变 v30 尚未用户验收的状态。

### 首次 GitHub 运行后的检查脚本修复

首次真实 CI `43959839755` 的 database job 完成93个测试通过事件、0跳过，Go测试成功，但冷缓存依赖下载的 stderr 诊断被混入 JSON 文件，检查脚本误报 FAIL。这是检查工具缺陷；修复仅让 Go stdout 写入 JSON，stderr 直接保留日志，Go 非零退出、损坏 JSON、skip 仍失败。CLI 回归先 RED 后 GREEN，并补齐三类失败保护；tooling 的11 CLI、3导航、8 CI路径、4技能安装测试通过。随后 Go1.25.0、全新专用 PostgreSQL16、空临时模块缓存实际产生下载诊断并完成 `db`：93个pass事件、0skip，34.711秒，退出0。证据单列 `09-ci-fix-*.log`，此前检查记录保持原样；修复提交的 GitHub 复跑仍需单独核对，本次未重复 Android 或前端全套。

## 任务10：导航、来源收口与首次远端检查

任务10从整合 `27eabab` 开始，只修改文档入口和后续记录；四个旧UI切片在独立Git bundle验证及状态/ignored复核后逐项退役，主编排者随后按精确SHA/标签/开放PR/bundle证据退役两条远端旧发布分支，三个同SHA发布标签与Release保留；来源和保留理由见[工作树收口](../development/workspace.md#任务10收口状态)、[分支清单](../development/branches.md)。任务01补充检查点的212项哈希仍匹配；独立bare clone从bundle恢复四个切片ref，完整SHA均匹配。05/06/07共204份归档文件的既定哈希全部匹配，没有重写归档正文。

本批在任务10隔离工作树运行 `bash scripts/check.sh navigation tooling download`：PASS3、FAIL0、MISSING_ENV0、SKIP0；额外检查Pi、Android、管理端及来源/后续/分支六份入口，28个本地目标无断链。初轮既有8项CLI、3项导航、8项CI路径、4项技能安装行为测试和4项下载单测通过；合并最新整合 `b6b0a2d`（含脚本修复 `f351473`）后同三target再次PASS3，CLI已扩为11项，其余行为检查继续通过；文档收口没有新增镜像测试。整合目录另实际运行技能 `--check` 成功，37个链接有效。最终review仍由主编排者后续执行。

`27eabab` 推送后的三份GitHub真实运行已经结束：[Android CI](https://github.com/genanalucy/dongmeng/actions/runs/43959839931)成功，[Android Motion Regression](https://github.com/genanalucy/dongmeng/actions/runs/43959841169)成功；[Product checks](https://github.com/genanalucy/dongmeng/actions/runs/43959839755)的八个target与路径选择成功，database job失败。数据库测试本身93个通过事件、0跳过，失败是冷缓存 `go: downloading` 的stderr混入JSON报告而被解析器拒绝；不是新增产品回归，也不能把该job写成通过。脚本修复 `f351473` 的本地空缓存/真实PG验证见上节，已合并纳入本候选；修复提交的GitHub复跑仍由主编排者后续核对，不能用本地结果覆盖这次红记录。

PR #1和#2的标题/正文按真实范围修订并保持Open/Draft。PR #2继承main→`dc587ef`的35个候选提交；其后整理、测试fixture调整和11登录故障修复分别说明。没有合main、部署或新建Release；v30仍未用户验收。GitHub模拟器通过也不代替真实手机音频、SAF文件提供方与跨设备验收。
