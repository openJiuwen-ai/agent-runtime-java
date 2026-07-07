# com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties

## class AgentCoreExternalProperties

```java
@ConfigurationProperties(prefix = "openjiuwen.service.external")
public class AgentCoreExternalProperties
```

Agent Core external adapters 的配置绑定对象。

## 顶层配置

| Property | Type | Description |
| --- | --- | --- |
| `mcp` | `McpPolicy` | MCP server 列表和治理策略。 |
| `remote` | `RemotePolicy` | A2A Remote client 列表和治理策略。 |
| `sandbox` | `SandboxPolicy` | Sandbox server 列表和治理策略。 |

## MCP

| Property | Default | Description |
| --- | --- | --- |
| `mcp.timeout-ms` | `30000` | MCP 调用默认超时。 |
| `mcp.retry-tool-calls` | `false` | 是否重试 `tools/call`。 |
| `mcp.servers[].server-id` | `null` | server 标识。 |
| `mcp.servers[].server-name` | required | server 名称。 |
| `mcp.servers[].server-path` | required | MCP server 地址。 |
| `mcp.servers[].client-type` | `sse` | `sse`、`stdio`、`streamable_http`。 |

## Remote

| Property | Default | Description |
| --- | --- | --- |
| `remote.timeout-ms` | `30000` | remote 调用默认超时。 |
| `remote.retry-invoke` | `false` | 是否重试 `invoke`。 |
| `remote.clients[].id` | required | client 标识。 |
| `remote.clients[].protocol` | `A2A` | 当前只支持 `A2A`。 |
| `remote.clients[].url` | required | A2A remote URL，必须是 HTTP(S)。 |

## Sandbox

| Property | Default | Description |
| --- | --- | --- |
| `sandbox.enabled` | `false` | 是否启用 sandbox factory。 |
| `sandbox.timeout-ms` | `30000` | sandbox 调用默认超时。 |
| `sandbox.servers[].server-id` | required when enabled | sandbox server 标识。 |
| `sandbox.servers[].service-url` | required when enabled | sandbox 服务 URL，必须是 HTTP(S)。 |
| `sandbox.servers[].sandbox-type` | `jiuwenbox` | sandbox 类型。 |
| `sandbox.servers[].launcher-type` | `pre_deploy` | launcher 类型。 |
| `sandbox.servers[].on-stop` | `delete` | 停止策略。 |
| `sandbox.servers[].root-path` | `.` | 写入 Core gateway `params.root_path`，除非 `params.root_path` 已设置。 |
| `sandbox.servers[].isolation-key` | `null` | 写入 Core `SandboxIsolationConfig.customId`。 |
| `sandbox.servers[].isolation-prefix` | `null` | 写入 Core `SandboxIsolationConfig.prefix`。 |
| `sandbox.servers[].container-scope` | `SESSION` | 写入 Core `SandboxIsolationConfig.containerScope`。 |
| `sandbox.servers[].timeout-ms` | `null` | server 级超时，覆盖 `sandbox.timeout-ms`。 |
| `sandbox.servers[].idle-ttl-seconds` | `null` | 写入 Core `SandboxLauncherConfig.idleTtlSeconds`。 |
| `sandbox.servers[].params` | `{}` | 写入 Core `SandboxGatewayConfig.params`。 |
| `sandbox.servers[].extra-params` | `{}` | 写入 Core `SandboxLauncherConfig.extraParams`。 |

## Sandbox 映射规则

`DefaultAgentCoreSandboxClientFactory` 会将 `SandboxServer` 映射为 Core `SandboxGatewayConfig`：

| Service 配置 | Core 配置 |
| --- | --- |
| `service-url` | `SandboxGatewayConfig.gatewayUrl`、`SandboxLauncherConfig.gatewayUrl`、`SandboxLauncherConfig.baseUrl` |
| `timeout-ms` | `SandboxGatewayConfig.timeoutSeconds`，按毫秒向上取整为秒，最小 1 秒 |
| `sandbox-type` | `SandboxLauncherConfig.sandboxType`，由 agent-core-java 对应 provider 消费 |
| `launcher-type` | `SandboxLauncherConfig.launcherType` |
| `on-stop` | `SandboxLauncherConfig.onStop` |
| `idle-ttl-seconds` | `SandboxLauncherConfig.idleTtlSeconds` |
| `params` + `root-path` | `SandboxGatewayConfig.params` |
| `extra-params` | `SandboxLauncherConfig.extraParams` |
| `isolation-key` / `isolation-prefix` / `container-scope` | `SandboxIsolationConfig` |

factory 构造时会校验 `servers[]`。`create()` / `create(serverId)` 按配置创建 Core `SandboxClient`，再包装为 `DecoratingSandboxClient`。

runtime 不再注册 sandbox HTTP provider，也不定义 sandbox HTTP 调用路径。`fs`、`shell`、`code` 的实际协议由 agent-core-java 中对应 `sandbox-type` 的 provider 决定。

## 方法

| Signature | Description |
| --- | --- |
| `public McpPolicy policyFor(McpServerConfig config)` | 计算某个 MCP server 的有效治理策略。 |
| `public RemotePolicy policyFor(RemoteClientConfig config)` | 计算某个 remote client 的有效治理策略。 |
| `public SandboxPolicy policyFor(Optional<SandboxServer> server)` | 计算某个 sandbox server 的有效治理策略。 |
