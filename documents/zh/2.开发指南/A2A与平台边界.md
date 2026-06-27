# A2A 与平台边界

## 定案摘要

| 项 | Agent Service（本仓 P0） | 平台 A2A Service（规划） |
| --- | --- | --- |
| Agent Card | ✅ 配置驱动, `A2AProperties` | ✅ 平台级 Card 聚合 |
| JSON-RPC `SendMessage` / `SendStreamingMessage` | ✅ `A2aJsonRpcController` | ✅ 网关级 JSON-RPC |
| TaskStore (InMemory / Redis) | ✅ `InMemoryTaskStore` + `RedisTaskStore` | ✅ 集中式 Redis |
| A2A Client（远端 Agent 探测+调用） | ✅ `A2AAgentCardDiscovery` + 增强 Orchestrator | ✅ 平台级路由 |
| 对话执行 `/v1/query` | ✅ 不变 | ✅ A2A → `/query` 转发 |
| 服务注册 | ❌ | ✅ 消费 Manager 目录 |

**Issue #4（进程内 A2A Server）已实现**；AgentCard + JSON-RPC + A2A Client 均在 `agent-service-app` 内交付。

## 两种形态共存

| 形态 | A2A Server 位置 | 适用场景 |
| --- | --- | --- |
| **进程内 A2A**（本期实现） | `agent-service-app.controller.a2a` | 单 Agent 镜像直连，无需平台 |
| **独立 A2A Service**（规划） | 独立平台进程 | 多 Agent 路由、统一网关、集中 TaskStore |

进程内 A2A 是独立 A2A Service 的超集：去掉路由/限流/Versatile 委托后，剩下的就是本期交付内容。

## 数据面 E2E

### 进程内 A2A（本期）

```text
A2A Client
  → GET /.well-known/agent-card.json
  → POST /a2a/ SendMessage / SendStreamingMessage
Agent Service（本仓）
  → A2AProtocolAdapter → ServeRequest
  → ServeOrchestrator → AgentHandler → Runner
  → A2AAgentExecutor → AgentEmitter → JSON-RPC response / SSE
```

### 平台 A2A（规划）

```text
A2A Client
  → GET {a2a-platform}/a2a/.well-known/agent-card.json
  → POST {a2a-platform}/a2a/
A2A Service
  → 目录：agent_id → backend_base_url
  → POST {backend}/v1/query 或 POST {backend}/a2a/
Agent Service（本仓）
  → ServeOrchestrator → AgentHandler → Runner
```

## 与 Python 的对照

| Python 组件 | 角色 | Java 实现 |
| --- | --- | --- |
| `applications/a2a_service` | 平台 A2A + EDP 编排 | 进程内 A2A Server + 独立 A2A Service（规划） |
| `service/AgentApp` | `/query` + `/a2a` | **同左**（本期新增 A2A 入口） |
| A2A → AgentApp | **已走通** | `A2aJsonRpcController` → `ServeOrchestrator` → `AgentHandler` |

## 本仓 A2A 相关代码

| 位置 | 说明 |
| --- | --- |
| `agent-service-app` · `controller.a2a` | AgentCard + JSON-RPC Controller |
| `agent-service-app` · `controller.a2a-client` | 远端 AgentCard 探测 + 注册 |
| `agent-service-app` · `orchestrator` | `A2AEnabledServeOrchestrator`（影子 Task 路由） |
| `agent-service-spec` · `paths` | `A2AServicePaths`（路径常量） |
| `agent-service-spec` · `dto` | `ServeRequest.metadata`（协议元数据透传） |
| `a2a-java-sdk`（根 POM dependencyManagement） | `org.a2aproject.sdk:*:1.0.0.Final` |

## 延伸阅读

- [A2A 开发指导](A2A开发指导.md)：A2A Server/Client 实现细节
- [HTTP 对话面](HTTP对话面.md)：REST 执行入口
