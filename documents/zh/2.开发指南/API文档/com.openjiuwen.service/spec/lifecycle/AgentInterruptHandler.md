# com.openjiuwen.service.spec.lifecycle.AgentInterruptHandler

## interface AgentInterruptHandler

```java
public interface AgentInterruptHandler
```

活动会话执行被 interrupt 时的通知回调。

## 方法

| Signature | Description |
| --- | --- |
| `void interrupt(String conversationId, InterruptReason reason)` | 指定会话被中断时触发。 |
