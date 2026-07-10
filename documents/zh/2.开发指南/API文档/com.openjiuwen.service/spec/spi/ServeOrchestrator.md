# com.openjiuwen.service.spec.spi.ServeOrchestrator

## interface ServeOrchestrator

```java
public interface ServeOrchestrator
```

Ingress 协议无关的编排 SPI。Controller 只负责协议解析，具体执行、活动流登记、取消和会话清理由 `ServeOrchestrator` 负责。

## 方法

| Signature | Description |
| --- | --- |
| `QueryResponse query(ServeRequest request)` | 非流式 Query 编排。 |
| `void streamQuery(ServeRequest request, QueryStreamObserver observer)` | 流式 Query 编排。 |
| `void cancelActive(String conversationId)` | 取消指定 conversation 的活动流或进行中执行。 |
| `void resetConversation(String conversationId)` | 取消活动流并清理 Handler 会话状态。 |

## 默认实现

- `DefaultServeOrchestrator`
- `A2AEnabledServeOrchestrator`
