# Android OTA 更新发布

> **当前发布入口已迁移**：2026-11-13起，官网页面和正式/测试OTA由同一个快照生成。后续请使用 [下载中心同步流程](download-center.md) 更新 `site/download/releases.json` 并部署，不再仅替换旧 `/ota/debug-v*.json` 或只改Cloud API环境，否则与官网不同步。下文保留历史操作记录供回溯。

应用从 `GET /api/v1/app-update` 获取公开的更新元数据。未配置完整更新变量时接口固定返回 `{"available":false}`，不会向用户推送不完整更新。

## 发布步骤

1. 以**与已安装应用相同的 applicationId 和签名证书**构建单 APK；将 `versionCode` 递增。
2. 上传 APK 到稳定的 HTTPS 地址（不含 URL 凭据或片段）。不要把 GitHub API token 放入客户端或更新元数据。
3. 计算 `sha256sum app-release.apk`。
4. 在 Cloud API 部署环境设置以下变量并滚动重启：

```dotenv
APP_UPDATE_PACKAGE_NAME=com.verba.interpretation
APP_UPDATE_VERSION_CODE=2
APP_UPDATE_VERSION_NAME=1.1
APP_UPDATE_APK_URL=https://downloads.example.com/verba/2/app-release.apk
APP_UPDATE_APK_SHA256=<64位小写或大写十六进制SHA-256>
APP_UPDATE_RELEASE_NOTES=修复并优化翻译体验
APP_UPDATE_FORCE=false
```

5. 用已安装旧版本的真机进入 **我的 → 账户设置 → 关于 → 检查更新** 验证：版本发现、下载、哈希校验、系统安装确认、重启后版本号。

## 旧 Debug 1602 的兼容分流（2026-11-04）

旧 Debug `20260920.1602` 检查更新时不传包名或通道，默认使用 `47-129-170-16.sslip.io`；正式 v20 锁定 `yszn.nlplayer.org`。当前在 Caddy 上仅对旧域名的精确路径 `/api/v1/app-update` 提供静态 Debug 元数据，其余 API 和 WebSocket 继续转发原后端。此方案是旧客户端兼容桥接，不是完整的包名/通道协议。

- 元数据源：[`ota/debug-v22.json`](ota/debug-v22.json)，部署到 `/opt/dngmeng/ota/debug-v22.json`，文件权限 644。
- Caddy 配置参考：[`Caddyfile.production`](Caddyfile.production) 中 sslip 站点的精确 `handle`；不要用此参考覆盖线上整个 Caddyfile，线上还有正式站点及其他服务。
- Debug：`com.verba.interpretation.debug` / v22 / `20261105.0031`，已发布 APK 的 SHA-256 和签名校验通过。
- 正式站点仍转发 Cloud API，`APP_UPDATE_*` 环境未修改，仍返回正式 v20。
- 响应使用 `Cache-Control: no-store`；旧 300 秒缓存可能需等待最多 5 分钟。
- 使用过旧 sslip 入口的早期正式包会忽略包名不符的 Debug 元数据，不会误装；但无法再通过该旧入口发现正式更新。正式 v20 的域名入口不受影响。Debug 若手动改成正式域名，也不会发现测试更新。

此次已备份线上 Caddyfile、通过 `caddy validate` 后平滑 reload。公网核验两域名分别返回预期包名/版本，两个 health 均 200，Caddy/Cloud API/Agent 均 active；未重启应用服务。旧手机实际发现、下载、覆盖安装及数据保留仍需用户确认。

回滚：恢复本次变更前的 Caddyfile 备份（本次为 `/etc/caddy/Caddyfile.before-debug-ota-20261104T165543Z`），先验证再 reload；先检查之后是否有其他配置变更，不覆盖后续修改。不删用户数据、不撤换已发布附件。

## Debug v23 圆角反馈修复（2026-11-05 中国时间）

- 当前旧 Debug 更新路径已从 `/debug-v22.json` 切到 `/debug-v23.json`，元数据源为 [`ota/debug-v23.json`](ota/debug-v23.json)。v22 文件保留作回溯。
- v23 `20261105.0217`，源码 `e8c2ad1`，APK SHA-256 `d49a581f331820cc80872606c1364f4733d0dd6f33cbd6b92407546779267532`；同一 Debug 签名。
- 修复 Debug 底部导航背景与按压涟漪的圆角裁剪，显式主题涟漪及按压底色保证深浅主题反馈可见；Release 交互分支不变。
- 385 项 JVM 单测、lint 无错误及 Debug/AndroidTest 构建通过；[设备回归](https://github.com/genanalucy/dongmeng/actions/runs/41885970668)通过，含深浅主题圆角外像素不变、内部反馈可见与点击断言。之前失败记录保留：暗色系统涟漪截图内部无变化，增加明确按压态后通过，未移除断言。
- GitHub [Pre-release](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-nav-corners-20261105-v23) 已发布。公网验证 sslip 返回 Debug23，正式域名仍返回正式20；Caddy/Cloud API/Agent active。
- Caddy 变更前备份：`/etc/caddy/Caddyfile.before-debug-v23-20261104T182456Z`；validate 与平滑 reload 成功。手机实际下载、覆盖与观感仍待用户确认。

## Debug v24 手动下载进度修复

- 当前 sslip Debug 更新路径指向 `/debug-v24.json`，源文件 [`ota/debug-v24.json`](ota/debug-v24.json)；正式域名继续返回正式 v20。
- v24 `20261105.0314`，源码 `5a8ff7a`，APK SHA-256 `4f20c833b09c1756bc4ec7f449d17ac27a33a08a0bdd1e3ca6bcfb6d61488348`，既有 Debug 证书及 APK v2 签名验证通过。
- 关于页进度字符串将字面百分比再传给 `.format()`，稳定抛出 `UnknownFormatConversionException`；改为先格式化数值再拼接百分比。3 项针对性回归修复前 2 项失败，修复后全量 388 项通过；lint 无错误（48 Warning / 2 Hint），Debug 构建通过。
- 不改下载、哈希或安装路径；未重跑云端设备测试。手机崩溃栈未取得，真实下载完成/安装仍需用户验收。
- 旧版手动下载仍有缺陷，请从启动更新弹窗升级此版，或使用 [GitHub 下载](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-update-progress-20261105-v24)，不卸载、不清数据。
- Caddy 备份 `/etc/caddy/Caddyfile.before-debug-v24-20261104T191652Z`，validate/reload 成功；公网核对 Debug24/正式20，三个服务 active。

## Debug v25 界面与状态引导

- 当前旧Debug更新入口指向 `/debug-v25.json`，源文件 [`ota/debug-v25.json`](ota/debug-v25.json)，versionCode25，versionName20261110.0226。
- APK来源 `25c38e3`；`abf3d73`仅修正UI测试的受支持语言选择，无运行时差异。SHA-256 `b350d84b57f0e9d9b2857e3143621938cbbf6e1e9b78870a8b08f39d0399beeb`，既有Debug证书，APK v2验证通过。
- Debug优化顶部语言对、切换等待原因、系统单路收音说明、状态/待机/恢复引导、三档字幕字号与主操作文案。不增加双麦或自动语言识别。状态投影补充连接取消清理、待播放/排空标记；不改供应商协议。
- 394项JVM单测、lint无错误（48 Warning/2 Hint）、Debug/Release/AndroidTest构建通过；[设备回归](https://github.com/genanalucy/dongmeng/actions/runs/42768121087)成功。首轮旧用例选择不存在的日语失败，改为越南语并断言实际状态后通过。
- [Pre-release](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-guidance-20261110-v25)已发布，测试OTA公网返回25。Caddy备份 `/etc/caddy/Caddyfile.before-debug-v25-20261110T062945Z`，validate/reload通过，三个服务active；未改正式站点与Cloud API环境。
- 正式源站经本机TLS/Host核验仍返回正式20；但当次正式域名经Cloudflare访问出现同URL 308循环，跟随3次仍失败，正式公网入口不得标记通过，须另排查边缘/回源配置。本轮未擅改该配置。
- 真机观感、真实音频、下载覆盖安装、长期稳定性仍需用户验收；模拟器不调用真实翻译。

## Debug v26 独立工作台

- Debug更新入口改为 `/debug-v26.json`，见 [`ota/debug-v26.json`](ota/debug-v26.json)，v26 `20261110.1930`。
- 源码 `a59644b`；`e16de23`仅修正无障碍测试调用。APK SHA-256 `591ed169b34b75c6c3bf4cd83d423ec94f10b49fb80460b8693109f59fd604c3`，既有Debug签名、APK v2验证通过。
- Debug登录后功能首页，提供对话/同传与我的入口；工作台无全局底栏，返回弹栈触发原根会话清理。语言外侧L/R，移除角色标题；麦克风为48dp高扁圆角矩形，连续模式中间开始/暂停，视图/字号/结束保留更多菜单。Release导航和样式保持原条件分支。
- 395项单测、lint无错误（48 Warning/2 Hint）、Debug/Release/AndroidTest构建通过；[设备回归](https://github.com/genanalucy/dongmeng/actions/runs/42809984257)通过，包括手势release/cancel、无障碍、L/R几何、小屏和会话生命周期。首轮旧测试把触摸用于关闭指针输入的无障碍测试而失败，改用SemanticsActions.OnClick后通过，实际触摸用例保留。
- [Pre-release](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-workbench-20261110-v26)已发布，公网测试OTA返回26；源站正式Host核验仍20。正式公网域名既有重定向风险不因该源站检查视为解决。
- Caddy备份 `/etc/caddy/Caddyfile.before-debug-v26-20261110T115121Z`，validate/reload通过，三个服务active。真机布局、真实翻译、升级数据保留等待用户验收。

## 安全边界

- 客户端拒绝非 HTTPS、带凭据或片段的下载地址，并在安装前校验 SHA-256。
- Android 系统安装器还会校验 APK 包名与签名；无法由应用静默覆盖安装。
- 首次侧载时，用户必须在系统设置中允许该应用“安装未知应用”。
- Debug 包的 `applicationId` 包含 `.debug`，只能更新同签名、同包名的 Debug 包；生产更新使用 Release 包。
