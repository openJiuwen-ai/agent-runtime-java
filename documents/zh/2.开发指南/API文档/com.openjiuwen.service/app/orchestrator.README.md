# orchestrator

`com.openjiuwen.service.app.orchestrator` 提供 `ServeOrchestrator` 的默认实现。

## 类型

| Type | Description |
| --- | --- |
| `DefaultServeOrchestrator` | 默认 Query 编排器，负责活动流登记、取消和会话 reset。 |
| `A2AEnabledServeOrchestrator` | A2A 增强编排器，支持 `a2a_delegate`、远端 INPUT_REQUIRED shadow task 和 resume。 |

## DefaultServeOrchestrator

```text
query
  -> AgentHandler.query

streamQuery
  -> ActiveStreamRegistry.register
  -> AgentHandler.streamQuery
  -> ActiveStreamRegistry.unregister

resetConversation
  -> cancelActive
  -> AgentHandler.clearSession
```

## A2AEnabledServeOrchestrator

在默认编排能力之上增加：

| 能力 | Description |
| --- | --- |
| `tryResumePending` | 检查本地 shadow task，恢复远端 Agent 调用。 |
| `runAgentAndCaptureInterrupt` | 执行本地 Agent 并捕获 interrupt chunk。 |
| `handleInterrupt` | 处理 `a2a_delegate` 或 `ask_user` 等中断。 |
| `delegateSync` / `delegateSse` | 按模式调用远端 Agent。 |

## 相关文档

- [A2A 开发指导](../../../A2A开发指导.md)
