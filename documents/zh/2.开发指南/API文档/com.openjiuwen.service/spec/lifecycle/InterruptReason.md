# com.openjiuwen.service.spec.lifecycle.InterruptReason

## enum InterruptReason

```java
public enum InterruptReason
```

活动会话 interrupt 原因。

## 枚举值

| Value | Description |
| --- | --- |
| `USER_REQUEST` | 用户主动请求中断。 |
| `LIFECYCLE_SHUTDOWN` | 服务生命周期关闭。 |
| `LIFECYCLE_INTERRUPT` | 生命周期管理器触发中断。 |
| `OTHER` | 其他原因。 |
