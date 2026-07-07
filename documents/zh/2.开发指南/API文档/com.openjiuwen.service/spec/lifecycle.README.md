# lifecycle

`com.openjiuwen.service.spec.lifecycle` 定义 Agent Service 生命周期和探针相关契约。

## 类型

| Type | Description |
| --- | --- |
| [`AgentInitHook`](lifecycle/AgentInitHook.md) | Init 阶段回调。 |
| [`AgentShutdownHook`](lifecycle/AgentShutdownHook.md) | Shutdown 阶段回调。 |
| [`AgentInterruptHandler`](lifecycle/AgentInterruptHandler.md) | 活动会话被 interrupt 时的通知回调。 |
| [`AgentLifecycleContext`](lifecycle/AgentLifecycleContext.md) | 生命周期回调上下文。 |
| [`AgentReadiness`](lifecycle/AgentReadiness.md) | `/health` 使用的进程与 Agent 就绪状态。 |
| [`AgentServiceIdentity`](lifecycle/AgentServiceIdentity.md) | Service 应用名提供者。 |
| [`InterruptReason`](lifecycle/InterruptReason.md) | interrupt 原因枚举。 |

## 生命周期顺序

```text
Spring context ready
  -> AgentLifecycleBootstrap
      -> AgentInitHook.onInit
      -> AgentHandler.start
      -> AgentReadiness.agentLoaded = true

Spring context closing
  -> AgentReadiness shutting down
  -> active stream interrupt
  -> AgentShutdownHook.onShutdown
  -> AgentHandler.stop
```
