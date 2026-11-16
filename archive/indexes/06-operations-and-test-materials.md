# 06：运维、发布与测试材料归档

效力：本批为历史规划、历史运行/发布证据和旧测试文案建立可见来源。现行入口：[运维效力](../../docs/operations/README.md)、[双通道发布](../../docs/deployment/download-center.md)、[人工测试及源/PDF关系](../../translation-test-kit/README.md)。返回[归档入口](../README.md)。

共同来源：迁移前整理基线 `079108bff4b55d1b3783ca8e99e3376a35804ee0`（v30源码 `cd920a1` + 发布资料 `dc587ef` 后的导航整合）。均为该提交中已跟踪文件，无未提交或本机私有配置入库；来源commit不是各次生产部署提交。每份历史报告自身登记的实现、二进制hash、授权、失败和未测事项仍保留，只代表记录当时。本批未连接生产/公网，未下载APK、部署、调用真实供应商或验证手机；v30用户已更新、尚未测试。

JSON和PDF逐字节保留，不在文件内插入标注；效力由本索引限定。Markdown增加历史标注并修复相对链接；旧“当前”不再表示今日。归档内容不参与当前site输入、脚本发现或部署；活动 `site/download/`、发布工具与旧v26迁移哨兵保留原位。Caddy参考继续位于 `docs/deployment/Caddyfile.production`，只增加非现场配置说明，路由指令不变。

## 逐文件来源、用途和内容完整性

表中“前”是共同来源中原路径**全文件**SHA-256，“后”是归档文件SHA-256。摘录条目另给出原始摘录hash，明确是历史段拆分而非原全文件逐字移动。原位置为已迁移路径，不制造失效链接；归档位置与现行替代均可打开。

### `docs/deployment/local-vm-requirements.md`

- 归档：[文件](../documents/operations/docs/deployment/local-vm-requirements.md)；来源：上述完整commit的 `docs/deployment/local-vm-requirements.md`。
- 原用途与效力：混合部署本地VM需求规划；不是已实施架构。
- 原因：规划未证明实施；当前环境须另行核验。现行替代：[入口](../../docs/operations/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`4bfb739f7123960d288bebcc5d59f7883e9fd938985e4e13b7833be48b34cccd`
- 后 SHA-256：`10f6cbd3fd038be23fb2aa2c6f858cf4d7e146ac189b2615e4c40a397834635c`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/aws-singapore-requirements.md`

- 归档：[文件](../documents/operations/docs/deployment/aws-singapore-requirements.md)；来源：上述完整commit的 `docs/deployment/aws-singapore-requirements.md`。
- 原用途与效力：AWS入口资源与网络规划；不是资源清单。
- 原因：规划不能证明现有AWS资源或隧道部署。现行替代：[入口](../../docs/operations/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`64c68860f9e76b22a150481cccddf67ad0bda69009194519f61efcdc8d7e38ac`
- 后 SHA-256：`cc80ff641bef34b95897a86e43207b88c07e4209410345ac244d64b32e568227`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/app-ota-update.md`

- 归档：[文件](../evidence/operations/docs/deployment/app-ota-update.md)；来源：上述完整commit的 `docs/deployment/app-ota-update.md`。
- 原用途与效力：旧Cloud环境/静态Debug更新步骤及v22–26发布记录。
- 原因：2026-11-13起由统一快照发布流程替代；不再按旧JSON单独更新。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`9cdd15b97d4c86c3ec124d4d404909ca95b2539751c89da5ed663d72798c14dd`
- 后 SHA-256：`22db97934bb82b9a9ebed9b409408946117344dcdf850d3443530ac37a5de732`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/operations/volcengine-routing.md`

- 归档：[文件](../evidence/operations/docs/operations/volcengine-routing.md)；来源：上述完整commit的 `docs/operations/volcengine-routing.md`。
- 原用途与效力：2026-11-14火山独占切换报告及未完成事项。
- 原因：后续Qwen切换已取代该次运行状态；旧独占约束不继承为当前产品规范。现行替代：[入口](../../docs/operations/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`c6200ca1856ceea72df4e56b834c3704ddeb6379c1f4e9c0e8a2dedcc0559c89`
- 后 SHA-256：`4bb417568e9139f05b0fa18a65bb13dd317a0c41a23c68bc2730a1ac020c4278`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/operations/qwen38-routing.md`

- 归档：[文件](../evidence/operations/docs/operations/qwen38-routing.md)；来源：上述完整commit的 `docs/operations/qwen38-routing.md`。
- 原用途与效力：2026-11-14阻塞诊断与11-15部署记录。
- 原因：运行快照与更晚v30发布记录分开；不能当作今日现场核验。现行替代：[入口](../../docs/operations/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`a44bda52b1f27660bb7d1227ecb8db9ca767d52d9f8dec412339f3e2f8bb7040`
- 后 SHA-256：`5be19f25ada49156fb1c8a4963bd4f75600ac5fe744896450d9bc8b11c53226c`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/reviews/android-phase1-regression.md`

- 归档：[文件](../evidence/operations/docs/reviews/android-phase1-regression.md)；来源：上述完整commit的 `docs/reviews/android-phase1-regression.md`。
- 原用途与效力：11月4–5日v22产物与回归记录，含未测真机项。
- 原因：版本已被后续候选取代；保留两份v22产物身份及全部未测项，不作为v30验收表。现行替代：[入口](../../translation-test-kit/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留；两处本机用户绝对路径改为受限产物目录说明，未纳入原始设备/构建产物。
- 前 SHA-256：`068dfb657d4ce0a5b9398c2923115c21a5e696b946eb6b02f9d3a8de5dac57b1`
- 后 SHA-256：`786b7e870c3458c02efd2cfa63da1f26e8c57848cdc911528eed3252c9414b40`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `dialogue-translation-test-scenarios.md`

- 归档：[文件](../documents/test-materials/dialogue-translation-test-scenarios.md)；来源：上述完整commit的 `dialogue-translation-test-scenarios.md`。
- 原用途与效力：根目录6轮模拟文案；与套件8轮文本不同。
- 原因：当前人工测试入口统一到套件；不同内容作为独立历史版本完整保留。现行替代：[入口](../../translation-test-kit/README.md)。
- 内容处理：增加历史来源标注并修正相对链接；正文及未测项保留。
- 前 SHA-256：`4553427ff28e3ff929c45b88b4b608d3e39860b61c82eca5c660dcad72f9d936`
- 后 SHA-256：`08858d2275d721b533c69ad2218188318230d6d2c6ec10caa3d3b2fec748904c`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `dialogue-translation-test-scenarios.pdf`

- 归档：[文件](../documents/test-materials/dialogue-translation-test-scenarios.pdf)；来源：上述完整commit的 `dialogue-translation-test-scenarios.pdf`。
- 原用途与效力：根目录既有PDF；导出来源未核实。
- 原因：独立保留，不与套件PDF合并或声称同源。现行替代：[入口](../../translation-test-kit/README.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`ad44da8c9f29aa0ab38e72253b57413c8fb20c041a70f6d555877e3988b3fbed`
- 后 SHA-256：`ad44da8c9f29aa0ab38e72253b57413c8fb20c041a70f6d555877e3988b3fbed`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/ota/debug-v22.json`

- 归档：[文件](../evidence/operations/docs/deployment/ota/debug-v22.json)；来源：上述完整commit的 `docs/deployment/ota/debug-v22.json`。
- 原用途与效力：旧Debug v22发布元数据。
- 原因：现行清单是site/download/releases.json；历史JSON不再是发布输入。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`9a813bbb55bd41c9a9538dbbad4123cd64d6883f5284e721f6cf2b7e807a9dd4`
- 后 SHA-256：`9a813bbb55bd41c9a9538dbbad4123cd64d6883f5284e721f6cf2b7e807a9dd4`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/ota/debug-v23.json`

- 归档：[文件](../evidence/operations/docs/deployment/ota/debug-v23.json)；来源：上述完整commit的 `docs/deployment/ota/debug-v23.json`。
- 原用途与效力：旧Debug v23发布元数据。
- 原因：现行清单是site/download/releases.json；历史JSON不再是发布输入。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`20ea9f40dd9ff41416e4de928cf83e25853ae905596ca204892d1d2ec697db3b`
- 后 SHA-256：`20ea9f40dd9ff41416e4de928cf83e25853ae905596ca204892d1d2ec697db3b`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/ota/debug-v24.json`

- 归档：[文件](../evidence/operations/docs/deployment/ota/debug-v24.json)；来源：上述完整commit的 `docs/deployment/ota/debug-v24.json`。
- 原用途与效力：旧Debug v24发布元数据。
- 原因：现行清单是site/download/releases.json；历史JSON不再是发布输入。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`3b02a9ef689089c2ec98bc64a8689724f5152c724968328709db3384f9281e08`
- 后 SHA-256：`3b02a9ef689089c2ec98bc64a8689724f5152c724968328709db3384f9281e08`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/ota/debug-v25.json`

- 归档：[文件](../evidence/operations/docs/deployment/ota/debug-v25.json)；来源：上述完整commit的 `docs/deployment/ota/debug-v25.json`。
- 原用途与效力：旧Debug v25发布元数据。
- 原因：现行清单是site/download/releases.json；历史JSON不再是发布输入。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`2ebd020cf8e28eeee4ae89011b94e79a93603a002f2a7cf349784ebf48a4a198`
- 后 SHA-256：`2ebd020cf8e28eeee4ae89011b94e79a93603a002f2a7cf349784ebf48a4a198`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/ota/debug-v26.json`

- 归档：[文件](../evidence/operations/docs/deployment/ota/debug-v26.json)；来源：上述完整commit的 `docs/deployment/ota/debug-v26.json`。
- 原用途与效力：旧Debug v26发布元数据。
- 原因：现行清单是site/download/releases.json；历史JSON不再是发布输入。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：逐字保留（二进制/JSON无内嵌标注，效力见本索引）。
- 前 SHA-256：`c09e8726912e7f840695ca710c60b3a2bc7156e0ffdd0d219790b4c4b0ca2eb9`
- 后 SHA-256：`c09e8726912e7f840695ca710c60b3a2bc7156e0ffdd0d219790b4c4b0ca2eb9`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/deployment/download-center.md`

- 归档：[文件](../evidence/operations/docs/deployment/download-center-releases.md)；来源：上述完整commit的 `docs/deployment/download-center.md`。
- 原用途与效力：11月13–17日改名、Debug v28–30与初次下载部署发布者记录。
- 原因：将现行步骤与历史发布/现场断言分开；不把既往检查写成本次通过。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：从来源文件提取该标题起至文件末尾的完整历史段落，增加历史来源标注；来源全文件和原始摘录哈希分别列出，原位保留现行步骤。
- 前 SHA-256：`29712882a6eab3ad65dbcd020bbcfabb33bee956a7fba92fcba79258cc552d00`
- 后 SHA-256：`c86cb434bbf08b33f4e84029e4fc26af1e2f8cd7d3d727d7eb308fe806efd0a5`
- 原始摘录 SHA-256：`16a2a58b92246dcb428f1f491dd7cf7b0ed8d6f5c13a68b4eb85af3bc90ee80a`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/operations/shared-invitations.md`

- 归档：[文件](../evidence/operations/docs/operations/shared-invitations-deployment.md)；来源：上述完整commit的 `docs/operations/shared-invitations.md`。
- 原用途与效力：11月11日共享码数据库迁移、签发和运行核验的发布者记录。
- 原因：将现行步骤与历史发布/现场断言分开；不把既往检查写成本次通过。现行替代：[入口](../../docs/operations/shared-invitations.md)。
- 内容处理：从来源文件提取该标题起至文件末尾的完整历史段落，增加历史来源标注；来源全文件和原始摘录哈希分别列出，原位保留现行步骤。
- 前 SHA-256：`29e78756446422253ecdcbd1524f447f3d59af7a23c6d15a76f58ba242d02856`
- 后 SHA-256：`2bb8bf2a82056d5e70fcf9659d061d07a865179ce0c6bf8b6e11514679ba503f`
- 原始摘录 SHA-256：`9246a498d874de9284ac35473e7988bd8f39c9220fadedf5e8c02c8b27af3e5c`
- 验证与未完成项：本地来源/前后hash及链接检查；历史记录未测项不改为通过。未核验当前生产、真实供应商、APK安装或用户体验；PDF另未核对源/导出一致性。

### `docs/internal-beta-update-guide.md`

- 归档：[文件](../documents/operations/docs/internal-beta-update-guide.md)；来源：上述完整commit的 `docs/internal-beta-update-guide.md`。
- 原用途与效力：旧内部Beta安装、注册和问题反馈手册。
- 原因：任取最新Pre-release/app-debug.apk、旧手机号注册与签名冲突卸载建议不适合作为v30候选流程；固定版本、包与数据兼容性优先。现行替代：[入口](../../docs/deployment/download-center.md)。
- 内容处理：旧正文完整保留；头部增加历史效力、来源、现行入口以及不沿用旧卸载建议的说明。
- 前 SHA-256：`82276b6e7066ffcea2e796c9121eb4a5eadc0f327f584122cd15d2cb89438ea9`
- 后 SHA-256：`f09a123dbc638dbe71e59698b2ddc165edd998008301a25305df19398446e951`
- 验证与未完成项：来源/hash和链接核对；没有执行旧安装/注册步骤、卸载、线上请求或设备验收。

## 当前材料与保留边界

套件8份Markdown/PDF的来源、逐文件迁移前后相同hash见[测试材料身份表](../../translation-test-kit/README.md#本次保留的文件身份)；正文与二进制均未重写。根6轮与套件8轮内容不同，保留为两个版本，没有合并去重；PDF是否对应各自Markdown仍未证明。测试材料用于后续人工验收，不构成已验收证据。

本批只修复[旧基线](../../docs/development/baselines.md)指向v22记录的链接，保留旧基线正文和未测状态。当前下载说明保留准备、两通道、旧客户端兼容、校验、原子切换和回滚步骤，拆出历史段；共享码当前说明同样保留签发/隔离检查/安全和回滚边界。SMTP只增加源码事实、运维要求与未核验现场的效力说明，不更改投递或数据库设置。

旧原型/阶段spec/plan/handoff及其他reviews由05批次处理；工具和本地资料由07处理；Makefile、统一检查及当前人工回归入口由08负责。本批不继承旧阶段约束、不新增产品行为、不更改AGENTS/领域文档，不发布新APK。

本轮检查命令：`python3 -m unittest discover -s scripts -p 'test_download_release.py'`、`bash -n scripts/deploy-download-release.sh`、`git diff --check`，以及对本索引、归档文件和变更导航做相对链接/来源hash核对。既有下载工具测试模拟APK验证，不证明实际APK签名或公网可下载；检查结果详见本批交付记录。
