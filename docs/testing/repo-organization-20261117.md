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
