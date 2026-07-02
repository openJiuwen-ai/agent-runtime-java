# controller.a2a

`com.openjiuwen.service.app.controller.a2a` 提供进程内 A2A Server 能力。

## 类型

| Type | Description |
| --- | --- |
| `AgentCardController` | 暴露 Agent Card well-known 路径。 |
| `A2aJsonRpcController` | 处理 A2A JSON-RPC `SendMessage`、`SendStreamingMessage`、`GetTask`。 |
| `A2AAgentExecutor` | A2A SDK `AgentExecutor` 实现，调用 `ServeOrchestrator`。 |
| `A2AProtocolAdapter` | A2A `MessageSendParams` 转 `ServeRequest`。 |
| `A2AMessageContext` | 从 A2A SDK `RequestContext` 提取消息上下文。 |
| `ChunkMapper` | `QueryChunk` 到 A2A `Part` 的转换。 |
| `RedisTaskStore` | Redis 实现的 A2A `TaskStore`。 |

## 路径

| Path | Description |
| --- | --- |
| `/.well-known/agent-card.json` | 标准 Agent Card。 |
| `/.well-known/agent.json` | 兼容 Agent Card。 |
| `/a2a/.well-known/agent-card.json` | prefixed Agent Card。 |
| `/a2a` / `/a2a/` | A2A JSON-RPC。 |

## 调用链

```text
A2A Client
  -> A2aJsonRpcController
      -> A2A SDK RequestHandler
          -> A2AAgentExecutor
              -> A2AProtocolAdapter
                  -> ServeOrchestrator
```

## 相关文档

- [A2A 开发指导](../../../../A2A/开发指导.md)
- [A2A 与平台边界](../../../../A2A/平台边界.md)
