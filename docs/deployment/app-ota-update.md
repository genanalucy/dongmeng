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

## 安全边界

- 客户端拒绝非 HTTPS、带凭据或片段的下载地址，并在安装前校验 SHA-256。
- Android 系统安装器还会校验 APK 包名与签名；无法由应用静默覆盖安装。
- 首次侧载时，用户必须在系统设置中允许该应用“安装未知应用”。
- Debug 包的 `applicationId` 包含 `.debug`，只能更新同签名、同包名的 Debug 包；生产更新使用 Release 包。
