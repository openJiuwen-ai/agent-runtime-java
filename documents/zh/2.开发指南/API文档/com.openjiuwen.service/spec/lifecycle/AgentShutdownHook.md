# com.openjiuwen.service.spec.lifecycle.AgentShutdownHook

## interface AgentShutdownHook

```java
public interface AgentShutdownHook
```

Agent Service shutdown 阶段回调。

## 方法

| Signature | Description |
| --- | --- |
| `void onShutdown(AgentLifecycleContext context)` | Spring context 关闭前执行，用于释放业务资源。 |
