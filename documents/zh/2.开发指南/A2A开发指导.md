# A2A 开发指导

## 概述

Agent Runtime Java 为每个 Agent 实例提供进程内 A2A Server 与 Client 能力，基于 `org.a2aproject.sdk:1.0.0.Final`。

```
A2A Client                         Agent Service（本仓）
──────▶ GET /.well-known/agent-card.json ────▶ AgentCardController
──────▶ POST /a2a (JSON-RPC)       ────▶ A2aJsonRpcController
                                         → A2AAgentExecutor
                                         → ServeOrchestrator
                                         → AgentHandler
```

## Maven 依赖

```xml
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-spec</artifactId>
    <version>1.0.0.Final</version>
</dependency>
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-server-common</artifactId>
    <version>1.0.0.Final</version>
</dependency>
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-jsonrpc-common</artifactId>
    <version>1.0.0.Final</version>
</dependency>
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-client</artifactId>
    <version>1.0.0.Final</version>
</dependency>
```

## 配置

所有 A2A 配置在 `openjiuwen.service.a2a` 下：

```yaml
openjiuwen:
  service:
    a2a:
      # AgentCard 内容
      agent-name: "My Agent"
      agent-description: "A helpful assistant"
      agent-version: "1.0.0"
      is-streaming: true
      is-push-notifications: false
      default-input-modes: ["text", "text/plain"]
      default-output-modes: ["text", "text/plain"]
      # 端点
      json-rpc-path: "/a2a"
      public-url: "https://my-agent.example.com"
      skills:
        - id: "search"
          name: "Search"
          description: "Search for information"
      # 远端 Agent
      remote-agents:
        - name: "hotel-agent"
          url: "https://hotel-agent.example.com/a2a"
          timeout-seconds: 300
```

## Bean 装配

`A2AEnabledServeOrchestrator` 是唯一的默认 `ServeOrchestrator` 实现。开发者自定义 `ServeOrchestrator` Bean 即可替换：

```java
@Bean
public ServeOrchestrator myOrchestrator(...) {
    return new MyCustomOrchestrator(...);
}
```

## A2A Server

### AgentCard 端点

`GET` 三个路径，返回内容相同：

| 路径 | 说明 |
|------|------|
| `/.well-known/agent-card.json` | 标准路径 |
| `/.well-known/agent.json` | 兼容路径 |
| `/a2a/.well-known/agent-card.json` | 兼容路径 |

### JSON-RPC 端点

`POST /a2a`（同时接受 `/a2a/`）处理以下方法：

| 方法 | 模式 | 说明 |
|------|------|------|
| `SendMessage` | 非流式 | JSON 单次响应 |
| `SendStreamingMessage` | SSE 流式 | `text/event-stream` |
| `GetTask` | 非流式 | 查询 Task 状态 |

流式响应格式：

```
event:jsonrpc
data:{"jsonrpc":"2.0","id":1.0,"result":{"statusUpdate":{"taskId":"...","status":{"state":"TASK_STATE_WORKING"},...}}}
```

### Task 状态

```
SUBMITTED → WORKING → COMPLETED / FAILED / INPUT_REQUIRED
```

### GetTask 响应

```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "id": "task-xxx",
        "contextId": "conv-1",
        "status": {"state": "TASK_STATE_COMPLETED", ...},
        "artifacts": [...],
        "history": [...]
    }
}
```

### 协议适配

A2A 请求 → `A2AProtocolAdapter.toServeRequest()`：

| A2A 字段 | ServeRequest |
|----------|-------------|
| `message.contextId` | `conversationId` |
| `message.taskId` | 透传 |
| `message.messageId` | 透传 |
| `message.role` | `messages[].role` |
| `message.parts[].text` | `messages[].content` |
| `params.metadata` | `metadata` |

`taskId` 非空时表示 resume 请求，服务端恢复已有 Task 上下文。

### Metadata 透传

`ServeRequest.metadata` 透传给远端 Agent：

```
A2A Client metadata
  → parseParams 解析 params.metadata
  → ProtocolAdapter.toServeRequest → ServeRequest.metadata
  → Orchestrator → A2ARemoteAgentClient
  → Message.metadata
  → 远端 Agent
```

## 中断-恢复链

### 概述

Agent A 调用工具时触发 `a2a_delegate` 中断，由 Orchestrator 路由到远端 Agent B。全程由 `A2AEnabledServeOrchestrator` 的 `streamQuery`/`query` 循环处理：

```
streamQuery/query:
  while (true):
    1. tryResumePending    → 检测挂起的影子 Task
    2. runAgentAndCaptureInterrupt → 捕获中断
    3. handleInterrupt     → 路由中断
       ├─ a2a_delegate → delegateSse / delegateSync
       └─ ask_user     → INPUT_REQUIRED 返回客户端
```

### 远端调用模式

通过中断 context 的 `_stream_mode` 字段选择：

| `_stream_mode` | 调用方式 | 说明 |
|---------------|---------|------|
| `"sse"` | `callStreaming()` | 中间输出实时透传 |
| 未设置 | `callSync()` | 仅返回最终结果 |

流式路径默认 `callSync()`，仅在工具显式设置 `_stream_mode: "sse"` 时启用 `callStreaming()`。非流式路径强制 `callSync()`。

### 中断数据格式

Tool Rail 在 context 中设置：

```java
context = Map.of(
    "agentName", "agentb",
    "_interrupt_kind", "a2a_delegate",
    "_stream_mode", "sse"
);
```

### 影子 Task

远端 Agent 返回 `INPUT_REQUIRED` 时，Orchestrator 在本地 TaskStore 保存影子 Task：

| 字段 | 内容 |
|------|------|
| `id` | `conversationId` |
| `status` | `INPUT_REQUIRED` |
| `metadata._agent_name` | 远端 Agent 名称 |
| `metadata._remote_task_id` | 远端 Task ID |
| `metadata._remote_url` | 远端 Agent URL |
| `metadata._stream_mode` | 调用模式 |

客户端下次以相同 `conversationId` 发请求时，`tryResumePending()` 通过 `taskStore.get(conversationId)` 检测影子 Task 并恢复远端调用。

### Resume 流程

```
请求（相同 conversationId）
  → tryResumePending → taskStore.get(conversationId)
    → 读取 _remote_task_id, _agent_name, _stream_mode
    → 恢复远端调用（带 remoteTaskId）
      ├─ COMPLETED → 删除影子 Task → 构建 resume 请求 → Agent A 继续
      └─ INPUT_REQUIRED → 保持影子 Task → 通知客户端
```

## A2A Client 远端调用

### 远端 Agent 发现

```yaml
remote-agents:
  - name: "agentb"
    url: "http://localhost:18091/a2a"
    timeout-seconds: 300
```

`A2AAgentCardDiscovery` 启动时探测远端 AgentCard，成功缓存，失败 30s 重试。

### A2ARemoteAgentClient

| 方法 | 说明 |
|------|------|
| `callStreaming(...)` | SSE 流式调用，返回 `CompletableFuture<String>` |
| `callSync(...)` | 阻塞调用，返回 `String`；远端中断时抛 `RemoteInputRequiredException` |

超时由 `remote-agents[].timeout-seconds` 控制。Orchestrator 透传 Agent A 的 `conversationId` 和 `metadata` 给 Agent B。

### 事件处理

| 事件 | 处理 |
|------|------|
| `TaskArtifactUpdateEvent` | 非 answer 透传给 `streamObserver`；answer 完成结果 |
| `TaskStatusUpdateEvent` | `INPUT_REQUIRED` → `RemoteInputRequiredException`；final → 空结果 |
| `TaskEvent` | `INPUT_REQUIRED` → `RemoteInputRequiredException`；final → 提取 artifact |

## Task 持久化

默认 `InMemoryTaskStore`。配置 Middleware Redis 后自动切换 `RedisTaskStore`：

```yaml
openjiuwen.service.middleware.checkpointer.type: redis
```

## 参考

- [A2A 与平台边界](A2A与平台边界.md)
- 测试模块：`service/agent-service-a2a-test/`
