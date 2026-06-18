# Adapters 与 Handler

**Adapters** 实现 `com.openjiuwen.service.spec.spi.AgentHandler`，将 HTTP 编排层接到具体执行后端。

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

`ServeRequest` 由 HTTP 层从 `QueryRequest` 转换，包含 `conversationId`、`messages`、租户字段等。

## Leaf 模块

| 模块 | Handler | 选型条件 |
| --- | --- | --- |
| `agent-service-adapters-agentcore` | `JiuwenCoreAgentHandler` | `openjiuwen.service.agent-id` 非空且 `handler=agentcore`（默认） |
| `agent-service-adapters-agentcore-ext` | `JiuwenCoreExtAgentHandler` | `agent-id` 非空且 `handler=agentcore-ext` |
| `agent-service-adapters-versatile` | `VersatileAgentHandler` | `handler=versatile` 且 `versatile.base-url` 非空 |
| `agent-service-adapters-common` | 共享 middleware / external 占位 | 被其他 leaf 依赖 |

## 配置示例

### Agent Core（默认高码）

```yaml
openjiuwen:
  service:
    handler: agentcore
    agent-id: my-workflow-agent
```

`JiuwenCoreAgentHandler` 通过 `agent-id` 从 `Runner.resourceMgr()` 加载 Agent 并调用 `Runner.query` / `stream`。

### Agent Core Ext（中断 / 挂起）

```yaml
openjiuwen:
  service:
    handler: agentcore-ext
    agent-id: my-ext-agent
```

扩展 Handler 继承 Core 能力，并映射 Core 中断事件到 Service 层可观测语义（高码挂起/恢复场景）。

### Versatile（远端低码）

```yaml
openjiuwen:
  service:
    handler: versatile
    versatile:
      base-url: https://versatile.example.com
      query-path: /v1/query          # 可选，默认 /v1/query
      stream-path: /v1/query/stream  # 可选
```

HTTP 出站由 `VersatileHttpClient` 完成，**不**经过 Core Runner。

## 自动装配规则

- 各 leaf 使用 `@ConditionalOnMissingBean(AgentHandler.class)`：已有 `@Bean AgentHandler` 时不装配默认 Handler。
- `agentcore` 与 `agentcore-ext`、`versatile` 通过 `openjiuwen.service.handler` 互斥。
- AutoConfiguration 注册在各自模块的 `META-INF/spring/...AutoConfiguration.imports`。

## 包结构约定

每个 adapter leaf 使用 **独立根包**，例如：

- `com.openjiuwen.service.adapters.agentcore.agentfw`
- `com.openjiuwen.service.adapters.agentcore.ext.agentfw`
- `com.openjiuwen.service.adapters.versatile.external`

子包含 `agentfw`、`middleware`、`external`、`autoconfigure`，与架构 **Ingress/Egress** 划分一致。

## 选型建议

| 场景 | 推荐 Handler |
| --- | --- |
| 高码 Workflow / LlmAgent | `agentcore` |
| 高码 + 人机中断 / INPUT_REQUIRED | `agentcore-ext` |
| 镜像只做壳，执行在 Versatile | `versatile` |
| 完全自定义运行时 | 自定义 `@Bean AgentHandler` |

## 自定义 Handler 模板

第三方可在业务镜像中提供 `@Bean AgentHandler`，覆盖 adapter 默认装配。最小实现见
`agent-service-demo` 模块中的 `EchoProxyAgentHandler`（`com.openjiuwen.service.demo.examples`）：

```java
@Bean
AgentHandler agentHandler() {
    return new EchoProxyAgentHandler("my-prefix:");
}
```

要点：

- 实现 `query` / `streamQuery`，入参为 `ServeRequest`，出参为 `QueryResponse` / `QueryChunk`。
- 生命周期 `start` / `stop` / `clearSession` 按需覆写；**非 Core 后端勿调用 `Runner.start()`**。
- 同模块还有 `DemoAgentHandler`（Mock 默认路径）；与 `VersatileAgentHandler`、`CustomAgentHandler`（`AgentServiceAutoConfigurationMvcIntegrationTest`）同属异构接入样例。

## 延伸阅读

- Core Runner：[vendor/agent-core-java · 执行器 Runner](../../vendor/agent-core-java/documents/zh/2.开发指南/高阶用法/执行器Runner.md)
- Versatile 适配与 A2A 边界：[A2A 与平台边界](A2A与平台边界.md)
