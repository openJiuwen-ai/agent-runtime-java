# dto

`com.openjiuwen.service.spec.dto` 定义 HTTP 入站、编排层和流式输出使用的数据对象。

## 类型

| Type | Description |
| --- | --- |
| [`QueryRequest`](dto/QueryRequest.md) | 外部 Query API 请求体，支持 `message` 简写和 `messages` 数组。 |
| [`ServeRequest`](dto/ServeRequest.md) | Controller / A2A adapter 转给 `ServeOrchestrator` 的协议无关请求。 |
| [`QueryResponse`](dto/QueryResponse.md) | 非流式 Query 响应，`result` 承载聚合 assistant 输出。 |
| [`QueryChunk`](dto/QueryChunk.md) | 流式输出 chunk，包含 `type` 和 `data`。 |
| [`HealthResponse`](dto/HealthResponse.md) | `/health` 响应体。 |
| [`ResetConversationRequest`](dto/ResetConversationRequest.md) | reset conversation 请求体。 |
| [`ResetConversationResponse`](dto/ResetConversationResponse.md) | reset conversation 响应体。 |

## 字段约定

- JSON 字段使用 snake_case，例如 `conversation_id`、`process_up`、`agent_loaded`。
- `QueryRequest.message` 是兼容简写；进入编排前会归一化为 `messages`。
- `ServeRequest.metadata` 用于协议元数据透传，例如 A2A `params.metadata`。

## 源码路径

`service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/dto/`
