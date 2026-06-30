# com.openjiuwen.service.spec.lifecycle.AgentInitHook

## interface AgentInitHook

```java
public interface AgentInitHook
```

Agent Service init 阶段回调。适合做依赖 Handler 的预热或辅助资源初始化。

## 方法

| Signature | Description |
| --- | --- |
| `void onInit(AgentLifecycleContext context) throws Exception` | Init 阶段执行；抛出异常时由 lifecycle 配置决定是否 fail fast。 |
