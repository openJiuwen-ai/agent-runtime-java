# A2A 与平台边界

本文说明 **进程内 A2A**（单 Agent 镜像内）与 **平台级 A2A 服务**（多 Agent 路由）的边界。实现细节见 [A2A 开发指导](A2A开发指导.md)；与 lifecycle interrupt 的关系见 [生命周期与探针](生命周期与探针.md)。

## 能力对照

| 项 | Agent Service（本仓） | 平台 A2A Service（规划） |
| --- | --- | --- |
| Agent Card | ✅ `A2AProperties` 配置驱动 | ✅ 平台级 Card 聚合 |
| JSON-RPC `SendMessage` / `SendStreamingMessage` | ✅ `A2aJsonRpcController` | ✅ 网关级 JSON-RPC |
| `GetTask` | ✅ | ✅ |
| Task 取消（Cancel） | ✅ `A2AAgentExecutor.cancel` | ✅ 平台级路由 |
| TaskStore（InMemory / Redis） | ✅ | ✅ 集中式 Redis |
| A2A Client（远端探测 + 调用） | ✅ Discovery + 增强 Orchestrator | ✅ 平台级路由 |
| HTTP `/v1/query` | ✅ 并存 | ✅ 可转发至 `/query` 或 `/a2a` |
| 服务注册 / 目录 | ❌ | ✅ 消费 Manager 目录 |

本仓已在 `agent-service-app` 内交付 **进程内 A2A Server** 与 **出站 Client**（Orchestrator 委派、影子 Task）。

## 两种形态共存

| 形态 | A2A Server 位置 | 适用场景 |
| --- | --- | --- |
| **进程内 A2A**（本仓） | `agent-service-app.controller.a2a` | 单 Agent 镜像直连，无需平台 |
| **独立 A2A Service**（规划） | 独立平台进程 | 多 Agent 路由、统一网关、集中 TaskStore |

进程内 A2A 覆盖单镜像直连场景；平台服务额外提供跨 Agent 路由、限流与集中目录。

## 数据面 E2E

### 进程内 A2A（本仓）

```text
A2A Client
  → GET /.well-known/agent-card.json
  → POST /a2a/  SendMessage / SendStreamingMessage / GetTask / Cancel
Agent Service（本仓）
  → A2AProtocolAdapter → ServeRequest
  → A2AEnabledServeOrchestrator → AgentHandler → Runner
  → A2AAgentExecutor → JSON-RPC response / SSE
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
| `applications/a2a_service` | 平台 A2A + 编排 | 进程内 A2A + 独立 A2A Service（规划） |
| `service/AgentApp` | `/query` + `/a2a` | 同左 |
| A2A → Agent 执行 | AgentApp 编排 | `A2aJsonRpcController` → `ServeOrchestrator` → `AgentHandler` |

## 本仓 A2A 相关代码

| 位置 | 说明 |
| --- | --- |
| `controller.a2a` | AgentCard、JSON-RPC、`A2AAgentExecutor` |
| `controller.a2a.client` | 远端 AgentCard 探测（`A2AAgentCardDiscovery`） |
| `orchestrator` | `A2AEnabledServeOrchestrator`（中断-恢复、影子 Task） |
| `spec.paths` | `A2AServicePaths` |
| `spec.dto` | `ServeRequest.metadata` 透传 |
| `org.a2aproject.sdk` | 根 POM `1.0.0.Final`（`agent-service-app` 传递依赖） |

## 延伸阅读

- [A2A 开发指导](A2A开发指导.md) — 配置、JSON-RPC、中断-恢复、Cancel
- [HTTP 对话面](HTTP对话面.md) — REST 入口与 A2A Ingress 摘要
- [逻辑架构 · 数据面路径](逻辑架构.md#53-数据面--进程内-a2a本仓已交付)
