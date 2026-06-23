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
| `agent-service-adapters-common` | 共享 middleware / external 占位 | 被 agentcore leaf 依赖 |

`agentcore-ext`、`versatile` 等扩展 Handler **不在本仓库 adapters 内实现**；人机中断、远端低码等场景由业务镜像自定义 `@Bean AgentHandler` 或在外部模块接入。

## 配置示例

### Agent Core（默认高码）

```yaml
openjiuwen:
  service:
    handler: agentcore
    agent-id: my-workflow-agent
```

`JiuwenCoreAgentHandler` 通过 `agent-id` 从 `Runner.resourceMgr()` 加载 Agent 并调用 `Runner.query` / `stream`。

## 自动装配规则

- `agent-service-adapters-agentcore` 使用 `@ConditionalOnMissingBean(AgentHandler.class)`：已有 `@Bean AgentHandler` 时不装配默认 Handler。
- AutoConfiguration 注册在 `META-INF/spring/...AutoConfiguration.imports`。

## 包结构约定

adapter leaf 使用独立根包，例如：

- `com.openjiuwen.service.adapters.agentcore.agentfw`
- `com.openjiuwen.service.adapters.common.middleware`

子包含 `agentfw`、`middleware`、`autoconfigure`，与架构 Ingress/Egress 划分一致。

## 选型建议

| 场景 | 推荐方式 |
| --- | --- |
| 高码 Workflow / LlmAgent | `agentcore` + `agent-id` |
| 人机中断 / 远端低码 / 其他引擎 | 自定义 `@Bean AgentHandler` 或外部 adapter 模块 |

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
- 同模块还有 `DemoAgentHandler`（Mock 默认路径）等异构接入样例。

## 延伸阅读

- Core Runner：[agent-core-java · 执行器 Runner](https://gitcode.com/openJiuwen/agent-core-java/tree/0.1.12/documents/zh/2.开发指南/高阶用法/执行器Runner.md)
- A2A 与平台边界：[A2A 与平台边界](A2A与平台边界.md)
