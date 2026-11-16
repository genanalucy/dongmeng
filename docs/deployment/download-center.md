# 官网下载中心与双通道 OTA 同步

> **现行发布流程；不表示本轮执行发布。** 适用于v30候选源码 `cd920a1`、后续发布资料 `dc587ef` 及本轮整理。当前活动输入仍是 `site/download/releases.json` 和 `site/download/`，本次没有修改、生成或部署它们。

唯一版本来源：`site/download/releases.json`。APK不入Git、不重签名；正式与Debug各自固定包名和证书。模板为 `site/download/index.template.html`，无外部依赖或JavaScript。

## 后续发布必须走同一流程

1. 构建并验证相应通道的新APK，递增versionCode，发布GitHub Release备用附件。
2. 更新清单该通道的版本、immutable文件名、SHA-256、证书指纹、更新说明、来源URL。另一个通道保留当前值。证书改变应先调查，不能为了通过验证随意改指纹。
3. 下载清单对应的两个APK为本地 `production.apk` / `debug.apk`（用户数据、邀请码和密钥不放输入目录）。
4. 准备快照：

```bash
python3 scripts/prepare-download-release.py \
  --apk-dir <apk-input-directory> --output <new-bundle-directory> \
  --base-url https://47-129-170-16.sslip.io \
  --build-tools <android-sdk>/build-tools/36.0.0
python3 -m unittest discover -s scripts -p 'test_download_release.py'
bash -n scripts/deploy-download-release.sh
bash scripts/deploy-download-release.sh <new-bundle-directory>
```

准备工具先验证所有APK的hash、签名、包名、版本，再生成HTML、公开清单与两通道OTA。部署工具使用现有SSH授权，先上传文件、复核，拒绝同文件名内容覆盖、同版本更换和隐式降级；锁住发布临界区后，原子切换 `current`。网页和OTA共用这个指针，避免更新说明与实际下载不同步。它不会自动挑任意“最新Release”或在未经审核时自动上线；每次受控发布执行它。

## 服务器结构与路由

```text
/opt/dngmeng/downloads/
  files/<versioned-name>.apk       # 保留旧版本，不覆盖
  snapshots/<release-id>/          # index.html,releases.json,production.json,debug.json
  current -> snapshots/<release-id>
```

首次先 `deploy-download-release.sh <bundle> --bootstrap` 上传完整初始快照，再安装路由。`scripts/install-download-routes.py`仅生成已知旧结构的定点修改（Debug旧路由v26），故意拒绝再次覆盖；后续版本只切快照，不再重复安装路由。必须先备份线上Caddyfile，运行`caddy validate`后平滑reload，不以仓库参考覆盖整个线上文件。

两个站点 `/download`、`/download/` 为网页，`/download/releases.json`为公开清单，`/downloads/*`为immutable APK。Caddy片段见 `site/download/Caddyfile.snippet`。旧Debug域名更新读取current/debug.json，正式域名读取current/production.json；Cloud API原正式APP_UPDATE环境保留作回退，不自动重启API。

网页和元数据no-store，APK版本地址一年immutable。暂用可用sslip HTTPS域名托管实际下载URL，官网CF恢复后可以切到正式域名；先验证无循环、APK完整下载后再切，旧链接继续保留。不同通道不能交叉覆盖安装。

## 验证与回滚

- 两APK必须完整下载并对SHA-256，不只看HEAD/200；校验包/证书/版本。
- 分别检查两个Host的OTA包名、versionCode、hash和下载URL与公开清单相同；验证Range206、长度、Content-Type和安装附件头。
- 检查API health、Agent health与管理后台、其他站点未受影响。
- 公网官网CF循环与源站状态分开报告，源站200不等于公网可用；不得降低TLS/绕过权限。
- 回滚快照：检查目标目录与旧APK存在，通过临时symlink+rename恢复原current；previous-snapshot.txt记录上个指针。首次路由回滚则恢复变更前Caddyfile并validate/reload，先检查之后有无其他配置变更。
- 不删用户数据、不撤换旧附件。服务器承担带宽成本；无需APK重打包或App升级。

历史发布/部署证据已分离，见[原记录](../../archive/evidence/operations/docs/deployment/download-center-releases.md)。其中测试、上线和公网结果均属当时发布者记录，本轮未复测。

## 当前版本身份与验收

以下来自仓库清单与既往发布记录，未重新下载APK或连接服务器。本轮对清单/源码关系做本地核对；手机安装信息未读取。**用户已更新 Debug v30，尚未测试。** 正式与Debug包名、证书不同，不交叉覆盖。

| 通道 / 用途 | 版本与源码 | 标签 / 来源 | APK SHA-256（记录值） |
| --- | --- | --- | --- |
| 正式 | 21 / `20261113.1836`；`20da583d39eb39d47e2ad104143f4ab185317b1a` | [android-production-yilu-20261113-v21](https://github.com/genanalucy/dongmeng/releases/tag/android-production-yilu-20261113-v21)；旧正式v20仅更名与升版 | `898347c3a707a92d9ea1e0aca3a666111fa98861c9af036f4776c93e2f97acc4` |
| Debug候选 | 30 / `20261117.0343`；实现 `cd920a1b96b35c52d77b8e415a8219cc30cf707c` | [android-debug-yilu-20261117-v30](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261117-v30)；后续 `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 只更新发布说明和清单 | `21ea23d51b04ee0017419a8e85800ec6feeb43d3a8612966733778bfebb91798` |
| Debug比较参考 | 29 / `20261116.2307`；`51a76704bbb8c853525fa903c840223160b09d87` | [android-debug-yilu-20261116-v29](https://github.com/genanalucy/dongmeng/releases/tag/android-debug-yilu-20261116-v29)；用户此前认可，仍有待打磨问题，不是活动清单输入 | `0068e8d967f878952b03e5a194f7a7323d4f8ab843bf19e472e1c8e0b92db7cb` |

安装应选择已确认通道和固定版本，先核对APK哈希、包名、证书和设备已安装版本。发生签名冲突、降级或数据兼容性疑问时停止安装并调查，不按旧Beta手册自动卸载、清数据或强制降级。

当前包名、证书指纹、immutable文件名和附件地址以[活动清单](../../site/download/releases.json)为准；证书指纹不是APK内容哈希。已发布不代表用户验收通过，v30真实设备文件提供方、长期语音、跨设备同步仍待验收；441单测、模拟器与公网检查属于[发布者既往记录](../../archive/evidence/operations/docs/deployment/download-center-releases.md)，不是本轮重新执行结果。

旧v22–26 JSON及旧Cloud环境发布步骤见[OTA历史记录](../../archive/evidence/operations/docs/deployment/app-ota-update.md)。`install-download-routes.py`和既有测试中的 `debug-v26` 是已知旧路由迁移哨兵；即使历史JSON已归档，也不得机械替换成v30。当前[Caddy参考](Caddyfile.production)与[活动片段](../../site/download/Caddyfile.snippet)不等于线上配置副本，必须按上述首次安装/回滚步骤核对现场。
