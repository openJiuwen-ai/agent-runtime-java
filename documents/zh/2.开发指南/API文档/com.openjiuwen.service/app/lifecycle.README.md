# lifecycle

`com.openjiuwen.service.app.lifecycle` 是 Agent Service 生命周期实现包。

## 类型

| Type | Description |
| --- | --- |
| `AgentLifecycleBootstrap` | Spring 生命周期入口，驱动 init / shutdown。 |
| `AgentLifecycleManager` | 生命周期门面接口。 |
| `DefaultAgentLifecycleManager` | 默认生命周期实现。 |
| `InitPhaseExecutor` | 执行 init hooks 和 `AgentHandler.start()`。 |
| `ShutdownPhaseExecutor` | interrupt active streams、执行 shutdown hooks 和 `AgentHandler.stop()`。 |
| `AgentLifecycleHooks` | 聚合并排序 lifecycle hook。 |
| `DefaultAgentReadiness` | `AgentReadiness` 默认状态实现。 |
| `ActiveStreamRegistry` | 活动流登记表。 |
| `ActiveStreamInterruptor` | 批量 interrupt 活动流。 |
| `StreamCancellationHandle` | 单个活动流取消句柄。 |
| `AgentHandlerHolder` | 缺少 Handler 时的可延迟注入占位实现。 |

## Init 阶段

```text
AgentLifecycleBootstrap
  -> DefaultAgentLifecycleManager.runInitPhase
      -> InitPhaseExecutor
          -> AgentInitHook.onInit
          -> AgentHandler.start
          -> DefaultAgentReadiness.markAgentLoaded(true)
```

## Shutdown 阶段

```text
DefaultAgentLifecycleManager.runShutdownPhase
  -> DefaultAgentReadiness.markShuttingDown
  -> ActiveStreamInterruptor.interruptAll
  -> AgentShutdownHook.onShutdown
  -> AgentHandler.stop
```

## 相关文档

- [生命周期与探针](../../../生命周期与探针.md)
