# HTTP 对话面

Agent Service 对外 **Ingress** 与 Python `AgentApp` 对齐（设计共识 **C-013**）。路径常量在 `com.openjiuwen.service.spec.paths.AgentServicePaths`。

## 端点一览

| Path | 方法 | 说明 |
| --- | --- | --- |
| `/health` | GET | 进程与 Agent 就绪 |
| `/v1/query` | POST | 主路径；JSON body；`stream=true` 时 SSE |
| `/query` | POST | 兼容路径（可配置 `legacy-path-enabled`） |
| `/v1/query/reactive` | POST | WebFlux 栈流式（需 reactive 应用类型） |
| `/v1/reset_conversation` | POST | 清空会话上下文 |
| `/reset_conversation` | POST | 兼容路径 |

**本期不提供**：Agent Card、`POST /a2a/` JSON-RPC、`GET /agent_detail`（可选 501）。

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

- `X-User-ID`
- `X-Space-ID`

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
QueryController
  → 解析 QueryRequest.normalizeMessages()
  → ServeRequest（来自 spec.dto）
  → ServeOrchestrator.query / streamQuery
  → AgentHandler
```

## 配置项（Query）

前缀 `openjiuwen.service.query`（`QueryProperties`）：

| 属性 | 说明 |
| --- | --- |
| `enabled` | 是否启用 Query Controller |
| `mvc.enabled` | Servlet `/v1/query` |
| `webflux.enabled` | Reactive `/v1/query/reactive` |
| `legacy-path-enabled` | 是否暴露 `/query`、`/reset_conversation` |

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

# 重置会话
curl -s http://localhost:8090/v1/reset_conversation \
  -H 'Content-Type: application/json' \
  -d '{"conversation_id":"c1"}'
```

完整 smoke 脚本见 `agent-service-demo/scripts/smoke-query.sh`。
