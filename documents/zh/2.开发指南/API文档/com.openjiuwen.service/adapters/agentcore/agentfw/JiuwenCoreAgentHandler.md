# com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler

## class JiuwenCoreAgentHandler

```java
public class JiuwenCoreAgentHandler implements AgentHandler
```

默认 Agent Core Handler。它把 Service 层的 `ServeRequest` 转成 Core Runner 输入，并将 Core 输出归一化为 `QueryResponse` / `QueryChunk`。

## 构造方法

| Signature | Description |
| --- | --- |
| `public JiuwenCoreAgentHandler(Object agent)` | 使用指定 Agent 对象。 |
| `public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar registrar)` | 启动时先应用 middleware 配置。 |
| `public JiuwenCoreAgentHandler(Object agent, ExternalSvcAdapterRegistrar registrar)` | 启动时注册 external service adapters。 |
| `public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middleware, ExternalSvcAdapterRegistrar external)` | 完整构造。 |

`agent` 可以是实际 Agent 对象，也可以是 `agent-id` 字符串；字符串场景下 Handler 会通过 Core `ResourceMgr` 查找。

## 方法

| Signature | Description |
| --- | --- |
| `public void start()` | 注册 middleware/external adapters 并启动 Core `Runner`。 |
| `public void stop()` | 停止 Core `Runner`。 |
| `public void clearSession(String conversationId)` | 调用 `Runner.release(conversationId)`。 |
| `public QueryResponse query(ServeRequest request)` | 非流式执行，并聚合 assistant 内容。 |
| `public void streamQuery(ServeRequest request, QueryStreamObserver observer)` | 流式执行，输出 `QueryChunk`。 |

## 输出映射

- Core 普通输出会被映射为 `chunk` 或 `answer`。
- Core interrupt 输出会被映射为 `interrupt`。
- 异常会被映射为 `error` chunk，并调用 `observer.onError`。

## 相关文档

- [Adapters 与 Handler](../../../../../Adapters与Handler.md)
