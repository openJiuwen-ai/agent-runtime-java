# A2A 开发指导

本文说明 **进程内 A2A Server**（Ingress）与 **出站 A2A Client**（Orchestrator 委派）的配置与调用链。平台级多 Agent 路由见 [A2A 与平台边界](A2A与平台边界.md)。

**前置阅读**：[HTTP 对话面](HTTP对话面.md)（REST Ingress）、[生命周期与探针](生命周期与探针.md)（Cancel vs lifecycle interrupt）。

## 概述

每个 Agent Service 实例基于 `org.a2aproject.sdk:1.0.0.Final` 提供：

- **A2A Server**：Agent Card + JSON-RPC
- **A2A Client**：远端 Agent 发现与调用（Orchestrator `a2a_delegate`）
- **TaskStore**：InMemory 或 Redis（与 Checkpointer 共用 Redis 配置时可切换）

```text
A2A Client                         Agent Service（本仓）
──────▶ GET /.well-known/agent-card.json ────▶ AgentCardController
──────▶ POST /a2a (JSON-RPC)       ────▶ A2aJsonRpcController
                                         → A2AAgentExecutor
                                         → A2AEnabledServeOrchestrator
                                         → AgentHandler → Runner
```

引入 `agent-service-app` 时，A2A SDK 由模块传递依赖提供；一般 **无需** 在业务 POM 中重复声明 SDK 坐标。

## Maven 依赖（可选显式声明）

仅在需要直接引用 SDK API 时添加：

```xml
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-spec</artifactId>
    <version>1.0.0.Final</version>
</dependency>
<!-- server-common、jsonrpc-common、client 按需 -->
```

## 配置

Agent Card 的 `name` 取自 `spring.application.name`，`version` 取自 `openjiuwen.service.version`。其余在 `openjiuwen.service.a2a` 下：

```yaml
spring:
  application:
    name: "My Agent"

openjiuwen:
  service:
    version: "1.0.0"
    a2a:
      agent-description: "A helpful assistant"
      public-url: "https://my-agent.example.com"   # Card 中的对外 URL；可留空由请求推断
      is-streaming: true
      json-rpc-path: "/a2a"
      task-completion-timeout-seconds: 300
      skills:
        - id: search
          name: Search
          description: Search for information
      remote-agents:
        - name: hotel-agent
          url: https://hotel-agent.example.com/a2a
          timeout-seconds: 300
```

出站 **Remote（A2A）** 也可通过 `openjiuwen.service.external.remote` 配置（Core RemoteClient 路径），与 `a2a.remote-agents`（Orchestrator 委派）是 **两条集成路径**，见 [Adapters 与 Handler](Adapters与Handler.md)。

TaskStore Redis：配置 `openjiuwen.service.middleware.checkpointer.type: redis` 后，A2A 可复用 Redis 连接使用 `RedisTaskStore`。

## Bean 装配

默认 `ServeOrchestrator` 为 `A2AEnabledServeOrchestrator`。自定义编排：

```java
@Bean
ServeOrchestrator myOrchestrator(...) {
    return new MyCustomOrchestrator(...);
}
```

## A2A Server

### AgentCard 端点

`GET` 三路径，内容相同：

| 路径 | 说明 |
| --- | --- |
| `/.well-known/agent-card.json` | 标准 |
| `/.well-known/agent.json` | 兼容 |
| `/a2a/.well-known/agent-card.json` | 兼容 |

### JSON-RPC 端点

`POST /a2a`（及 `/a2a/`）：

| 方法 | 模式 | 说明 |
| --- | --- | --- |
| `SendMessage` | 非流式 | JSON 单次响应 |
| `SendStreamingMessage` | SSE | `text/event-stream` |
| `GetTask` | 非流式 | 查询 Task 状态 |
| Cancel（SDK） | — | 见下文「Cancel 与 interrupt」 |

流式 SSE 示例格式：

```text
event:jsonrpc
data:{"jsonrpc":"2.0","id":1.0,"result":{...}}
```

### Task 状态

```text
SUBMITTED → WORKING → COMPLETED / FAILED / INPUT_REQUIRED
```

### 协议适配

`A2AProtocolAdapter.toServeRequest()` 映射要点：

| A2A 字段 | ServeRequest |
| --- | --- |
| `message.contextId` | `conversationId` |
| `message.taskId` / `messageId` | 透传 |
| `message.role` / `parts[].text` | `messages[]` |
| `params.metadata` | `metadata` |

`taskId` 非空时表示 resume 请求。

### Metadata 透传

```text
A2A Client metadata → ServeRequest.metadata → Orchestrator → A2ARemoteAgentClient → 远端 Agent
```

## Cancel 与 interrupt（勿混淆）

| 机制 | 入口 | 作用 |
| --- | --- | --- |
| **A2A Cancel** | JSON-RPC → `A2AAgentExecutor.cancel` | `orchestrator.cancelActive(contextId)`，停止当前 A2A 流 |
| **Lifecycle interrupt** | `AgentLifecycleManager.interrupt`（无 REST） | 同上 `cancelActive` + `AgentInterruptHandler` |
| **业务 interrupt** | Handler 发出 `QueryChunk("interrupt")` | Orchestrator 路由（`a2a_delegate`、`ask_user`） |
| **reset_conversation** | `POST /v1/reset_conversation` | `cancelActive` + `clearSession` |

详见 [生命周期与探针 · 三种中断语义](生命周期与探针.md#三种中断语义)。

## 中断-恢复链（业务协作）

Agent A 工具触发 `a2a_delegate` 时，`A2AEnabledServeOrchestrator` 循环处理：

```text
streamQuery / query:
  while (true):
    1. tryResumePending        → 影子 Task
    2. runAgentAndCaptureInterrupt
    3. handleInterrupt
       ├─ a2a_delegate → 出站 A2A Client
       └─ ask_user     → INPUT_REQUIRED 返回客户端
```

### 远端调用模式（`_stream_mode`）

| `_stream_mode` | 调用 | 说明 |
| --- | --- | --- |
| `"sse"` | `callStreaming()` | 中间输出透传 |
| 未设置 | `callSync()` | 仅最终结果 |

### 中断 context 示例

```java
context = Map.of(
    "agentName", "agentb",
    "_interrupt_kind", "a2a_delegate",
    "_stream_mode", "sse"
);
```

### 影子 Task

远端返回 `INPUT_REQUIRED` 时，TaskStore 保存影子 Task（`conversationId` 为 key），字段含 `_remote_task_id`、`_agent_name`、`_remote_url`、`_stream_mode`。下次同 `conversationId` 请求由 `tryResumePending()` 恢复。

## A2A Client 远端调用

### 配置（`a2a.remote-agents`）

```yaml
openjiuwen:
  service:
    a2a:
      remote-agents:
        - name: agentb
          url: http://localhost:18091/a2a
          timeout-seconds: 300
```

`A2AAgentCardDiscovery` 启动探测 Agent Card，失败约 30s 重试。

### A2ARemoteAgentClient

| 方法 | 说明 |
| --- | --- |
| `callStreaming(...)` | SSE 流式，返回 `CompletableFuture` |
| `callSync(...)` | 阻塞；远端 `INPUT_REQUIRED` 时抛 `RemoteInputRequiredException` |

Orchestrator 透传 `conversationId` 与 `metadata` 给远端 Agent。

## Task 持久化

| 模式 | 配置 |
| --- | --- |
| 内存（默认） | 无需额外配置 |
| Redis | `openjiuwen.service.middleware.checkpointer.type: redis` + `middleware.redis` |

## 测试与示例

| 资源 | 说明 |
| --- | --- |
| `service/agent-service-a2a-test` | 中断-恢复、Client 最佳实践 |
| `agent-service-demo` + `application-a2a-remote.yml` | 出站 Remote 示例 |
| `example/remote/` | Core RemoteClient 装饰示例 |

```bash
cd service
mvn -pl agent-service-a2a-test -am test
```

## 延伸阅读

- [A2A 与平台边界](A2A与平台边界.md)
- [HTTP 对话面](HTTP对话面.md)
- [生命周期与探针](生命周期与探针.md)
- [逻辑架构](逻辑架构.md)
