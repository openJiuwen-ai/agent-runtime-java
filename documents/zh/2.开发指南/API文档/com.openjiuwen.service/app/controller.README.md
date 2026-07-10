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

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/controller/`
