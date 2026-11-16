> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/auto-face-mode/issues/03-auto-safety-lifecycle.md`；原始 SHA-256 `1477ccee128466c1bf5c3e367fa6c0c19fc002908e0b46242a30eb06e09d7a28`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 03: 自动模式安全拒绝与生命周期

**What to build:** 自动模式对 malformed provider 输出、空最终对和 provider failure fail-closed：不播放、不留存、不产生完成 Turn，并以可恢复错误终止本段 provider session；暂停、继续、停止、翻译错误与强制恢复翻译能安全停止采集和处理未完成 Turn，同时保留已完成 Turn。Azure 的候选外语言可能被映射为 A/B；直连协议没有低置信度结果；当前未实现重叠检测，均不承诺静默拒绝。

**Blocked by:** 02 自动模式单段双向路由.

**Status:** implemented

- [x] malformed/non-candidate provider 检测结果、空最终对和 provider failure 不会生成目标耳机 PCM、已完成 Turn 或历史候选；空最终对以可恢复 terminal error 结束，避免 capture 卡住或自动续转循环。
- [x] 暂停后不采集或路由音频；继续后下一段重新进行自动识别；停止后遵循现有安全排空和关闭规则。
- [x] 最终对已请求 Finish 的段具有明确 finishing 状态；紧接的 pause/stop 不会重复 Finish，随后 Finished 也不会自动新建段。
- [x] 强制恢复翻译仅丢弃当前未完成自动 Turn，保留已完成 Turn，并可立即继续对话。
- [x] 覆盖拒绝、空最终、暂停/继续、停止、错误和恢复的协调器/Provider 生命周期测试均从用户可观察状态验证。
- [x] 第三语言可能被 Azure 强制映射为候选 A/B，是接受风险；低置信度和重叠检测未实现，不能宣称静默拒绝。
