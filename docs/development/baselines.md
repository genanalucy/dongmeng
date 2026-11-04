# Android 开发与测试基线

## 规则与恢复入口

Android 是主要开发方向，`main` 是共享集成基线。正式功能与实验功能分离；正式发布以 Release APK 和 SHA-256 为准，不以最新分支名或构建时间推断。

本文件记录 2026-11-04 恢复开发时的基线整合。它不证明以下已知问题已修复：正式版偶发登录失败、语言交换后无声、长时间翻译与播放阻塞；邮件真实收码、跨设备删除一致性与完整动效也仍需专项验收。

### 本机工作树

| 路径 | 用途 |
|---|---|
| `/home/genanalucy/work/dngmeng-ui/integration` | 原 Android 集成工作树，`android-ui-redesign` 固定在 `8e625a6`；保留作整合前对照，不在这里混合拉取旧远端分支 |
| `/home/genanalucy/work/dngmeng-ui/main-baseline` | 本次整合与后续 Android 开发工作树，分支 `chore/android-main-baseline`；整合推送后以 `origin/main` 为共享基线 |
| `/home/genanalucy/dngmeng_project` | 旧本地 `main` 工作树，包含大量未跟踪源码/本地资料；本次不切换、不清理、不直接 pull |

开始任务先运行 `git status --short`、`git branch --show-current`、`git log -1 --oneline`。在主基线上开任务分支；使用其他机器时，从 GitHub `main` 新 clone 即可，不依赖上述本机绝对路径。

## 保存的分支关系

整合前各状态保存在远端 `baseline/20261104/*` 分支，供查证与回溯；这些是快照，不是活跃开发线。

| 快照后缀 | 原引用 | 提交 |
|---|---|---|
| `remote-main` | 旧 GitHub `main` | `3ca49501390eefdcefb47e6da0f0fe271b1f993a` |
| `local-main` | 本地 `main`（测试材料/文档） | `bec885983928973849757f5d31df38b903b116a8` |
| `android-integration` | 本地 `android-ui-redesign` | `8e625a67ebcffe0a5776228f5ac4ba907f05391a` |
| `legacy-remote-ui` | 旧 `origin/android-ui-redesign` | `a32f2159ed1ec5fcd6009e3c7489b79cb012e7b2` |
| `azure-experiments` | 本地 `azure-experiments` | `aeec6aa` |

旧 GitHub `main` 已是 Android 集成分支的祖先。本次以 `8e625a6` 为应用代码基础，合入 `bec8859`，保留两侧提交历史和本地 `main` 独有的 10 个文档/工具配置提交，再更新入口文档、忽略规则和 CI，普通推送到 GitHub `main`；不重写历史。

旧远端 `android-ui-redesign` 与本地集成分支各有 12 个独有提交，包含 Azure 协议/诊断等实验演进，未整体合回新 `main`。如未来需要移植通用诊断，逐项审查，不把实验引擎重新带入主线。历史提交保留 Azure 代码不代表当前主线启用该引擎。

## 测试版本登记

以下版本是既有产物，本次不新发 APK、不更新 OTA。完整下载链接由表中 Release 页面提供。

| 用途 | 包名 | versionCode / versionName | 应用源码与发布状态 |
|---|---|---|---|
| 正式对照 | `com.verba.interpretation` | `20` / `20260915.0232` | `87e8ce4`；[正式 v20](https://github.com/genanalucy/dongmeng/releases/tag/android-production-20260915-v20)，当前正式 OTA 对照 |
| 历史问题复现 | `com.verba.interpretation.debug` | `19` / `20260915.0225` | 从 `cde766d` 重建并固定版本名；[历史测试包](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260915-0225)；不含液态玻璃 |
| 已发布玻璃测试 | `com.verba.interpretation.debug` | `21` / `20260920.1415` | `c1ac3f1`；[玻璃测试包](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-internal-beta-20260920-liquid-glass)；不含后续动画补丁 |
| 动画开发对照 | `com.verba.interpretation.debug` | `21` / `20260920.1602` | `8e625a6`；曾本地构建安装到 Pixel 3，没有独立 GitHub Release；不要把玻璃 Release 当成此版本 |

### 既有 APK SHA-256

```text
正式 v20
5fb3e8e10640ff8d9a18a4a6423d65f6561fa14428c084c5433755de9bdd2330

历史 Debug 20260915.0225
8daa0898834f1284fa10c703a14103e59d5440bab419b1246fd0f3f3f2649313

已发布玻璃 Debug 20260920.1415
652cba783ebce26e02c5313e65a6b459e2874bade8d425b2f0bb88c7c9074a6b
```

动画对照包没有在此登记经留存核验的 SHA-256，不应声称后续重建是同一二进制。当前源码动态生成 `versionName`，重新构建会显示新时间；这次未改变构建或版本号逻辑。

**历史标签例外：**`android-debug-internal-beta-20260915-0225` Git 标签实际指向 `87e8ce4`，不是构建来源 `cde766d`。原发布仅在隔离工作树固定了版本名，没有记录这次构建元数据修改的提交。为避免破坏已有引用，本次不移动标签、不替换附件；复现时以这里记录的源码来源及 APK 校验值为准。

## 测试时如何避免装错包

1. 先选用途：正式流程、历史故障复现，还是新 UI；记录源码提交和 APK 来源。
2. 计算 APK SHA-256，核对签名与包元数据；本地构建读 `android/app/build/outputs/apk/debug/output-metadata.json`，不能只看文件名 `app-debug.apk`。
3. 使用 `adb -s <serial> install -r <apk>`，然后核对 `adb -s <serial> shell dumpsys package <package>` 的 `versionCode` / `versionName`。
4. Debug 与正式版可以并存，但三个 Debug 版本包名相同，会互相覆盖。历史 v19 降到 v21 设备可能被系统拒绝；先保留数据并讨论兼容方案，不卸载、不清数据、不盲目强制降级。
5. 记录实际入口。当前 Release 默认 `https://yszn.nlplayer.org`，Debug 默认 `https://47-129-170-16.sslip.io`，WebSocket 使用同主机 `/ws/translate`；Debug 本地设置可能覆盖默认值。
6. 测试记录至少包含：设备/Android 版本、包名、版本号、源码提交、APK SHA-256、实际服务入口、网络、语言方向、操作时间、复现步骤、预期/实际和脱敏错误信息。不要上传账号、token、原始用户历史或私密录音。

## 验证边界

- `main` 的 Android CI：单测、lint、Debug 构建及 artifact；不自动发布 Release 或部署服务器。
- 本地服务端验证与真实 AST 构建：见根 [README](../../README.md)。真实数据库集成测试必须使用专用隔离数据库。
- APK 编译或单测通过不等于真机音频、登录、云同步、邮件或 OTA 全链路通过。
- 本次本地重新验证：Android 384 项单测通过、lint 无 Error（48 Warning / 2 Hint）、Debug 构建通过；Agent 默认和 `officialast` 两种构建的 test / vet / build 通过；Cloud API test / vet / build 通过。环境缺少 `make`，真实 AST 检查使用 Makefile 中等价的准备脚本与 Go 命令执行。
- 验证发现旧迁移清单测试仍期望 13 个文件，而已有 `000014_admin_auth_version`。本次仅更新测试清单到 14，并断言所有迁移的事务分类；未修改迁移 SQL 或业务逻辑。真实 PostgreSQL、真机音频、登录及 OTA 覆盖安装不在本次验证范围。
- 下一次发布应递增相应包的 versionCode，并把构建来源、版本和 APK 校验值随 Release 一起记录；不要再次让标签与 APK 来源错位。
