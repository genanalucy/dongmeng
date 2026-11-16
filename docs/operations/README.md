# 运维资料的效力与核验入口

适用源码：v30 `cd920a1`、发布资料 `dc587ef` 及本轮整理（2026-11-17）。**本轮未连接生产、未发真实供应商请求、未发送邮件、未签发/领取邀请码、未部署或重启。** 不由仓库文件、health200或旧报告推断当前供应商、端口、模型、数据与公网状态。

| 类型 | 阅读入口 | 能证明与不能证明 |
| --- | --- | --- |
| 当前源码事实 | [Agent配置](../../agent/internal/config/config.go)、[进程入口](../../agent/cmd/translator-agent/main.go)、[路由客户端](../../agent/internal/ast/qwen.go) | 配置支持Qwen/火山、空配置默认火山，显式qwen选择3.8、无自动fallback；真实火山需officialast。不能证明当前服务器环境 |
| 当前运维步骤 | [下载与双通道更新](../deployment/download-center.md)、[SMTP](email-registration-smtp.md)、[共享邀请码](shared-invitations.md) | 提供准备、隔离检查、安全边界和回滚入口；运行前还需批准的环境与变更窗口，不表示已经执行 |
| 历史规划 | [本地VM](../../archive/documents/operations/docs/deployment/local-vm-requirements.md)、[AWS入口](../../archive/documents/operations/docs/deployment/aws-singapore-requirements.md) | 仅为混合部署设想；不能用来列举今日实际资源、WAF、隧道或数据库位置 |
| 历史供应商/部署证据 | [11月14日火山](../../archive/evidence/operations/docs/operations/volcengine-routing.md)、[11月15日Qwen](../../archive/evidence/operations/docs/operations/qwen38-routing.md)、[更晚v30发布](../../archive/evidence/operations/docs/deployment/download-center-releases.md)、[共享码部署](../../archive/evidence/operations/docs/operations/shared-invitations-deployment.md) | 仅保存当时作者记录的授权、hash、检查和未完成项；不是本轮重新核验。v30记录比11月15日部署更晚，不能把旧二进制hash当今日唯一身份 |
| 当前验收状态 | [版本与工作树](../development/workspace.md)、[用户测试材料](../../translation-test-kit/README.md) | v30用户已更新、尚未测试；历史发布自动检查与本轮本地工具检查分开记录 |

现场复核应另记时间、源码/二进制身份、脱敏provider/model与健康检查、下载的完整APK哈希及未执行项；不要打印私有环境文件、验证码、邀请码、用户正文或凭据。源码可选择供应商不等于任何生产变更已获授权。规划与历史报告不得作为部署脚本的直接输入。

完整迁移位置、逐文件来源和前后哈希见[06归档索引](../../archive/indexes/06-operations-and-test-materials.md)。返回[文档导航](../README.md)。
