# autoconfigure

`com.openjiuwen.service.adapters.agentcore.autoconfigure` 提供 Agent Core adapter 的自动装配。

## 类型

| Type | Description |
| --- | --- |
| `AgentCoreAdaptersAutoConfiguration` | 注册 external decorator factory、registrar、remote/sandbox factory、默认 Core `AgentHandler`。 |
| `MiddlewareAdaptersAutoConfiguration` | 注册 `MiddlewareAdapterRegistrar`。 |

## AgentCoreAdaptersAutoConfiguration

主要 Bean：

| Bean | 条件 | Description |
| --- | --- | --- |
| `AgentCoreMcpClientDecoratorFactory` | 缺少同类型 Bean | MCP client 装饰器工厂。 |
| `AgentCoreRemoteClientDecoratorFactory` | 缺少同类型 Bean | Remote client 装饰器工厂。 |
| `ExternalSvcAdapterRegistrar` | 缺少同类型 Bean | 向 Core 注册 MCP / Remote provider。 |
| `AgentCoreRemoteClientFactory` | 配置了 `openjiuwen.service.external.remote.clients[0].id` | 按配置创建 remote client。 |
| `AgentCoreSandboxClientFactory` | `openjiuwen.service.external.sandbox.enabled=true` | 校验 sandbox 配置，并按配置创建 sandbox client。 |
| `AgentHandler` | `openjiuwen.service.agent-id` 非空且 `handler=agentcore` | 默认 `JiuwenCoreAgentHandler`。 |

## MiddlewareAdaptersAutoConfiguration

注册 `DefaultMiddlewareAdapterRegistrar`，将 `openjiuwen.service.middleware` 转换为 Core Runner 配置。
