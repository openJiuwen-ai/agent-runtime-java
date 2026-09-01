# controller

`com.openjiuwen.service.app.controller` 是 Agent Service 的 HTTP / A2A 入站层。

## 子包

| Package | Description |
| --- | --- |
| [`query`](controller/query.README.md) | `/v1/query`、`/query`、`/v1/query/reactive`。 |
| [`reset`](controller/reset.README.md) | `/v1/reset_conversation`、`/reset_conversation`。 |
| [`probe`](controller/probe.README.md) | `/health`。 |
| [`a2a`](controller/a2a.README.md) | Agent Card、A2A JSON-RPC、A2A protocol adapter。 |
| [`a2a.client`](controller/a2a/client.README.md) | 远端 AgentCard 发现和 A2A remote client。 |

## 设计原则

- Controller 只负责协议解析、校验和响应格式。
- 业务执行必须经 `ServeOrchestrator`。
- Controller 不直接调用 Core `Runner`。
- 启用 `openjiuwen.service.security.auth.enabled=true` 时，标注 `@AuthorizedResource` 的端点经 `FineGrainedAuthorizer` 鉴权；拒绝返回 **403**（`code=ACCESS_DENIED`）。`/health` 不受鉴权影响。

## 鉴权 resource / action 一览

| 子包 | 端点 | resource | action |
| --- | --- | --- | --- |
| `query` | Query REST | `query` | `execute` |
| `reset` | reset conversation | `session` | `reset` |
| `a2a` | JSON-RPC / Agent Card / Push Callback | `a2a` / `agent-card` / `a2a-push-callback` | `rpc` / `read` / `receive` |
| `probe` | `/health` | — | 无鉴权 |

详见 [安全加固](../../../开发与扩展/安全加固.md)。

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/`
