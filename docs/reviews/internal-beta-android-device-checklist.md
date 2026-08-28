# Android Internal Beta 真机验收清单（受控、token-safe）

**用途：** Task 8 的两分钟普通用户主路径验收。此清单只适用于批准的 **internal beta** 受控测试网络，使用 Debug APK 的 HTTP/WS 临时联调；**不是生产流程，绝不可用于 Release、公开分发或扩大测试人群。**

**本次清单不要求、也不允许提交或发送：**密码、access/refresh/session JWT、任何 token、DSN、Provider/API key、`.env` 文件、数据库导出、完整/raw logcat、原始音频、录音、完整对话/字幕正文。截图必须先确认未显示上述内容。

## 0. 责任与边界

| 角色 | 负责事项 | 禁止事项 |
| --- | --- | --- |
| 操作员 | 只在受控内网启动已批准的 Cloud API 与 Translator Agent，执行健康检查及最小化、只读的会话/usage 核对 | 不打印或复制运行时秘密；不启动真实 Provider 作为本清单的预检；不连接或改动数据库以完成本清单 |
| 验收用户 | 安装 Debug APK，按下列步骤完成一次普通用户中译英，反馈安全截图、可见错误文本或“成功/失败” | 不发送凭据、token、日志、录音或对话正文 |
| 记录人 | 填写 token-safe 验收记录模板 | 不从截图、日志或后端复制个人数据或秘密 |

## 1. 已批准的 Debug 网络范围

以下值来自当前 Debug 构建配置，仅为短期 internal beta 联调地址；不得写入 Release 配置或视为生产地址。

| 服务 | Debug URL / 端口 | 使用方式 |
| --- | --- | --- |
| Cloud API 控制面 | `http://114.132.83.144:8080` | 注册、登录、trial/entitlement、翻译会话、结束与聚合 usage |
| Translator Agent health | `http://114.132.83.144:18765/api/health` | 仅操作员健康检查 |
| Translator Agent WebSocket | `ws://114.132.83.144:18765/ws/translate` | App 数据面；会话 JWT 仅由 App 经 WebSocket subprotocol 在内存中传递 |
| 受控测试 Origin | `http://114.132.83.144:15173` | Debug Agent 请求 Origin；不是对外 Web 服务承诺 |

Debug 可使用 HTTP/WS 是受控内测的例外。Release 必须仅使用 HTTPS/WSS；未配置正式域名、TLS、Secret Manager、托管数据库、监控和 Release 签名之前，禁止生产发布。

## 2. 启动前提（由操作员完成）

- [ ] 已确认当前测试获得 internal beta 批准，使用可信测试网络和普通 `role=user` 测试账户；不得使用管理员账户替代普通用户验收。
- [ ] 已按受限的本机运行手册启动 Cloud API 与 Translator Agent；启动参数、环境变量与秘密均不粘贴到聊天、文档、截图或日志。
- [ ] Cloud API 的受限运行配置与 Agent 的 session JWT 校验配置一致；Agent 仍启用会话 JWT 校验，未为联调放宽鉴权。
- [ ] 在不输出敏感响应内容的前提下，操作员确认 Cloud API readiness 和 Agent health 均成功。若任一失败，停止验收并记录为“未开始”。
- [ ] 确认 App 的“服务设置”显示上述 Cloud API 地址，而不是历史的 `127.0.0.1:8080`。若仍为旧值，恢复默认、重启 App；仍不正确则卸载后重装本次 APK。
- [ ] 仅当已批准的真实 Provider 已在受控环境中可用时，才进行本清单中的真实中译英；不得为验收启动 mock/fallback 并把其结果称为真实翻译。若真实 Provider 不可用，停止在此处并记录“未运行/服务不可用”。数据库核对仍只能由批准操作员在独立、最小化的只读流程中进行。

## 3. APK 安装与普通用户路径（验收用户）

1. [ ] 从测试人员提供的本地 Debug APK 安装 `com.verba.interpretation.debug`；不要将 APK 上传到公开渠道。
2. [ ] 首次启动时允许“麦克风”权限。若拒绝，记录可见提示后结束本次，不反复尝试绕过系统权限。
3. [ ] 使用普通测试用户完成注册或登录。密码只在 App 输入，**不要**将密码、邮箱、任何 token 或登录截图发送给记录人。
4. [ ] 确认页面显示试用（新注册用户应有 3 天 trial）或已有有效 entitlement；没有权益时不应尝试直连 Agent，记录界面可见错误文本即可。
5. [ ] 打开“单人同传”，选择源语言“中文”、目标语言“English”。
6. [ ] 开始一次会话后，只说以下验证句：`你好，今天天气很好。` 不要说个人或敏感信息。
7. [ ] 确认显示英文字幕或听到英文语音。只反馈“字幕”“语音”“两者均有”或“均无”；不要转写字幕全文或发送录音。
8. [ ] 点击“结束/停止”，等待界面退出录音/连接状态，不崩溃、不永久停留在录音或连接中。
9. [ ] 仅在确认没有秘密、个人资料或对话正文时，发送一张页面状态/可见错误截图；否则仅报告“成功/失败”和可见的通用错误码/错误文本。

## 4. 停止、会话与 usage 确认（操作员）

- [ ] 用户点击停止后，App 已停止麦克风采集，并让已开始的 turn/TTS 走完或取消；不会遗留活跃录音。
- [ ] 依据 Task 5 已验证的 `TranslationSessionCoordinator` 生命周期，预期同一个 Cloud Translation Session 最多发起一次最小聚合 usage 上报（仅 `session_id`、`audio_seconds`、`characters=0`）；usage 上报失败不得阻塞会话结束或 UI 收口。
- [ ] 若批准的独立操作流程允许，操作员用**只读、最小化且不显示用户字段**的方式确认：该 session 已结束/撤销，且此 session 恰有 1 条聚合 usage。不得在本清单、聊天或截图中写入 session ID、邮箱、JWT、DSN、SQL、原始行或任何正文。
- [ ] 本次会话不应保存 PCM、原文、译文正文、密码或 JWT。任何疑似泄露均按失败收口处理。

## 5. 失败收口与安全日志规则

发生登录/权益/连接/翻译/停止失败时：

1. 立即停止采集并点击结束；若界面无响应，关闭 App。不要重试到产生多个并行会话。
2. 只记录步骤编号、时间、App 版本、网络类型、是否有安全截图，以及页面可见的非敏感错误文本。
3. **禁止收集、导出或发送完整 logcat、bugreport、WebSocket 抓包、HTTP header/body、数据库输出或环境文件。** 它们可能含 token、DSN、API key、邮箱或对话数据。
4. 若必须由授权工程师诊断，工程师须在受控设备上自行执行最小化、预先脱敏的日志观察；不得将 token、`Authorization`、`Sec-WebSocket-Protocol`、JWT、密码、DSN、API key、Cookie、完整 URL query、音频/字幕正文写入任何记录。
5. 怀疑秘密泄露、会话未收口、重复 usage、崩溃或权限绕过时，停止本轮验收，标记“失败/安全事件待处理”，并转入受控事件流程；不要在聊天中附加材料。

## 6. 已知限制

- 此 APK 是 Debug 包；HTTP/WS 和临时公网映射仅适用于内部受控联调，不代表生产可用性。
- 本清单不证明 HTTPS/WSS、正式域名、Release 签名、商店发布、Secret Manager、托管 PostgreSQL、备份、监控、限流或告警已就绪。
- 真机结果必须由用户/设备实测获得；单元测试、fake-Provider 授权 E2E 或 APK 成功构建均不能替代真实翻译结果。
- 真机音频路由会受设备、耳机、蓝牙 profile、厂商 DSP、权限和网络影响；本次只覆盖一次中文到英语的单人路径，未覆盖长期录音、面对面模式、法语/越南语和耳机左右声道。
- 若真实 Provider 或受控服务不可用，本次应诚实记录为“未运行/失败”，不得静默 fallback 到 Mock 或声称翻译成功。
