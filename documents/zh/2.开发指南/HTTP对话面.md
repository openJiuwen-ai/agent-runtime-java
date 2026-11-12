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

### Session 管控（Issue #117）

| Path | 方法 | 说明 |
| --- | --- | --- |
| `/api/sessions` | GET | 会话列表（分页 + state 过滤） |
| `/api/sessions/{sessionId}` | GET | 单会话生命周期详情 |
| `/api/sessions/{sessionId}/messages` | GET | 会话消息历史（默认关闭，数据面 T1 未交付前 501） |
| `/api/sessions/{sessionId}/pause` | POST | 暂停运行中会话（202 异步受理） |
| `/api/sessions/{sessionId}/resume` | POST | 恢复已暂停会话（202 异步受理） |
| `/api/sessions/{sessionId}/terminate` | POST | 终止会话（202 异步受理，幂等） |
| `/api/sessions/{sessionId}/compact` | POST | 压缩空闲会话（编排 T5 未交付前 501） |

详细契约与状态机见 [Session 管控面](#session-管控面api-sessions)。

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

## Session 管控面（`/api/sessions`）

Session 管控是管理面接口，与 `/v1/query` 数据面独立：它不发起对话，而是回答“现在有哪些会话、它们处于什么状态、如何暂停 / 恢复 / 终止一个正在执行的会话”。

调用链：

```text
SessionManageMvcController / SessionManageWebFluxController
  → SessionManageService（状态机判定）
  → SessionPauseResumeCoordinator（编排 + 准入闸 + 中断链路）
  → ServeOrchestrator.resumeSession / ActiveStreamInterruptor（terminate 共享）
  → Core Runner
```

### 会话状态机

管控层视角的会话状态只有四个值，由 `SessionManageService.sessionState(...)` 解析：

| 状态 | 来源 |
| --- | --- |
| `PAUSED` / `TERMINATED` | 管控层登记表：pause / terminate 编排的最新判定优先 |
| `RUNNING` | 活跃任务注册表（`ActiveTaskQuery`）中存在该会话的活动任务 |
| `IDLE` | 以上都不命中 |

```text
running ──pause──▶ paused ──resume──▶ running
   │                 │
   └──────┬──────────┘
          ▼
     terminated（终态，pause/resume 均不可达）
```

### POST pause / resume / terminate（异步受理）

三个操作端点都是**异步受理**语义：202 表示编排层已受理并在后台执行，不代表执行已到达目标状态。

| 操作 | 202 受理 | 200 幂等 | 409 冲突 | 429 | 404 |
| --- | --- | --- | --- | --- | --- |
| pause | running → 发起暂停 | 已是 paused（`alreadyPaused=true`） | terminated / idle | — | 会话不存在 / 功能关闭 |
| resume | paused → 发起恢复 | — | running / terminated | 准入闸满（`ADMISSION_REJECTED`，状态保持 paused） | 会话不存在 / 功能关闭 |
| terminate | running / paused → 发起中断 | 已是 terminated（`alreadyTerminated=true`） | — | — | 会话不存在 |

要点：

- **pause**：受理后编排层走与 terminate 相同的中断链路（`ActiveStreamInterruptor`），执行停在下一条步边界；core 侧游标落 `PAUSED` 状态并保存全量快照。受理链路带 1 秒去抖窗口，窗口内对同一会话的重复 pause 合并为一次执行。可选 Header：`X-User-Id`（操作人）、`X-Pause-Reason`（暂停原因），进入审计日志。
- **resume**：只有 `paused` 状态可恢复。受理前先过 `TaskAdmissionGate` 准入闸——闸满时返回 429 且会话**保持 paused**，稍后可重试；受理后经 `ServeOrchestrator.resumeSession(sessionId)` 从持久化的暂停边界续跑。
- **terminate**：running 与 paused 会话都可终止，重复请求幂等；终止后的会话不可恢复（resume 返回 409）。

202 响应体示例：

```json
{
  "accepted": true,
  "state": "PAUSED",
  "detail": "pause accepted; suspension will take effect at the next step boundary"
}
```

409 响应体示例：

```json
{
  "error": "INVALID_SESSION_STATE",
  "state": "RUNNING"
}
```

### GET 列表与详情

- `GET /api/sessions?state=running&page=0&size=20`：分页列表；列表能力（活跃任务注册表与协调器登记拼装）交付前返回空集合。
- `GET /api/sessions/{sessionId}`：单会话详情，返回 `{"sessionId": "...", "state": "..."}`；会话不存在返回 404。
- `GET /api/sessions/{sessionId}/messages`：消息历史端点；数据面存储列举能力交付前统一返回 501。

### POST compact

压缩空闲会话上下文，仅接受非运行态会话（运行中 / 已暂停返回 409）；压缩编排能力交付前统一返回 501。

### 鉴权与租户

管控端点全部标注 `@AuthorizedResource(resource="session", action=...)`，action 取值 `list` / `read` / `pause` / `resume` / `terminate` / `compact`，**没有匿名豁免**——即使 `security.auth.enabled` 未开启的默认链路外，管控面也始终走细粒度鉴权装配。

对 `/api/sessions/{sessionId}/...` 这类会话级路径，路径变量 `sessionId` 会被注入鉴权请求的 extensions（键 `resource.id`），`FineGrainedAuthorizer` SPI 实现可据此做“只允许操作自己的会话”这类资源级判权：

```java
public AuthorizationDecision authorize(AuthorizationRequest request) {
    String sessionId = request.getExtensions().get("resource.id");
    // 业务自定义判权逻辑
}
```

Servlet 栈从 `request.getRequestURI()` 解析 lookupPath 提取路径变量；WebFlux 栈因无 `HttpServletRequest`，lookupPath 缺省为 null，extensions 为空，鉴权实现需自行降级（fail-closed）。

### 配置项（`openjiuwen.service.session-api`）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `pause-enabled` | `true` | 是否提供 pause / resume 端点；关闭时返回 404 |

### 边界与注意事项

- 管控层的 `PAUSED` / `TERMINATED` 登记目前保存在**进程内存**中，实例重启后丢失，会话状态回退到由活跃任务注册表推断；跨实例共享登记是后续演进项。
- pause / resume 依赖 core 的步级断点内核（`step_cursor` / `step_cursor_paused`），键结构、扫描跳过与显式恢复语义见 Core 仓文档《Checkpointer 检查点机制》的「断点续跑与用户暂停」小节。
- 审计日志当前输出到应用日志（`Session audit: action=..., session_id=..., operator=...`），未落独立审计存储。

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
- [AaaS](AaaS.md) — 服务化形态与模块总览
