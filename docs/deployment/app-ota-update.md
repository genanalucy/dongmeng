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

## 安全边界

- 客户端拒绝非 HTTPS、带凭据或片段的下载地址，并在安装前校验 SHA-256。
- Android 系统安装器还会校验 APK 包名与签名；无法由应用静默覆盖安装。
- 首次侧载时，用户必须在系统设置中允许该应用“安装未知应用”。
- Debug 包的 `applicationId` 包含 `.debug`，只能更新同签名、同包名的 Debug 包；生产更新使用 Release 包。
