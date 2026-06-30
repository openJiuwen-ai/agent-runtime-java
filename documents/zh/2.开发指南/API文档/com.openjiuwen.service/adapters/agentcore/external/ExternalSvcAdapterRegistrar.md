# com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar

## interface ExternalSvcAdapterRegistrar

```java
public interface ExternalSvcAdapterRegistrar
```

外部服务适配注册 SPI，用于把 Service 配置接到 Agent Core 的 MCP / Remote 扩展点。

## 方法

| Signature | Description |
| --- | --- |
| `void registerTo(RunnerConfig runnerConfig)` | 将外部服务配置合并到指定 RunnerConfig。 |
| `void registerToRunner()` | 直接注册到 Core 全局 Runner 和相关 factory。 |
| `static ExternalSvcAdapterRegistrar noop()` | 返回无操作实现。 |

## 默认实现

`DefaultExternalSvcAdapterRegistrar` 会：

1. 注册 MCP client provider：`sse`、`stdio`、`streamable_http`。
2. 注册 A2A `RemoteClientProvider`。
3. 将 `mcp.servers[]` 转为 Core `McpServerConfig` 并注册到 `Runner.resourceMgr()`。
