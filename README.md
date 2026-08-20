# 面对面实时翻译（本地交付说明）

这是一个本机回环部署的面对面翻译原型：Web 负责设备选择、麦克风采集、手动半双工 PTT、自动交替录音与左右声道播放；Go Agent 提供 `127.0.0.1:18765` 上的 health 和 WebSocket 边界。浏览器开发服务器默认在 `http://127.0.0.1:5173`，并将 `/api`、`/ws` 代理至 Agent。

> **构建模式**：默认 Go 构建不依赖本地官方 protobuf，并安全返回 `AST_CODEC_UNAVAILABLE`；使用 `officialast` build tag 的构建会启用真实火山引擎 AST 2.0 WebSocket、字幕与 PCM TTS 链路。官方生成代码不纳入 Git，须先运行固定 URL 与 SHA256 校验的准备脚本。

## 环境要求

- macOS（本文的蓝牙和 Chrome 权限步骤以 macOS 为例；开发脚本也可在具有 Bash、Go、Node.js 的 Linux 环境使用）。
- Go：`agent/go.mod` 当前声明 `go 1.23`；使用 Go 1.23 或更高版本。
- Node.js：建议使用当前 LTS（Node.js 20+）和随附的 npm；依赖锁定在 `web/package-lock.json`。
- Bash、`curl`、`python3`（`scripts/smoke-local.sh` 的静态 HTTP 检查使用）、GNU Make（执行 `make` 目标）。
- Chrome 或其他支持 `getUserMedia` 的现代浏览器。输出设备的页面内选择依赖 `HTMLMediaElement.setSinkId`；不支持时请在系统设置中设定默认输出。

首次安装 Web 依赖：

```bash
npm --prefix web ci
```

## 凭据脚本与本地环境

不要手工复制、粘贴或提交凭据。使用交互式脚本创建仅本机可读的 `agent/.env.local`：

```bash
./scripts/setup-volcengine-env.sh
```

脚本会静默读取凭据，写入权限为 `600` 且受 Git 忽略保护的文件。支持两种配置，二选一：

- 新版：`VOLCENGINE_API_KEY`（推荐）；
- 旧版：`VOLCENGINE_APP_ID` 和 `VOLCENGINE_ACCESS_TOKEN`（可选 `VOLCENGINE_SECRET_KEY`）；
- 可选：`VOLCENGINE_RESOURCE_ID`，未设置时使用 `volc.service_type.10053`。

`make dev` 与 `scripts/smoke-local.sh` 会在该文件存在时静默加载它，不会输出其中任何变量或值。两者会自动准备经过固定 SHA256 校验的官方生成代码，并使用 `officialast` build tag 启动真实 AST 客户端。

## 启动 Agent 与 Web

### 一条命令开发启动

```bash
make dev
```

该命令会：

1. 如有 `agent/.env.local` 则静默加载；
2. 如本地尚无官方生成代码，则从官方附件 URL 下载、校验 SHA256 并生成到 Git 忽略目录；
3. 使用 `officialast` build tag 将真实 Go Agent 预先构建到系统临时目录（不使用 `go run`）；
4. 启动 Agent：`http://127.0.0.1:18765`；
5. 启动 Vite：`http://127.0.0.1:5173`；
6. 在 `Ctrl-C`（SIGINT）或 SIGTERM 时停止两个子进程并删除临时二进制，避免遗留 `go run`/Agent 进程。

浏览器访问 <http://127.0.0.1:5173>。如端口已经被占用，先关闭占用 `18765` 或 `5173` 的进程再重试。

### 分开启动（排障用）

终端一，加载本地环境后启动 Agent：

```bash
set -a
source agent/.env.local
set +a
make prepare-official-ast
(cd agent && go run -tags officialast ./cmd/translator-agent)
```

终端二启动 Web：

```bash
npm --prefix web run dev
```

### 启用真实 AST 客户端

先下载并校验官方 Go Demo，再从归档中仅抽取非 gRPC `pb.go`，机械重写 Go import 到本模块：

```bash
make prepare-official-ast
```

生成目录为受 Git 忽略的 `agent/internal/officialastproto/`。准备脚本使用固定官方附件 URL 和 SHA256；校验不一致会停止，且不会改写 protobuf 消息字段。随后可构建或运行：

```bash
(cd agent && go build -tags officialast -o /tmp/translator-agent ./cmd/translator-agent)
set -a; source agent/.env.local; set +a
/tmp/translator-agent
```

也可一次执行 tagged 测试、vet 与构建：

```bash
make officialast-check
```

Agent health 检查：

```bash
curl --fail http://127.0.0.1:18765/api/health
```

预期包含 `{"status":"ok","service":"translator-agent"}`。WebSocket 仅接受 `http://127.0.0.1:5173` 与 `http://localhost:5173` 作为 Origin；应通过 Vite 页面连接，而不是从任意网页直连。

## macOS + Chrome 麦克风权限

1. 连接并确认 TWS 已配对（见下一节），然后在 Chrome 打开 `http://127.0.0.1:5173`。
2. 进入“面对面翻译”，在“面对面准备”中点击“授权麦克风”，在 Chrome 弹窗选择“允许”。设备名称在获得授权后才会完整显示。
3. 若 Chrome 未弹窗或此前点过拒绝：点击地址栏左侧的网站控制图标，进入“网站设置”，将“麦克风”改为“允许”，刷新页面后重新授权。
4. 仍无法访问时，打开 **系统设置 → 隐私与安全性 → 麦克风**，允许 **Google Chrome**；完全退出并重开 Chrome 后重试。
5. macOS/浏览器可能只暴露蓝牙耳机的免提（Hands-Free/HFP）输入，音质和采样率会低于播放模式；这是蓝牙配置限制，不是页面故障。优先使用 Mac 内建麦克风或独立 USB 麦克风作为输入、TWS 作为输出。

## TWS 连接、输入/输出选择与左右耳测试

### 连接和选择设备

1. 在 **系统设置 → 蓝牙** 配对并连接 TWS，确认其显示为已连接。
2. 回到页面点击“刷新设备”；如设备名仍为空，先执行“授权麦克风”。
3. 在“输入设备”选择讲话所用麦克风（推荐 Mac 内建/USB 麦克风；需要时也可选择 TWS 麦克风）。
4. 在“输出设备”选择已连接的 TWS。若页面显示浏览器不支持直接选择输出，请在 **系统设置 → 声音 → 输出** 将 TWS 设为默认输出，然后刷新页面。
5. 若页面提示耳机已断开，重新在 macOS 中连接 TWS，再点击“刷新设备”和重新选择输出。

### 左右耳测试

选择输出设备后，在“左右耳测试”中依次点击“测试左耳”和“测试右耳”：

- 左耳测试仅向左声道播放短提示音；
- 右耳测试仅向右声道播放短提示音；
- 仅在浏览器成功开始播放后，按钮才标记为“正常”。

若双耳都响、声道反向或没有声音，先确认 TWS 的系统声道平衡未偏移、输出设备是目标 TWS，并在 Chrome 中重选输出。部分蓝牙设备、系统混音或浏览器不支持独立 sink 选择时，只能使用 macOS 默认输出，左右声道实际表现以耳机/系统为准。

## Mock 模式与 Local Agent 模式

进入“面对面翻译”后可在“翻译模式”切换；切换只能在当前轮次空闲时进行。

- **模拟模式（Mock）**：完全在 Web 内运行确定性的演示文案。不会调用 Agent，也不会向外部服务发送麦克风 PCM；适合演示 PTT 流程、语言交换、字幕和左右耳路由。
- **Local Agent 模式**：Web 会检测 `GET /api/health`，并通过 Vite 代理连接本机 Agent 的 `/ws/translate`。Agent 离线时页面会锁定开始翻译，不会静默回退为 Mock；启动 Agent 后点击“手动检测”。

首页提供 **单人同声传译** 与 **面对面翻译**。单人同传支持中文/英文交换、一键开始、8 秒安全 Turn 连续分片、暂停/恢复/结束、实时原译文、复制和 UTF-8 TXT 导出；TTS 可选择双耳、左耳、右耳或仅字幕。仅字幕模式不要求输出设备，也不会调度或分发 TTS PCM；输入麦克风或所需输出断开时会安全取消。

面对面页面提供两种录音控制。**手动 PTT** 保持严格半双工：一侧按住说话时另一侧不可开始，直到翻译和播放结束。**自动交替** 点击开始后默认持续录制左耳侧；右耳用户按住右侧按钮可立即抢话，松开后立即恢复左侧录音。自动模式始终只有一侧麦克风采集，但上一 Turn 的翻译和 TTS 可与下一 Turn 录音并行；多个 TTS Turn 按创建顺序播放，避免中英文 PCM 交错。点击“停止连续录音”只停止采集和自动重启，已说完的后台 Turn 仍会完成翻译与播放；离页、设备断开或全局错误则取消全部后台 Turn。

当前固定语言为中文 ↔ English；说话者耳静音，译文目标为对方耳。使用 tagged 构建时，Agent 发送官方 protobuf `StartSession`、`TaskRequest`、`FinishSession`，等待 `SessionStarted` 后才向 Browser 报 `ready`，并将字幕映射成 JSON、将 TTS PCM 作为二进制 WebSocket 消息发送。火山的 `TTSSentenceStart` / `TTSSentenceEnd` 只在 Agent 内部消费，不进入 Browser 协议；零 TTS 和多分句 TTS 均由同一套 `ready → PCM* → finished` 生命周期处理。

## `AST_CODEC_UNAVAILABLE` 的含义

这不是凭据泄露、麦克风权限、TWS 连接或 Vite 代理错误。它仅表示当前运行的是**默认无 tag 构建**：`ast.NewConfiguredClient` 在 `!officialast` 下返回安全的 `UnavailableClient`，使默认测试和构建不需要本地官方生成代码，也不会错误声明服务已就绪。

需要真实翻译时，先执行 `make prepare-official-ast`，再使用 `-tags officialast` 构建或运行。真实客户端通过 `github.com/coder/websocket` 的自定义 `HTTPHeader` 发送新版 API Key 或旧版 App ID/Access Token，并保留握手响应的 `X-Tt-Logid` 用于事件和错误排查；日志与浏览器事件都不会包含凭据。

## 测试与本地 Smoke

执行完整检查：

```bash
make test
```

顺序包括：

1. Web：`test`、`typecheck`、`lint`、`build`；
2. Agent 默认安全构建：`go test ./...`、`go vet ./...`、`go build ./cmd/translator-agent`；
3. 自动准备官方生成代码，并执行真实 AST tagged `test`、`vet`、`build`。

真实 AST tagged 构建也可单独执行：

```bash
make officialast-check
```

它会先准备 Git 忽略的官方 protobuf，再运行 tagged `test`、`vet`、`build`。`google.golang.org/protobuf` 固定为 `v1.34.2`。

执行本机集成 smoke：

```bash
make smoke-local
# 或 ./scripts/smoke-local.sh
```

它会静默加载环境、构建并临时启动 Agent、检查 health、验证允许的 Vite Origin 与被拒绝的未知 Origin、构建 Web，并用临时静态 HTTP 服务检查入口 HTML；退出或中断均会清理临时 Agent、HTTP 服务和临时文件。

当前默认不运行 `go test -race`：race 检测要求 Go 工具链支持的 CGO/C 编译环境，在部分交付环境（尤其是精简容器或交叉编译环境）不可用，且不是当前 `make test` 的可移植基线。若本机具备可用的 CGO/C 工具链，可另行执行：

```bash
(cd agent && go test -race ./...)
```

## 故障排查

| 现象 | 检查与处理 |
| --- | --- |
| `make: command not found` | 安装 GNU Make，或直接运行 `./scripts/dev.sh`、`./scripts/smoke-local.sh` 和 README 中的底层命令。 |
| Agent 启动即报 `INVALID_CONFIGURATION` | 运行 `./scripts/setup-volcengine-env.sh`，确保只配置 API Key 或完整的 APP ID + Access Token，不混用；不要在终端打印或提交 `.env.local`。 |
| Agent 无法绑定 / Web health 为 offline | 检查 `127.0.0.1:18765` 是否被占用；停止旧 Agent 后运行 `make dev`，在页面点击“手动检测”。 |
| 浏览器访问失败或 Vite 启动失败 | 检查 `5173` 端口及 `npm --prefix web ci` 是否完成；必要时运行 `npm --prefix web run dev` 查看 Vite 错误。 |
| 麦克风下拉为空/权限被拒 | 按 [macOS + Chrome 麦克风权限](#macos--chrome-麦克风权限) 重新授权，然后刷新设备。 |
| TWS 未显示或播放无声 | 在 macOS 蓝牙中重新连接；刷新设备并重新选择输出。没有页面内输出选择时，在系统声音设置中设为默认输出。 |
| 左右耳测试失败或声道不对 | 先确认目标 TWS 已选中、音量非零和系统声道平衡；重新选择输出并测试。若设备/浏览器不支持单独输出选择，使用系统默认输出。 |
| Local 模式显示 Agent 离线 | 运行 `make dev` 或单独启动 Agent，访问 health URL 后在页面点“手动检测”；确认页面使用 `127.0.0.1:5173` 或 `localhost:5173`。 |
| Local 模式报 `AST_CODEC_UNAVAILABLE` | 当前运行的是默认无 tag 构建；执行 `make prepare-official-ast`，再用 `-tags officialast` 构建并启动 Agent。 |
| smoke 失败但单元测试通过 | 确认 `curl`、`python3`、可用端口和 Node/Go 依赖；脚本会清理临时进程，可在修正环境后重试。 |
