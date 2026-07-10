# com.openjiuwen.service.spec.spi.AgentHandler

## interface AgentHandler

```java
public interface AgentHandler
```

执行后端适配 SPI。默认实现是 `JiuwenCoreAgentHandler`；业务也可以注册 `@Bean AgentHandler` 覆盖默认装配。

## 方法

| Signature | Description |
| --- | --- |
| `QueryResponse query(ServeRequest request)` | 非流式执行入口。 |
| `void streamQuery(ServeRequest request, QueryStreamObserver observer)` | 流式执行入口，通过 observer 输出 `QueryChunk`。 |
| `default void start()` | Handler 启动钩子，通常用于启动 Runner 或建立后端连接。 |
| `default void stop()` | Handler 停止钩子。 |
| `default void clearSession(String conversationId)` | 清理某个会话的持久状态。 |

## 实现建议

- `streamQuery` 必须在正常结束时调用 `observer.onComplete()`。
- 出错时可以先 `observer.onNext(new QueryChunk(TYPE_ERROR, ...))`，再调用 `observer.onError(error)`。
- 非 Core 后端不要直接调用 Core `Runner.start()` / `Runner.stop()`。

## 相关实现

- `JiuwenCoreAgentHandler`
- `DemoAgentHandler`
- `EchoProxyAgentHandler`
