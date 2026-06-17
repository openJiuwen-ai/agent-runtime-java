# A2A 与平台边界

## 定案摘要

| 项 | Agent Service（本仓 P0） | 平台 A2A Service（规划） |
| --- | --- | --- |
| Agent Card | ❌ | ✅ |
| JSON-RPC `message/stream` | ❌ | ✅ |
| TaskStore（Redis） | ❌ | ✅ |
| 对话执行 | ✅ `POST /query` | 适配后转发 `/query` |
| 服务注册 | ❌ 不自注册 | 消费 Manager 目录 |

**Issue #4（进程内 A2A Server）已取消**；不在 `agent-service-app` 实现 Card + JSON-RPC。

## 为何 Agent 镜像不做 A2A

1. **统一对外协议面**：Client 对接平台 A2A，不必每个 Agent 记一套 Card URL。
2. **Task 在平台**：`task_id`、resubscribe、`INPUT_REQUIRED` 放在 A2A Service + Redis。
3. **镜像保持瘦**：避免每个 OCI 重复 `a2a-java-sdk` Server、TaskStore、限流。
4. **多 Agent 路由**：`agent_id` → `backend_base_url` 由 A2A Service 查目录完成。

## 数据面 E2E（平台形态）

```text
A2A Client
  → GET {a2a-platform}/a2a/.well-known/agent-card.json
  → POST {a2a-platform}/a2a/  message/stream
A2A Service
  → 目录：agent_id → backend_base_url
  → POST {backend}/v1/query  stream=true
Agent Service（本仓）
  → ServeOrchestrator → AgentHandler → Runner
```

Manager **不在**每次对话链路上；仅在 deploy 时写 `DeploymentRecord` 与网关注册。

## 与 Python 的对照

| Python 组件 | 角色 | Java 高码定案 |
| --- | --- | --- |
| `applications/a2a_service` | 平台 A2A + EDP 编排 | 独立 A2A Service（对内 `/query`） |
| `service/AgentApp` | 仅 `/query` 等 | **同左** |
| 高码 A2A → AgentApp | **未走通** | A2A Service → `/query` **补齐** |

## A2A Client 发现说明

- A2A Client **通常不从** Manager 或网关拉 **Agent Card 清单**。
- Manager 提供 **部署 list**（`url`、`name`）；标准 Client 认 **平台 A2A URL**。
- 进程内 A2A（每 Agent 一张 Card）为 **另一种形态**，与当前 Java 定案不同。

两种形态对比摘要：

| 形态 | A2A Server 位置 | 多 Agent 发现 |
| --- | --- | --- |
| **独立 A2A Service**（Java 定案） | 平台进程；对内 `POST /query` | 平台 URL + `agent_id` |
| **进程内 A2A**（未采用） | 每个 Agent OCI | 每个 Agent 各拉一张 Card |

更完整的边界说明见上文各节；独立 A2A Service 与 Manager、Deploy 联动细节将在 **`applications/a2a-service`** 与 **`manager/*`** 文档中维护。

## 本仓 A2A 相关代码

| 位置 | 说明 |
| --- | --- |
| `agent-service-app` · `controller.a2a` | 占位 package-info，**无** Server 实现 |
| `a2a-java-sdk`（根 POM dependencyManagement） | 供未来 A2A Service 或 Egress Client |
| Core `extensions.a2a` | A2A **Client** 与转换，非 Service Server |

## 延伸阅读

- [HTTP 对话面](HTTP对话面.md)：Agent 侧唯一标准执行入口
