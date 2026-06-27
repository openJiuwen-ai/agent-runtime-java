# com.openjiuwen.service.spec

**agent-service-spec** 模块：纯 Java 契约层，**无** Spring 依赖。被 `app` 与 `adapters` 共同依赖。

## 包结构

| 子包 | 内容 |
| --- | --- |
| `spec.paths` | `AgentServicePaths`（REST 路径）、`A2AServicePaths`（A2A 路径） |
| `spec.dto` | `QueryRequest`、`QueryResponse`、`QueryChunk`、`HealthResponse`、`ResetConversationRequest/Response`、`ServeRequest` |
| `spec.spi` | `AgentHandler`、`ServeOrchestrator`、`QueryStreamObserver` |
| `spec.lifecycle` | `AgentInitHook`、`AgentShutdownHook`、`AgentInterruptHandler`、`AgentReadiness`、`AgentServiceIdentity` 等 |

## SPI 要点

### AgentHandler

执行后端适配入口。实现类位于 `agent-service-adapters-*` 或业务 `@Bean`。

```java
public interface AgentHandler {
    QueryResponse query(ServeRequest request);
    void streamQuery(ServeRequest request, QueryStreamObserver observer);
    void clearSession(String conversationId);
    // start() / stop() omitted for brevity
}
```

### ServeOrchestrator

编排入口；默认实现 `DefaultServeOrchestrator`（`agent-service-app`）。A2A 激活时 `A2AEnabledServeOrchestrator` 替代。

- `query(ServeRequest)` 聚合非流式结果
- `streamQuery(ServeRequest, QueryStreamObserver)` 流式回调
- `cancelActive(String conversationId)` 取消活动流
- `resetConversation(String conversationId)` 取消+清理会话

### QueryStreamObserver

流式回调：`onNext(QueryChunk)`、`onError`、`onComplete`、`isCancelled`。

## DTO 要点

### QueryRequest

对外 HTTP 请求体；`normalizeMessages()` 将 `message` 简写转为 `messages` 列表。

```java
public class QueryRequest {
    List<Map<String, Object>> messages;
    String conversationId;
    String userId;
    String spaceId;
    String tenantId;
    boolean stream;
    String message;  // shorthand → normalized to messages
}
```

### ServeRequest

编排层内部对象；由 Controller 从 `QueryRequest` 或 A2A Protocol Adapter 构建。

```java
public class ServeRequest {
    String conversationId;
    List<Map<String, Object>> messages;
    String userId;
    String spaceId;
    String tenantId;
    boolean stream;
    Map<String, Object> metadata;  // ★ 协议元数据透传（A2A params.metadata / REST headers+body）
}
```

### A2AServicePaths

A2A 专用路径常量：

```java
public static final String WELL_KNOWN_AGENT_CARD = "/.well-known/agent-card.json";
public static final String A2A_JSONRPC = "/a2a/";
public static final String A2A_WELL_KNOWN_CARD = "/a2a/.well-known/agent-card.json";
```

## A2A 中断机制

Agent 触发 A2A Tool 调用时，AgentHandler 产生 `a2a_interrupt` 类型的 `QueryChunk`：

```java
new QueryChunk("a2a_interrupt", Map.of(
    "agentName", "hotel-agent",
    "interruptRequest", Map.of("message", "...")
));
```

Orchestrator 检测到此 chunk 后调远端 Agent，根据结果决定 resume 或关闭 SSE。详细流程见 [A2A 开发指导](../A2A开发指导.md)。

## HealthResponse

`process_up`、`agent_loaded` 与 Python `AgentApp` 探针语义对齐。

## Maven 坐标

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-spec</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 源码路径

`service/agent-service-spec/src/main/java/com/openjiuwen/service/spec/`
