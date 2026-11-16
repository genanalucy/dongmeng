> **历史证据，非现行操作或验收结论。** 来源：`079108bff4b55d1b3783ca8e99e3376a35804ee0` 的 `docs/deployment/download-center.md`。原文中的“当前/本次”仅指记录当时，未测项保持未测；本轮未连接生产或真机。现行入口：[当前说明](../../../../../docs/deployment/download-center.md)；来源与内容处理见[批次索引](../../../../indexes/06-operations-and-test-materials.md)。

## 译路随行改名发布（2026-11-13）

- 用户调整Cloudflare配置后，正式域名/download、版本清单、API/Agent health、管理后台均公网200，不再循环；旧两APK也经正式域名完整下载哈希匹配。以下初次上线阻塞记录仅作历史。
- 正式v21 `20261113.1836`，源码`20da583`，隔离分支`release/yilu-production-v21`基于正式v20，仅名称/版本变化；测试v27同版本名，源码`46fa5cf`，保留测试v26功能。包名、原证书及图标不变，名称分别“译路随行”和“译路随行 Debug”；关于页引用BrandConfig统一名称。
- 正式Release单测384、测试Debug单测395均通过；各自lint无错误，构建成功，aapt确认label/package/version，apksigner确认原证书。未运行实际手机覆盖安装，不承诺设备数据保留已实测。
- APK与网页/两OTA已通过统一发布工具切换到快照`downloads-20261113T104226Z-1445042`；两APK完整公网下载SHA匹配，正式公网OTA21/测试OTA27核对通过。新下载URL使用`https://yszn.nlplayer.org/downloads/`，旧sslip文件和链接继续保留。
- 仅改名，不换域名、不变包、不把Debug界面功能带入正式。用户旧版首次安装仍显示旧名，覆盖升级后才显示新名。源码签名配置、私钥和APK构建产物未入Git。

## Debug v30当前会话导出与保存（北京时间2026-11-17）

- 实现`cd920a1`，Debug30/`20261117.0343`。三工作台更多提供保存到历史/重试、明文TXT和JSON导出；系统SAF选位置，冻结点击快照，取消/写入关闭失败及账号变更有独立处理，不申请全存储权限。进程重建安全取消导出，不将正文写Bundle；用户选择的外部文档提供方可能留部分文件，失败提示检查。
- Qwen3.8官方conversation.item.created的previous_item_id明确关联原译文；两侧final加response成功产出turn_completed。仅supportsCompletedTurns显式true的新Debug收到事件，旧正式不收新类型。Android以完成身份幂等保存，运行中的同传不必等transport结束；旧协议仅实际Finished后以完整transport块归档，不zip两个final数组。
- 保存采用application-owned IO，稳定turn PK与Room事务/outbox；失败可重试、重复请求不重复记录，配额保留，删除tombstone/持久账号epoch阻止迟到复活，云同步使用冻结凭据核验所属账号。未改schema版本。只有Room commit是持久接纳，未commit瞬时内容不保证断电/强杀恢复；失败队列仍属进程内重试，不冒称未提交队列跨进程持久化。
- 状态区区分保存中/已保存到本机/失败重试，成功可进历史；没有完成内容主动保存会说明。账号切换清旧正文且导出需绑定账号；外部写按有界块与撤销互斥。撤销无法收回此前已由用户主动交付的字节，外部明文需用户妥善保管。
- 441单测通过，lint0error/50warning/2hint，Debug与AndroidTest构建通过；Agent默认/officialast test/vet/build通过。双轴审查发现跨账号正文、提前主动保存重复、导出撤销和过期凭据静默停止同步问题，修复后二次静态复核原P1/P2均消除。云端Android CI`43942661747`、设备`43942651603`/`43942661999`成功，覆盖Room、反馈、导出菜单等；无真实语音收费测试、未验真实文档提供方/长时翻译/跨设备全流程。
- 用户单独授权Agent上线。2026-11-16T19:52:20Z部署且provider=qwen/model=qwen3.8-livetranslate-flash-realtime/env未变，运行二进制hash `ddb8310343a995f776459708c56339d8278f6a5afbf045cfdb25d169839a1a40`，备份`/opt/dngmeng/backups/completed-turns-20261116T195220Z`，无模型fallback。
- DebugAPK SHA256 `21ea23d51b04ee0017419a8e85800ec6feeb43d3a8612966733778bfebb91798`，原包名证书不变，tag`android-debug-yilu-20261117-v30`指向实现。正式21原APK复用；统一快照`downloads-20261116T195255Z-3771633`。
- 两APK完整公网hash、页面清单及双OTA与bundle核验通过；正式公网OTA仍21、Debug30；正式下载页/Agent/Cloud/admin200。4项发布脚本测试与shell语法通过。手机覆盖安装与用户对话真实性未代验。

## Debug v29静音空白气泡修复（2026-11-16）

- 用户确认参考11月独立工作台之前v25的无空泡体验，不回滚整套UI。实现`51a7670`，Debug29/`20261116.2307`；无原译文不渲染气泡，输入仍finish等待迟到字幕；Finished且播放drain后才移除纯空结果。保留translation-only/source-only内容、不删除新一轮。v28短句松手/连续修复保持。
- 新增显示映射与VM静音结束清理回归均先red后green，另覆盖旧空句结束不删新句及单边文字保留。408单测通过，lint无error（48warning），Debug和AndroidTest构建成功。云端Android CI`43893327120`、模拟器UI回归`43893327143`成功。无新增收费语音请求，未做真机安装或声音验收。
- 原Debug包名证书不变，APK SHA256 `0068e8d967f878952b03e5a194f7a7323d4f8ab843bf19e472e1c8e0b92db7cb`，GitHub tag `android-debug-yilu-20261116-v29`固定实现提交。正式21APK直接复用，未重建未改清单production项。
- 统一工具切换快照`downloads-20261116T151732Z-3411128`，双APK公网完整下载hash、网站清单/页面、两OTA对照bundle成功；独立检查正式公网OTA仍21、Debug29及hash，下载页/Agent/Cloud/admin均200。4项发布脚本测试及shell语法检查通过。后台模型保持Qwen3.8。

## Debug v28输入与连续模式修复（2026-11-15）

- 实现`064b789`，Debug28/`20261115.2103`，固定原Debug包名及证书。正式21/`20261113.1836`整个清单及APK保持不变。
- 正常松手不再依赖首字幕决定取消，保留已采集输入并finish等待结果；pointer cancel仍取消。AUTO完整final pair后封口，Finished及可听播放drain后自动开启下一句、复用Cloud grant；pause/stop/cancel/error不自动重开。录音任务/回调及返回值带代次门槛，逆序final pair也能处理，提前Finished明确停止错误，不无限重连。
- 405项Debug单测通过（包含原先两项红色诊断及新增无字幕松手/连续三句/停止/迟到回调/提前结束边界）；lint0error/48warning，Debug及AndroidTest包构建通过。GitHub Android CI `43717131072`、模拟器UI回归`43717131067`均成功。现无ADB设备，未做真实录音/模型调用/真机覆盖安装；用户声音、长时稳定性仍待验收，Ready前缓存顺序沿用既有Socket实现，未新增真实WebSocket端到端测试。
- GitHub预发布tag `android-debug-yilu-20261115-v28`指向实现提交；APK SHA256 `9feec005ad8ae3a89178d268fddecb6f657af6a12f987ec5ac525615ce5bb499`。
- 统一工具部署快照`downloads-20261115T131258Z-1708481`，两APK公网完整下载hash、网页/清单/Debug OTA和正式源站OTA严格对照bundle通过；另核对正式公网OTA仍21、测试28及hash相符，正式下载页/Agent/Cloud/admin均200。4项发布脚本单测与shell语法检查通过。
- 不更改线上Qwen3.8服务、不重建正式包、不清数据。测试版通过App内检查更新或官网下载覆盖安装。

## 初次上线状态

正式v20 /20260915.0232和Debug v26 /20261110.1930原包下载并核验成功。4项发布工具回归与shell语法检查通过；实际核验APK哈希、包名、版本和既有签名。

- 已安装双Host路由，Caddy备份 `/etc/caddy/Caddyfile.before-downloads-20261113T052342Z`，validate/reload成功，Caddy/Cloud API/Agent active。
- 当前快照 `/opt/dngmeng/downloads/snapshots/downloads-20261113T052707Z-1038246`；成功执行完整同步工具，网页、清单、Debug OTA和正式源站OTA均与本次bundle严格对照一致，两APK从公网完整下载后SHA匹配，Range206及附件/长度/类型头正常。
- 备用公网入口 `https://47-129-170-16.sslip.io/download` 已200，API health、Agent health、管理后台均200；页面为响应式CSS，但尚未用真实浏览器逐尺寸视觉验收，不宣称已完成手机观感测试。
- 官网源站 `https://yszn.nlplayer.org/download` 正确Host/TLS直连200；公网经Cloudflare仍同URL308循环。用户暂不能调整Cloudflare，故正式官网公网访问仍为阻塞项，不能视作全任务完成；未降低TLS或绕过代理。
- App更新仍需用户核验真实下载/系统安装。版本号、签名、APK内容未变化，不需要为URL托管变化升级App。
