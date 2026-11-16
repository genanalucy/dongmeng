# 人工翻译测试材料与版本

本目录是当前人工测试材料入口，不是已执行的验收报告。适用于选择候选后的场景测试；**Debug v30用户已更新，尚未测试**，正式v21与Debugv29只作独立版本对照，身份与APK记录见[下载版本说明](../docs/deployment/download-center.md)。空白质量表/反馈表不表示任何版本通过。安装固定版本前核对包、证书和数据兼容性；签名冲突不自动卸载/清数据，详见上述下载版本说明。

| 材料 | Markdown维护入口 | 既有PDF导出材料 | 来源关系 |
| --- | --- | --- | --- |
| 中英/中越等双向对话 | [8轮套件文案](dialogue-test-scenarios.md) | [套件PDF](dialogue-test-scenarios.pdf) | 与根目录6轮旧版不同，分别保存；不合并去重 |
| 中法等多语场景 | [多语文案](multilingual-dialogue-test-scenarios.md) | [多语PDF](multilingual-dialogue-test-scenarios.pdf) | 源语言/目标语以表格及当前产品实际支持为准 |
| 翻译质量 | [空白质量验收表](translation-quality-checklist.md) | [表格PDF](translation-quality-checklist.pdf) | 逐场景记录原意、方向、数字、延迟等 |
| 使用体验 | [空白反馈表](software-usage-feedback-form.md) | [反馈PDF](software-usage-feedback-form.pdf) | 记录操作、稳定性、满意度与问题 |

后续文案修改以本目录Markdown为维护入口，PDF是独立的既有分发资产。**本轮没有实际比对PDF文本/排版和Markdown，也没有核对导出脚本或生成记录，不能证明任何一对PDF由同名Markdown导出或与之内容一致。** 不凭同名、Git提交相同或哈希存在作一致性保证；下次分发前应核对/重导并记录源commit、文件hash、导出工具与人工抽查结果。

根目录原6轮[Markdown](../archive/documents/test-materials/dialogue-translation-test-scenarios.md)及[PDF](../archive/documents/test-materials/dialogue-translation-test-scenarios.pdf)作为独立历史版本归档，原Markdown278行，套件8轮Markdown507行（迁移前快照）；两份Markdown/PDF各自哈希不同。两份PDF与各自源的关系均未证明。旧v22[第一阶段记录](../archive/evidence/operations/docs/reviews/android-phase1-regression.md)保留当时全部未测真机项，不自动套用为v30结果。

测试时补记包名/versionCode/versionName、源码与APK SHA-256、设备/Android、耳机、模式/语言、实际服务入口、日期/时区、步骤和预期/实际。质量表现有语言勾选可另填多语方向；另附保存/重试、TXT/JSON导出、账号切换、删除优先的实际步骤和结果。真实文件提供方、长期语音和跨设备同步仍待验收；参考译文不能当作真实模型输出。自动检查不能替代听音、安装、网络或用户体验。

## 本次保留的文件身份

快照来源为 `079108bff4b55d1b3783ca8e99e3376a35804ee0`；以下8份文件本轮逐字节保留，迁移前后SHA-256相同。Git最后变更提交只表示版本库来源，不能证明生成关系。源与导出的维护关系说明在本页，材料正文未重写。

| 文件 | Git最后变更提交 | 迁移前 = 迁移后 SHA-256 |
| --- | --- | --- |
| [dialogue-test-scenarios.md](dialogue-test-scenarios.md) | `1c949c6dd194db6dadc9fb5708252c3f07ee6faa` | `619db3ada07f90a842d961304f9b96f260493b1fd9e8dd65b89f070bf936ef0a` |
| [dialogue-test-scenarios.pdf](dialogue-test-scenarios.pdf) | `4ae0fef8889ceac2a2db58d42ac1f853a6a95ddc` | `bea9315779b711c18fd2c3257b1342b9bc503649eff53b14cbcdcf9e6e40dfc8` |
| [multilingual-dialogue-test-scenarios.md](multilingual-dialogue-test-scenarios.md) | `1abf1591ee2c1a001495a51604a4b4f49a8a655a` | `0874fee0b9ccb2bcd9ce8953ae6f06abe324a9b7e0e81bae670eeb2ba411a4fb` |
| [multilingual-dialogue-test-scenarios.pdf](multilingual-dialogue-test-scenarios.pdf) | `bec885983928973849757f5d31df38b903b116a8` | `99b992846c07ef4a52328cff3649acffee1a5b19070b7857fa06a30770898c9d` |
| [software-usage-feedback-form.md](software-usage-feedback-form.md) | `1c949c6dd194db6dadc9fb5708252c3f07ee6faa` | `142eccffa55f297185506081ee9e643f21c31c209f8c739c2b199ae135c7c4d7` |
| [software-usage-feedback-form.pdf](software-usage-feedback-form.pdf) | `4ae0fef8889ceac2a2db58d42ac1f853a6a95ddc` | `15528de904f0fd6a73791908745ae19eb8fa5426b4c74ba1d3eac9703e586039` |
| [translation-quality-checklist.md](translation-quality-checklist.md) | `1c949c6dd194db6dadc9fb5708252c3f07ee6faa` | `3c08d7a6a9eb7193f474a4bc8df1552315a080e9a2dca812c679037136060499` |
| [translation-quality-checklist.pdf](translation-quality-checklist.pdf) | `b7188997c7a091388c86a64cf328ffc2ad528937` | `5c64b367e5074c416212c66cf3b982f010dd9cab465e92fbadbbd9955f34ffd4` |

原6轮材料与本批历史报告的逐文件前后哈希见[06归档索引](../archive/indexes/06-operations-and-test-materials.md)。返回[项目首页](../README.md)。
