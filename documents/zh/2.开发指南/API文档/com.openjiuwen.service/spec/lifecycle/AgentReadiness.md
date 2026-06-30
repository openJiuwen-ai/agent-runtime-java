# com.openjiuwen.service.spec.lifecycle.AgentReadiness

## interface AgentReadiness

```java
public interface AgentReadiness
```

`GET /health` 使用的就绪状态抽象。

## 方法

| Signature | Description |
| --- | --- |
| `boolean isProcessUp()` | JVM / HTTP stack 是否存活。 |
| `boolean isAgentLoaded()` | Agent / `AgentHandler` 是否完成 init 并可服务 Query。 |
