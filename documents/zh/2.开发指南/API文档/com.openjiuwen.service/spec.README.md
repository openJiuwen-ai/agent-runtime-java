# com.openjiuwen.service.spec

**agent-service-spec** 模块：纯 Java 契约层，**无** Spring 依赖。被 `app` 与 `adapters` 共同依赖。

## 包结构

| 子包 | 内容 |
| --- | --- |
| `spec.paths` | `AgentServicePaths` — C-013 HTTP 路径常量 |
| `spec.dto` | `QueryRequest`、`QueryResponse`、`QueryChunk`、`HealthResponse`、`ResetConversationRequest/Response`、`ServeRequest` |
| `spec.spi` | `AgentHandler`、`ServeOrchestrator`、`QueryStreamObserver` |
| `spec.lifecycle` | `AgentInitHook`、`AgentShutdownHook`、`AgentInterruptHandler`、`AgentReadiness`、`AgentServiceIdentity` 等 |

## SPI 要点

### AgentHandler

执行后端适配入口。实现类位于 `agent-service-adapters-*` 或业务 `@Bean`。

### ServeOrchestrator

编排入口；默认实现 `DefaultServeOrchestrator`（`agent-service-app`）。负责：

- `query(ServeRequest)` 聚合非流式结果
- `streamQuery(ServeRequest, QueryStreamObserver)` 流式回调
- 活动流注册（供 interrupt / cancel）

### QueryStreamObserver

流式回调：`onNext(QueryChunk)`、`onError`、`onComplete`。

## DTO 要点

### QueryRequest

对外 HTTP 请求体；`normalizeMessages()` 将 `message` 简写转为 `messages` 列表。

### ServeRequest

编排层内部对象；由 Controller 从 `QueryRequest` 构建，字段与会话、租户对齐。

### HealthResponse

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
