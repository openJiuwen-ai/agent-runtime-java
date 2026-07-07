# controller.a2a.client

`com.openjiuwen.service.app.controller.a2a.client` 提供进程内 A2A 的远端 Agent 发现和调用辅助。

## 类型

| Type | Description |
| --- | --- |
| `A2AAgentCardDiscovery` | 按 `openjiuwen.service.a2a.remote-agents` 拉取远端 Agent Card，失败后定时重试。 |
| `A2ARemoteAgentCardRegistry` | 保存远端 AgentCard、URL 和 timeout。 |
| `A2ARemoteAgentClient` | 调用远端 Agent 的 sync / streaming client。 |
| `RemoteInputRequiredException` | 远端 Agent 返回 `INPUT_REQUIRED` 时抛出。 |
| `RemoteAgentException` | 远端调用异常封装。 |

## 调用模式

| Method | Description |
| --- | --- |
| `callSync(...)` | 阻塞调用远端 Agent，返回最终文本。 |
| `callStreaming(...)` | SSE 调用远端 Agent，中间事件可透传给本地 stream observer。 |

## 使用位置

- `A2AEnabledServeOrchestrator`
- A2A delegate / shadow task resume 流程
