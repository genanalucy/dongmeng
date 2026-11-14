# 线上火山独占路由（2026-11-14）

## 事实修正

此前将“移除Azure”误报为“线上只用火山”。实际部署的routingClient.Start直接选择Qwen，11月14日中越两个方向日志也出现Qwen错误。不得再依据provider请求字段、火山客户端代码存在、或health200来判断供应商。

## 本次变更

实现提交`4d5a382`：NewRoutingClient只返回注入的火山客户端；即使有千问凭据也不选择/回退。配置必须有火山认证，Qwen-only不再通过。生产环境默认无tag编译的UnavailableClient拒绝启动；部署必须`-tags officialast`。Qwen客户端源文件仍保留，但不在当前服务启动路由上，生产运行环境已移除DASHSCOPE_/QWEN_变量。

默认及officialast两套Go test/vet通过，官方AST构建成功。新增回归覆盖zh→en、zh→vi、vi→zh、fr→en选火山，以及火山错误不回退；这是离线注入客户端测试，不是全部语言实测。

部署前限时直连火山official客户端检查zh→en、en→zh、zh→vi、vi→zh，均SessionStarted后发送一帧80ms静音并Finish，正常finished；未采集用户声音/测试翻译质量。检查工具临时创建，已从源码工作树移除，不长期驻留。该检查本身可能产生火山小额计费；未请求千问。

## 部署与回滚边界

备份`/opt/dngmeng/backups/volc-only-20261114T101846Z`保存旧二进制与受限环境文件（含秘密，禁止复制到Git/聊天）。部署后Agent2026-11-14T10:18:47Z启动记录provider=volcengine，运行进程环境无Qwen/DashScope变量，Caddy/CloudAPI/Agent active，源站和正式域名Agent health200。重启已终止旧Agent进程及其连接。

不修改APK/包名/OTA/账户/历史；正式和测试共用此Agent。新服务失败时必须停服务，不自动恢复旧Qwen路由二进制或其凭据。需要回滚时仍维持火山独占，另做修复，不能静默重新产生阿里调用。

## 未完成事项

千问历史消费归因尚需调用日志和阿里账单对照；本次切换不能证明此前消费都是测试、也不能撤回已有费用。若相同API key还用于其他系统，本次仅保证该翻译Agent不再使用它。

连续对话诊断已发现应用状态问题，原诊断测试仍未修复/未发布；切供应商不代表这些问题解决。真实中英/中越字幕与播音、其他语言质量和长时间翻译仍待验收。
