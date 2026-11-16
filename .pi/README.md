# 项目代理工具

Pi 从 `skills/` 发现技能，从 `prompts/` 发现 OpenSpec 提示词。仓库自带的 OpenSpec、caveman 和 UI/UX 技能可直接读取；37 个 Matt Pocock 技能使用相对符号链接，需要安装固定版本的上游仓库。

在项目根目录执行（需要 Python 3 和 Git）：

```bash
python3 scripts/setup-agent-skills.py
python3 scripts/setup-agent-skills.py --check
```

安装入口读取 [skill-sources.json](skill-sources.json)，把公开上游的固定提交检出到被 Git 忽略的 `.pi/git/`。首次安装需要访问 GitHub；检查命令离线运行，不写文件。固定提交来自整理前已有效的工具缓存，属于工具来源记录，与产品源码版本无关。

已有可信本地 Git 镜像时，可以离线安装；镜像必须包含清单中的提交：

```bash
python3 scripts/setup-agent-skills.py --source /path/to/local/skills-repository
```

安装器逐个确认技能链接能找到 `SKILL.md`。重复安装复用相同版本；遇到版本不同、文件缺失或本地修改时返回失败并保留原缓存。先把旧缓存保存到仓库外，再重新安装；需要升级时同时审阅固定提交和链接目标。不要通过覆盖或重置缓存丢弃本地成果。缓存、私有代理配置、调度记录均留在本机。

UI/UX 命令也从项目根运行，例如：

```bash
python3 .pi/skills/ui-ux-pro-max/scripts/search.py "accessibility" --stack jetpack-compose -n 1
```

脚本根据自身位置读取随仓库保存的 CSV；[技能说明](skills/ui-ux-pro-max/SKILL.md)列出其余参数。OpenSpec 的 [配置](../openspec/config.yaml)、提示词和技能目录继续保留。工具安装不运行产品、连接服务或部署。
