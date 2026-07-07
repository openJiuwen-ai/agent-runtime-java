# external

`com.openjiuwen.service.adapters.agentcore.external` 将 Service 外部服务配置接入 Agent Core 的 MCP、RemoteClient 和 Sandbox 扩展点。

## 类型

| Type | Description |
| --- | --- |
| [`AgentCoreExternalProperties`](external/AgentCoreExternalProperties.md) | `openjiuwen.service.external` 配置绑定对象。 |
| [`ExternalSvcAdapterRegistrar`](external/ExternalSvcAdapterRegistrar.md) | 向 Core 注册 MCP 和 Remote provider 的 SPI。 |
| `DefaultExternalSvcAdapterRegistrar` | 默认 registrar，注册 MCP provider 和 A2A remote provider。 |
| `AgentCoreMcpClientDecoratorFactory` | MCP client 装饰器工厂。 |
| `AgentCoreRemoteClientDecoratorFactory` | Remote client 装饰器工厂。 |
| `AgentCoreRemoteClientFactory` | 按 `remote.clients[].id` 创建 Core `RemoteClient`。 |
| `AgentCoreSandboxClientFactory` | 按 sandbox server id 创建 Core `SandboxClient`。 |
| `DecoratingMcpClient` | MCP 出站治理装饰器。 |
| `DecoratingRemoteClient` | A2A Remote 出站治理装饰器。 |
| `DecoratingSandboxClient` | Sandbox 出站治理装饰器。 |
| `DefaultAgentCoreSandboxClientFactory` | 校验 sandbox 配置，创建 Sandbox gateway / launcher / isolation config 和 client。 |

## 注册链路

```text
JiuwenCoreAgentHandler.start
  -> ExternalSvcAdapterRegistrar.registerToRunner
      -> McpClientFactory.register(...)
      -> RemoteClientFactory.register("A2A", ...)
  -> Runner.start
```

Sandbox 不通过 `registerToRunner()` 创建 client。启用链路是：

```text
AgentCoreAdaptersAutoConfiguration
  -> AgentCoreSandboxClientFactory bean
      -> DefaultAgentCoreSandboxClientFactory.validate

业务代码
  -> AgentCoreSandboxClientFactory.create(serverId)
      -> SandboxGatewayConfig / SandboxLauncherConfig / SandboxIsolationConfig
      -> DecoratingSandboxClient
```

runtime 不再提供 sandbox HTTP provider。`service-url`、`sandbox-type`、`params` 和 `extra-params` 会被写入 Core `SandboxGatewayConfig` / `SandboxLauncherConfig`，实际后端协议由 agent-core-java provider 负责。

## 相关文档

- [外部服务](../../../../开发与扩展/外部服务.md)
