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

`GET /health` **不受** `openjiuwen.service.security.auth` 鉴权 AOP 影响，便于负载均衡与 K8s 探针。

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
