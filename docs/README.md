# 项目文档导航

## 使用 Android 应用

- [功能与使用指南](android-features.md)涵盖登录、面对面对话、同传和历史记录。
- [手动测试与反馈](testing/README.md)提供对话文案、翻译质量表和可打印的 PDF。

## 理解与开发

- [项目结构与模块职责](architecture.md)：Android、Cloud、翻译服务、管理后台和 Web 的关系。
- [开发与测试基线](development/baselines.md)：Mac 开发、检查命令和测试包识别。
- [GitHub 仓库与版本状态](development/repository-status.md)：分支、PR、Release 及待收口项目。
- [Android 工程说明](../android/README.md)。
- [Web 与 Agent 本地开发](development/web-prototype.md)：辅助原型、火山 AST 准备和本地排障。

## 接口、部署与运维

- [Cloud API 接口](../cloud-api/API.md)。
- [Cloud API 迁移与隔离数据库验证](../cloud-api/README.md)。
- [Android OTA 发布](deployment/app-ota-update.md)。
- [Caddy 生产配置参考](deployment/Caddyfile.production)：参考文件，使用前核对实际部署。
- [邮箱验证码 SMTP 运维](operations/email-registration-smtp.md)：使用前核对当前邮件投递配置。

## 历史资料

- [归档总入口](../archive/README.md)：旧设计、实现报告、验收记录、早期 Android/Web 说明。
- [全部迁移路径](../archive/source-index.md)：旧路径到新位置的逐文件对照。
- [本地虚拟机历史需求](deployment/local-vm-requirements.md)、[AWS 新加坡历史需求](deployment/aws-singapore-requirements.md)：部署规划，不代表现网事实。

归档中的“已完成”“当前环境”或未勾选任务只描述当时状态。判断当前功能时，结合源码提交、Release 和实际验收记录。
