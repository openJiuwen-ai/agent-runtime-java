# autoconfigure

`com.openjiuwen.service.app.autoconfigure` 提供 `agent-service-app` 的 Spring Boot 自动装配。

## 类型

| Type | Description |
| --- | --- |
| `AgentServiceAutoConfiguration` | 注册基础 Service Bean、Lifecycle、Readiness、Controller component scan。 |
| `LlmAutoConfiguration` | 注册 `LlmProperties` 和 `LlmConfigResolver`。 |
| `A2AAutoConfiguration` | 注册 A2A SDK 组件、AgentCard、JSON-RPC、TaskStore、远端 Agent 发现和增强 Orchestrator。 |

## AgentServiceAutoConfiguration

```java
@AutoConfiguration
@EnableConfigurationProperties({ServiceProperties.class, QueryProperties.class, LifecycleProperties.class})
@ComponentScan(basePackages = "com.openjiuwen.service.app.controller")
public class AgentServiceAutoConfiguration
```

主要 Bean：

| Bean | 条件 | 说明 |
| --- | --- | --- |
| `AgentHandlerHolder` | 缺少 `AgentHandler` 且 `openjiuwen.service.agent-id` 为空 | 占位 Handler，可后续注入真实 Handler。 |
| `AgentServiceIdentity` | 缺少同类型 Bean | 默认读取 `spring.application.name`。 |
| `DefaultAgentReadiness` / `AgentReadiness` | 缺少同类型 Bean | `/health` 使用的就绪状态。 |
| `AgentLifecycleHooks` | 缺少同类型 Bean | 聚合 init / shutdown / interrupt hooks。 |
| `InitPhaseExecutor` | 缺少同类型 Bean | 执行 init 阶段。 |
| `ShutdownPhaseExecutor` | 缺少同类型 Bean | 执行 shutdown 阶段。 |
| `ActiveStreamInterruptor` | 缺少同类型 Bean | 统一 interrupt 活动流。 |
| `DefaultAgentLifecycleManager` | 缺少 `AgentLifecycleManager` | 生命周期门面。 |
| `AgentLifecycleBootstrap` | 缺少同类型 Bean | Spring 启停时驱动 lifecycle。 |

## A2AAutoConfiguration

```java
@AutoConfiguration(after = AgentServiceAutoConfiguration.class)
@ConditionalOnClass(AgentExecutor.class)
@EnableConfigurationProperties(A2AProperties.class)
public class A2AAutoConfiguration
```

主要 Bean：

| Bean | 说明 |
| --- | --- |
| `MainEventBus` | A2A SDK 事件总线。 |
| `TaskStore` | 默认 `InMemoryTaskStore`，配置 Redis checkpointer 时使用 `RedisTaskStore`。 |
| `QueueManager` / `MainEventBusProcessor` | A2A SDK task event 处理。 |
| `A2AProtocolAdapter` | A2A message 转 `ServeRequest`。 |
| `A2AAgentExecutor` | A2A SDK 调用 Service Orchestrator 的执行器。 |
| `A2ARemoteAgentCardRegistry` | 远端 AgentCard 注册表。 |
| `A2ARemoteAgentClient` | 远端 A2A Agent 调用客户端。 |
| `A2AAgentCardDiscovery` | 启动时发现 `remote-agents`。 |
| `A2AEnabledServeOrchestrator` | 默认 `ServeOrchestrator`，支持 A2A delegate / resume。 |
| `RequestHandler` | A2A SDK JSON-RPC request handler。 |

## 源码路径

`service/agent-service-app/src/main/java/com/openjiuwen/service/app/autoconfigure/`
