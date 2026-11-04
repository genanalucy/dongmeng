# Android OTA 更新发布

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

## 安全边界

- 客户端拒绝非 HTTPS、带凭据或片段的下载地址，并在安装前校验 SHA-256。
- Android 系统安装器还会校验 APK 包名与签名；无法由应用静默覆盖安装。
- 首次侧载时，用户必须在系统设置中允许该应用“安装未知应用”。
- Debug 包的 `applicationId` 包含 `.debug`，只能更新同签名、同包名的 Debug 包；生产更新使用 Release 包。
