# com.openjiuwen.service.spec.dto.HealthResponse

## class HealthResponse

```java
public class HealthResponse
```

`GET /health` 响应体，与 Python `AgentApp` 探针语义对齐。

## 字段

| Field | JSON | Description |
| --- | --- | --- |
| `status` | `status` | 健康状态，例如 `healthy`。 |
| `app` | `app` | 应用名，默认来自 `spring.application.name`。 |
| `version` | `version` | Service 版本，来自 `openjiuwen.service.version`。 |
| `processUp` | `process_up` | HTTP 进程是否存活。 |
| `agentLoaded` | `agent_loaded` | Agent / Handler 是否完成 init。 |

## 构造方法

| Signature | Description |
| --- | --- |
| `public HealthResponse()` | Jackson 使用。 |
| `public HealthResponse(String status, String app, String version, boolean processUp, boolean agentLoaded)` | 创建健康响应。 |
