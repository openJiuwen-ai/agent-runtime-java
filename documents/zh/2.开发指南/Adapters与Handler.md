# Adapters 与 Handler

**Adapters** 将 HTTP / A2A 编排层接到 **执行后端**，并在 Handler 启动时把 **中间件** 与 **外部服务 egress** 注册进运行时（默认路径为 Agent Core `Runner`）。

## 职责分层

```text
agent-service-adapters/
├── agent-service-adapters-common     # 引擎无关：中间件客户端、凭证、外部调用 DFX
└── agent-service-adapters-agentcore  # Agent Core leaf：Handler + 注册进 Runner
```

| 包域 | 模块 | 职责 |
| --- | --- | --- |
| `adapters.common.middleware` | common | Redis 连接、`MiddlewareProperties`、Checkpointer 配置组装 |
| `adapters.common.external` | common | 超时、重试、熔断、审计（`ExternalCallExecutor`） |
| `adapters.common.credential` | common | 凭证解密（默认 `PassthroughCredentialDecryptor`） |
| `adapters.agentcore.agentfw` | agentcore | `JiuwenCoreAgentHandler` |
| `adapters.agentcore.middleware` | agentcore | `MiddlewareAdapterRegistrar` → `RunnerConfig` |
| `adapters.agentcore.external` | agentcore | MCP / Remote / Sandbox → Core 出站 SPI |

`JiuwenCoreAgentHandler.start()` 时依次调用 middleware 与 external 注册器，再执行 `Runner.start()`。

## SPI：AgentHandler

```java
public interface AgentHandler {
    QueryResponse query(ServeRequest request);
    void streamQuery(ServeRequest request, QueryStreamObserver observer);
    default void start() { }
    default void stop() { }
    default void clearSession(String conversationId) { }
}
```

`ServeRequest` 由 HTTP / A2A 层从 `QueryRequest` 等转换，包含 `conversationId`、`messages`、租户字段等。

## Leaf 模块与选型

| 模块 | 角色 | 选型条件 |
| --- | --- | --- |
| `agent-service-adapters-common` | 共享中间件 / 外部 DFX | 被 agentcore leaf 依赖；不单独提供 Handler |
| `agent-service-adapters-agentcore` | 默认 `JiuwenCoreAgentHandler` | `handler=agentcore`（默认）且配置 `agent-id`，且无自定义 `@Bean AgentHandler` |

`versatile` 等其它引擎 Handler **不在本仓库 adapters 内**；人机中断、远端低码等由业务镜像 `@Bean AgentHandler` 或外部 adapter 模块提供。

### Agent Core（默认高码）

```yaml
openjiuwen:
  service:
    handler: agentcore
    agent-id: my-workflow-agent
```

`JiuwenCoreAgentHandler` 通过 `agent-id` 从 `Runner.resourceMgr()` 加载 Agent，调用 `Runner.query` / `stream`。

## 中间件（`openjiuwen.service.middleware`）

前缀：`openjiuwen.service.middleware`（`MiddlewareProperties`）。

### Checkpointer

| 属性 | 说明 |
| --- | --- |
| `checkpointer.type` | `in_memory`（默认）或 `redis` |
| `checkpointer.redis-ref` | 引用 `middleware.redis.<name>` 中的端点名 |

`type=redis` 时，`DefaultMiddlewareAdapterRegistrar` 将 Redis 连接写入 Core `RunnerConfig` 的 Checkpointer 配置。

示例（内存）：

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: in_memory
```

示例（Redis）：

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: default
      redis:
        default:
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: ""   # 经 CredentialDecryptor 解密；空表示无密码
```

Demo：`example/redis/application-redis-checkpointer.yml`，独立模块 `agent-service-demo-redis`。

### 其它中间件占位

`session-store`、`object-storage`、`vector-store` 当前为 **P2 占位**（`type: none`），结构预留，尚未接入运行时。

### 凭证

Redis 密码等敏感字段使用 `encrypted-password`；默认 `PassthroughCredentialDecryptor`（明文透传）。生产环境可 `@Bean CredentialDecryptor` 覆盖。

## 外部服务 egress（`openjiuwen.service.external`）

前缀：`openjiuwen.service.external`（`AgentCoreExternalProperties`）。

`DefaultExternalSvcAdapterRegistrar` 在 Handler `start()` 时将配置注册到 Core 的 MCP、Remote、Sandbox 扩展点，并由 `adapters-common` 的 `ExternalCallExecutor` 统一施加超时、重试、熔断与审计。

三类出站能力：

| 子树 | Core 集成 | 典型用途 |
| --- | --- | --- |
| `external.mcp` | `McpClient` 装饰工厂 | MCP Tool 调用 |
| `external.remote` | `RemoteClient` 装饰工厂 | 出站 A2A / 远端 Agent（`protocol: A2A`） |
| `external.sandbox` | `SandboxClient` 工厂 | 沙箱执行（代码 / 文件 / Shell） |

各子树均支持 `timeout-ms`、`retry`、`circuit-breaker`、`audit`（见 `ExternalCallPolicy`）。

### MCP 示例（节选）

```yaml
openjiuwen:
  service:
    external:
      mcp:
        timeout-ms: 30000
        servers:
          - server-id: demo-mcp
            server-name: demo-mcp-tools
            server-path: http://localhost:18080/mcp
            client-type: streamable-http
```

Demo：`example/mcp/application-mcp.yml`、独立模块 `agent-service-demo-mcp`。

### Remote（出站 A2A）示例（节选）

```yaml
openjiuwen:
  service:
    external:
      remote:
        clients:
          - id: demo-a2a-remote
            name: Demo A2A Remote
            protocol: A2A
            url: http://127.0.0.1:18082
        timeout-ms: 3000
```

与 **进程内 A2A Server**（Ingress JSON-RPC）不同：此处是 Agent 作为 Client 调用远端 Agent。Demo 配置见 `agent-service-demo/src/test/resources/application-a2a-remote.yml`；本地 mock 与验收见 `src/test/`（`RemoteExampleLocalServerTest`）。

### Sandbox 示例（节选）

```yaml
openjiuwen:
  service:
    external:
      sandbox:
        enabled: true
        servers:
          - server-id: default
            service-url: http://localhost:18090
            sandbox-type: jiuwenbox
```

Demo：`example/sandbox/application-sandbox.yml`、独立模块 `agent-service-demo-sandbox`。

## 自动装配规则

- `agent-service-adapters-agentcore`：`@ConditionalOnMissingBean(AgentHandler.class)` — 已有 `@Bean AgentHandler` 时不装配默认 Handler。
- AutoConfiguration 注册在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- `agent-service-app` **主代码**不硬依赖 `adapters-agentcore`；由 demo 或产品镜像引入具体 adapter leaf。

## 选型建议

| 场景 | 推荐方式 |
| --- | --- |
| 高码 Workflow / LlmAgent | `agentcore` + `agent-id` |
| Redis 会话 / 图状态持久化 | `middleware.checkpointer.type=redis` + `middleware.redis` |
| MCP / 远端 Agent / 沙箱 | `external.mcp` / `external.remote` / `external.sandbox` |
| 人机中断 / 远端低码 / 其他引擎 | 自定义 `@Bean AgentHandler` 或外部 adapter 模块 |

## 自定义 Handler 模板

业务镜像可提供 `@Bean AgentHandler` 覆盖默认装配。最小实现见 `agent-service-demo` 的 `EchoProxyAgentHandler`：

```java
@Bean
AgentHandler agentHandler() {
    return new EchoProxyAgentHandler("my-prefix:");
}
```

要点：

- 实现 `query` / `streamQuery`；入参 `ServeRequest`，出参 `QueryResponse` / `QueryChunk`。
- 生命周期 `start` / `stop` / `clearSession` 按需覆写；**非 Core 后端勿调用 `Runner.start()`**。
- 若仍走 Core 但需自定义装配，可参考 `JiuwenCoreAgentHandler` 注入 `MiddlewareAdapterRegistrar` / `ExternalSvcAdapterRegistrar`。

同模块还有 `DemoAgentHandler`（Mock）、`example/` 下 MCP / Remote / Sandbox 样例。

## 延伸阅读

- [架构概述 · Adapters 聚合结构](架构概述.md#6-adapters-聚合结构)
- [HTTP 对话面](HTTP对话面.md) — Ingress 契约
- [A2A 开发指导](A2A/开发指导.md) — 进程内 A2A Server 与 Orchestrator 远端委派
- Core Runner：[agent-core-java · 执行器 Runner](https://gitcode.com/openJiuwen/agent-core-java)（仓内 `documents/zh/2.开发指南/高阶用法/`，版本见 [Agent Core 依赖](Agent Core 依赖.md)）
- Demo 总览：[agent-service-demo/README.md](../../service/agent-service-demo/README.md)
