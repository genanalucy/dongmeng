> **历史资料，非现行规范或本轮验收。** 来源：root 工作区未提交资料样本（工作区 HEAD `bec885983928973849757f5d31df38b903b116a8` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `docs/reviews/agent-sessionauth-acceptance.md`；原始 SHA-256 `b25d577c3923c21498c4a57a330170a0a82dc65f8667a880624afd41bb5ca259`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../../docs/architecture/README.md)。

# Agent Session Authentication 验收摘要

> 验收对象：`e05dca7`、`10552e3`、`5e7816d`、`367a1f2` 的最终叠加状态
> 范围：主仓 `agent/`；本摘要未提交

## 结论

通过。Agent 全量测试和静态检查未发现可证明的回归或需要修改的运行问题；实现、测试和 `agent/internal/sessionauth/README.md` 对当前鉴权契约的描述一致，因此本次未改 Agent 代码。

## 已确认行为

- `sessionauth.Verifier` 只接受 `HS256` 和 `typ=translation_session`。
- 严格验证配置的 issuer、唯一 audience、subject，以及 `user_id/session_id/install_id` 绑定。
- 要求 `exp/iat`，限制最大 token 寿命，并按配置应用时钟偏差。
- 所有 token 验证失败统一返回脱敏错误，不包含 token。
- 开发默认关闭鉴权，且关闭时不读取 JWT key/issuer/audience；旧客户端仍可连接。
- 生产显式启用时，缺失或 transport 外形错误的 token 在 upgrade 前返回 HTTP `401`。
- 非允许 Origin 在 token 解析前返回 HTTP `403`。
- 签名、claims 或首包绑定错误在 upgrade 后返回 `TRANSLATION_AUTH_INVALID`，Provider 不会启动。
- 有效 token 通过 `Sec-WebSocket-Protocol` 传递；Agent 只回显固定 `translation.v1`，不回显 credential protocol。
- `Authorization` header 不是支持的旁路。
- 配置不完整时进程在监听前失败，错误不包含 signing key。
- README 已明确生产启用、代理 header 脱敏、单 key 轮换限制和安全回滚方式。

## 验证结果

```text
go test ./...       PASS
go vet ./...        PASS
git diff --check    PASS
```

覆盖包包括 `cmd/translator-agent`、`internal/ast`、`internal/config`、`internal/server`、`internal/sessionauth`；生成 protobuf 包无测试文件。

## 非阻塞前提与剩余边界

- 生产签发方必须与 Agent 精确一致地签发 `typ=translation_session`、issuer、唯一 audience、`sub/user_id/session_id/install_id` 和时间 claims；本次未对外部签发服务做端到端验证。
- 当前只有单个 HS256 key，不支持 `kid`/双 key 无中断轮换；运维文档已要求协调切换。
- `Sec-WebSocket-Protocol` 在到达 Agent 前可能被代理/APM 看到；生产入口必须对原始 header 脱敏。
- 默认关闭只为 loopback 开发兼容，不能作为公网生产安全配置或常规回滚方案。
