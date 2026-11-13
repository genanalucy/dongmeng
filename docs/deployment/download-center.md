# 官网下载中心与双通道 OTA 同步

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

## 初次上线状态

正式v20 /20260915.0232和Debug v26 /20261110.1930原包下载并核验成功。4项发布工具回归与shell语法检查通过；实际核验APK哈希、包名、版本和既有签名。

- 已安装双Host路由，Caddy备份 `/etc/caddy/Caddyfile.before-downloads-20261113T052342Z`，validate/reload成功，Caddy/Cloud API/Agent active。
- 当前快照 `/opt/dngmeng/downloads/snapshots/downloads-20261113T052707Z-1038246`；成功执行完整同步工具，网页、清单、Debug OTA和正式源站OTA均与本次bundle严格对照一致，两APK从公网完整下载后SHA匹配，Range206及附件/长度/类型头正常。
- 备用公网入口 `https://47-129-170-16.sslip.io/download` 已200，API health、Agent health、管理后台均200；页面为响应式CSS，但尚未用真实浏览器逐尺寸视觉验收，不宣称已完成手机观感测试。
- 官网源站 `https://yszn.nlplayer.org/download` 正确Host/TLS直连200；公网经Cloudflare仍同URL308循环。用户暂不能调整Cloudflare，故正式官网公网访问仍为阻塞项，不能视作全任务完成；未降低TLS或绕过代理。
- App更新仍需用户核验真实下载/系统安装。版本号、签名、APK内容未变化，不需要为URL托管变化升级App。
