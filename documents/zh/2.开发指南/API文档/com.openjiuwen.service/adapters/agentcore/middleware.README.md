# middleware

`com.openjiuwen.service.adapters.agentcore.middleware` 把 Service middleware 配置转换为 Core Runner 配置。

## 类型

| Type | Description |
| --- | --- |
| `MiddlewareAdapterRegistrar` | middleware 注册 SPI。 |
| `DefaultMiddlewareAdapterRegistrar` | 默认实现，应用 checkpointer 配置。 |
| `AgentCoreCheckpointerConfigAssembler` | 根据 `MiddlewareProperties` 组装 Core checkpointer 配置。 |

## 支持的 checkpointer

| Type | Description |
| --- | --- |
| `in_memory` | 默认内存 checkpointer。 |
| `redis` | 使用 `middleware.redis.<redis-ref>` 生成 Redis checkpointer 配置。 |

## 使用位置

`JiuwenCoreAgentHandler.start()` 在 `Runner.start()` 前调用 `MiddlewareAdapterRegistrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig())`。
