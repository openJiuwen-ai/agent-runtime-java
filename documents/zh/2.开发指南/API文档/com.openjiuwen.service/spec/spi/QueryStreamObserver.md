# com.openjiuwen.service.spec.spi.QueryStreamObserver

## interface QueryStreamObserver

```java
public interface QueryStreamObserver
```

纯 Java 流式回调，不依赖 Reactor 类型。

## 方法

| Signature | Description |
| --- | --- |
| `void onNext(QueryChunk chunk)` | 输出一个流式片段。 |
| `void onError(Throwable error)` | 通知执行错误。 |
| `void onComplete()` | 通知流式执行正常完成。 |
| `default boolean isCancelled()` | 调用端是否已取消；默认 `false`。 |

## 使用位置

- `AgentHandler.streamQuery`
- `ServeOrchestrator.streamQuery`
- `QueryMvcController`
- `QueryWebFluxController`
