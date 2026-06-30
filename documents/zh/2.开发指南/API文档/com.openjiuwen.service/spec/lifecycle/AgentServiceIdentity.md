# com.openjiuwen.service.spec.lifecycle.AgentServiceIdentity

## interface AgentServiceIdentity

```java
public interface AgentServiceIdentity
```

提供 Agent Service 应用名，供 lifecycle 日志和 `/health.app` 使用。

## 方法

| Signature | Description |
| --- | --- |
| `String getAppName()` | 返回应用名，默认实现读取 `spring.application.name`。 |
