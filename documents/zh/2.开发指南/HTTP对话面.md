# HTTP 对话面

Agent Service 对外 **Ingress** HTTP 契约与 Python `AgentApp` 对齐。路径常量在 `com.openjiuwen.service.spec.paths.AgentServicePaths`；A2A 路径在 `A2AServicePaths`。

与 **A2A JSON-RPC** 的关系：两者共用 `ServeOrchestrator` → `AgentHandler`，但路由与协议不同。A2A 详见 [A2A 开发指导](A2A/开发指导.md)。

REST 与 A2A 的完整请求、响应、SSE、`TextPart`/`DataPart` 和中断恢复报文见 [对话接口输入与输出](对话接口输入与输出.md)。

## 端点一览

### Query 与健康

| Path | 方法 | 说明 |
| --- | --- | --- |
| `/health` | GET | 进程与 Agent 就绪 |
| `/v1/query` | POST | 主路径；JSON body；`stream=true` 时 SSE |
| `/query` | POST | 兼容路径（`legacy-path-enabled`） |
| `/v1/query/reactive` | POST | WebFlux 流式（需 reactive 应用类型） |
| `/v1/reset_conversation` | POST | 清空会话上下文 |
| `/reset_conversation` | POST | 兼容路径 |

### A2A（Ingress，摘要）

| Path | 方法 | 说明 |
| --- | --- | --- |
| `/.well-known/agent-card.json` | GET | Agent Card（标准） |
| `/.well-known/agent.json` | GET | Agent Card（兼容） |
| `/a2a/.well-known/agent-card.json` | GET | Agent Card（兼容） |
| `/a2a` / `/a2a/` | POST | JSON-RPC：`SendMessage`、`SendStreamingMessage`、`GetTask` |

完整说明见 [A2A 开发指导 · A2A Server](A2A/开发指导.md#a2a-server)。

## GET /health

响应字段（`HealthResponse`）：

| 字段 | 含义 |
| --- | --- |
| `status` | 如 `healthy` |
| `app` | 应用名（`spring.application.name`） |
| `version` | `openjiuwen.service.version` |
| `process_up` | 进程存活 |
| `agent_loaded` | Init 完成且 Handler 就绪 |

**K8s 建议**：

- **liveness**：`process_up == true`
- **readiness**：`agent_loaded == true`

示例：

```bash
curl -s http://localhost:8090/health
```

## POST /query（QueryRequest）

请求体主要字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversation_id` | string | 会话 ID；多轮必填 |
| `message` | string | 单轮简写；会归一化为 `messages` |
| `messages` | array | OpenAI 风格消息列表 |
| `stream` | boolean | 默认 `true`；`false` 返回聚合 JSON |
| `user_id` / `space_id` | string | 默认 `anonymous` / `default` |
| `tenant_id` | string | 可选 |

Header（与 Python 对齐，网关可注入）：

- `X-User-ID` — 映射到 `user_id`（body 未填时）
- `X-Space-ID` — 映射到 `space_id`（body 未填时）

### 非流式响应

`stream=false` 时返回 JSON，包含 `result`（assistant 消息）与 `conversation_id`。

### 流式响应

`stream=true` 时 `Content-Type: text/event-stream`，每行 `data: {...}` 为 `QueryChunk` 序列化结果。

Servlet 栈使用 `/v1/query`；WebFlux 栈流式使用 `/v1/query/reactive`。

## POST /reset_conversation

请求：`{"conversation_id":"..."}`。

行为：调用 `AgentHandler.clearSession(conversationId)`（Core 路径为 `Runner.release`）。**不删除** `conversation_id` 本身。

与 **interrupt**（停止当前流式 run）独立；interrupt 无 HTTP，见 [生命周期与探针](生命周期与探针.md)。

## 调用链

```text
QueryMvcController / QueryWebFluxController
  → QueryIngressSupport（租户 Header、body 归一化）
  → ServeRequest（spec.dto）
  → ServeOrchestrator.query / streamQuery
      （默认或 A2A 增强实现，见架构概述）
  → AgentHandler
  → Core Runner（agentcore 路径）
```

Controller **禁止**绕过 Orchestrator 直连 `Runner`。A2A JSON-RPC 经 `A2AProtocolAdapter` 转成 `ServeRequest` 后走同一条 Orchestrator 链。

## 配置项

### Query（`openjiuwen.service.query`）

| 属性 | 说明 |
| --- | --- |
| `enabled` | 是否启用 Query Controller |
| `mvc.enabled` | Servlet `/v1/query` |
| `webflux.enabled` | Reactive `/v1/query/reactive` |
| `legacy-path-enabled` | 是否暴露 `/query`、`/reset_conversation` |

### 服务元数据

| 配置 | 说明 |
| --- | --- |
| `openjiuwen.service.version` | `/health` 与对外版本字段 |
| `spring.application.name` | `/health` 的 `app` 字段；A2A Agent Card `name` 亦会引用 |

中间件与外部 egress 配置不在 HTTP 层，见 [Adapters 与 Handler](Adapters与Handler.md)。

### 入站安全（`openjiuwen.service.security`，Issue #24）

默认 **关闭**；开启后 HTTP 对话面分为两层：

| 层 | 配置 | 行为 |
| --- | --- | --- |
| **L0 传输** | `security.tls.enabled` | HTTPS / mTLS；映射 Spring `server.ssl.*` |
| **L1 鉴权** | `security.auth.enabled` | `@AuthorizedResource` + `FineGrainedAuthorizer`；拒绝 **403**（`code=ACCESS_DENIED`） |

`/health` 不受 L1 鉴权影响。租户 Header 进入鉴权链的行为见 [对话接口输入与输出 · 租户 Header 与鉴权链](对话接口输入与输出.md#租户-header-与鉴权链)。完整配置与 demo 见 [安全加固](开发与扩展/安全加固.md)。

## 示例

```bash
# 非流式
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"c1","message":"你好","stream":false}'

# 流式
curl -N http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"c1","message":"你好","stream":true}'

# 租户 Header（与 body 字段等价）
curl -s http://localhost:8090/v1/query \
  -H 'Content-Type: application/json' \
  -H 'X-User-ID: u1' -H 'X-Space-ID: sp1' \
  -d '{"conversation_id":"c1","message":"你好","stream":false}'

# 重置会话
curl -s http://localhost:8090/v1/reset_conversation \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"c1"}'

# Agent Card（A2A Ingress）
curl -s http://localhost:8090/.well-known/agent-card.json
```

完整 smoke 脚本：`agent-service-demo/scripts/smoke-query.sh`。Demo 更多场景见 [agent-service-demo/README.md](../../service/agent-service-demo/README.md)。

## 延伸阅读

- [架构概述 · Ingress 与 Egress](架构概述.md#4-ingress-与-egress)
- [开发 Agent Service](开发Agent Service.md) — `application.yml` 装配
- [安全加固](开发与扩展/安全加固.md) — 入站 TLS/mTLS 与细粒度鉴权
- [A2A 与平台边界](A2A/平台边界.md)
