# controller.probe

`com.openjiuwen.service.app.controller.probe` 提供健康探针和活动任务负载查询。

## 类型

| Type | Description |
| --- | --- |
| `HealthController` | `GET /health` Controller。 |
| `ActiveTaskController` | `GET /v1/current_active_tasks` Controller。 |

## 路径

| Path | Description |
| --- | --- |
| `/health` | 返回进程和 Agent 就绪状态。 |
| `/v1/current_active_tasks` | 返回活动任务快照，见 [活动任务负载](../../../../HTTP对话面.md#活动任务负载)。 |

`GET /health` 和 `GET /v1/current_active_tasks` 均不经过 Runtime 内置的 L1 细粒度鉴权（`openjiuwen.service.security.auth`）。
`/health` 用于负载均衡和 K8s 探针。

活动任务查询可能返回任务和会话标识，请通过网关或部署侧限制访问。`agentId` 仅用于筛选，不用于授权。

## 健康响应字段

| Field | Source |
| --- | --- |
| `status` | Controller 固定健康状态。 |
| `app` | `AgentServiceIdentity.getAppName()`。 |
| `version` | `openjiuwen.service.version`。 |
| `process_up` | `AgentReadiness.isProcessUp()`。 |
| `agent_loaded` | `AgentReadiness.isAgentLoaded()`。 |

## 相关文档

- [生命周期与探针](../../../../生命周期与探针.md)
