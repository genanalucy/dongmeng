> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/azure-translation-provider/issues/05-beta-ota.md`；原始 SHA-256 `9758e071ac3729bfa3d82721e95976dcd7639b8950f59c35bee7f05b1ea8a945`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 05: 测试版 OTA 发布

**What to build:** 双引擎版本经测试版 OTA 渠道发布：versionCode 递增、完整验证（单测/lint/Debug/Release/AndroidTest 构建）、上传 Debug APK 到 GitHub Release、OTA 元数据切回测试轨道（com.verba.interpretation.debug），不自动安装设备。用户真机更新后即可选择 Azure 引擎验收英越质量；验收通过后正式版发布另立变更。

**Blocked by:** 03（部署与真实冒烟）、04（App Debug 设置与自动模式入口）

**Status:** ready-for-agent

- [ ] versionCode 递增，版本名按北京时间生成
- [ ] testDebugUnitTest、lintDebug、assembleDebug、assembleRelease、assembleDebugAndroidTest 全部通过
- [ ] Debug APK 上传独立 GitHub Release（Pre-release）
- [ ] OTA 元数据切换并验证：包名 com.verba.interpretation.debug、新 versionCode、SHA-256 一致
- [ ] 不安装到任何设备
- [ ] 提交并推送分支
