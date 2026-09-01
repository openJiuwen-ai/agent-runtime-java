# controller.query

`com.openjiuwen.service.app.controller.query` 提供 Query HTTP 入口。

## 类型

| Type | Description |
| --- | --- |
| `QueryMvcController` | Servlet 栈 `/v1/query` Controller，支持 JSON 和 SSE。 |
| `QueryLegacyMvcController` | 兼容路径 `/query`，委托给 `QueryMvcController`。 |
| `QueryWebFluxController` | WebFlux `/v1/query/reactive`。 |
| `QueryIngressSupport` | Query 请求校验和错误响应辅助。 |
| `QuerySseSupport` | `QueryChunk` 到 JSON / SSE data 的序列化辅助。 |

## 路径

| Path | Type | Description |
| --- | --- | --- |
| `/v1/query` | Servlet MVC | `stream=false` 返回 JSON，`stream=true` 返回 SSE。 |
| `/query` | Servlet MVC legacy | 需要 `openjiuwen.service.query.legacy-path-enabled=true`。 |
| `/v1/query/reactive` | WebFlux | 需要 `openjiuwen.service.query.webflux.enabled=true`。 |

## 鉴权

`QueryMvcController` / `QueryWebFluxController` 方法标注 `@AuthorizedResource(resource = "query", action = "execute")`。`auth.enabled=true` 且 Authorizer 拒绝时返回 403，body 见 [安全加固](../../../../开发与扩展/安全加固.md#43-403-响应契约)。

## 调用链

```text
QueryMvcController / QueryWebFluxController
  -> QueryRequest.normalizeMessages
  -> ServeRequest.fromQueryRequest
  -> ServeOrchestrator.query / streamQuery
```

## 相关文档

- [HTTP 对话面](../../../../HTTP对话面.md)
