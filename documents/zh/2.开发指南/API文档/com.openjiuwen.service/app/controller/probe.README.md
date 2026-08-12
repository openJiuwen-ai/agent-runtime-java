# controller.probe

`com.openjiuwen.service.app.controller.probe` 提供健康探针。

## 类型

| Type | Description |
| --- | --- |
| `HealthController` | `GET /health` Controller。 |

## 路径

| Path | Description |
| --- | --- |
| `/health` | 返回进程和 Agent 就绪状态。 |

**不受** `openjiuwen.service.security.auth` 鉴权 AOP 影响，便于负载均衡与 K8s 探针。

## 响应字段

| Field | Source |
| --- | --- |
| `status` | Controller 固定健康状态。 |
| `app` | `AgentServiceIdentity.getAppName()`。 |
| `version` | `openjiuwen.service.version`。 |
| `process_up` | `AgentReadiness.isProcessUp()`。 |
| `agent_loaded` | `AgentReadiness.isAgentLoaded()`。 |

## 相关文档

- [生命周期与探针](../../../../生命周期与探针.md)
