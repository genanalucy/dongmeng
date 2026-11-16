> **历史资料，非现行规范或本轮验收。** 来源：main-baseline 工作区未提交资料样本（工作区 HEAD `dc587eff340abe4b8d59a6667e2fc34c81e30e0a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/phone-headset-dual-input/issues/01-device-capability-probe.md`；原始 SHA-256 `1b0d25c74128801baffc002faff150fc7ae752e8a28731fafc3c7957363488dc`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# 验证普通耳机麦＋手机麦双输入

Status: deferred
Type: task

用户要求先记录、暂不实施，当前优先优化App测试版。

目标：耳机麦收佩戴者、手机麦收对方，尝试免点按双向对话。先用普通蓝牙样机做离线能力探针：真实输入设备、两路非静音/非同源、串音、HFP/SCO或LE Audio路由、耳机/手机播放与回声、断连恢复。无需供应商翻译调用。设备依赖，不承诺普通耳机通用；讯飞专用耳机测试不能替代普通样机验证。

参考：.scratch/research/phone-headset-dual-input-summary-2026-11-05.md。
