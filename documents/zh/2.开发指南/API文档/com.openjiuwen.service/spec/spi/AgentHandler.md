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
| `default Optional<Object> prepareTask(ServeRequest request)` | 处理请求前获取请求范围内的资源，返回交给 `completeTask` 的令牌。默认返回 `Optional.empty()`。 |
| `default void completeTask(Optional<Object> taskToken)` | 请求处理结束后释放该令牌对应的资源。默认不执行操作。 |
| `default void start()` | Handler 启动钩子，通常用于启动 Runner 或建立后端连接。 |
| `default void stop()` | Handler 停止钩子。 |
| `default void clearSession(String conversationId)` | 清理某个会话的持久状态。 |

## 实现建议

- `streamQuery` 必须在正常结束时调用 `observer.onComplete()`。
- 出错时可以先 `observer.onNext(new QueryChunk(TYPE_ERROR, ...))`，再调用 `observer.onError(error)`。
- 一个请求可能触发多次 `query` 或 `streamQuery`。`prepareTask` 获取的资源应在这些调用期间保持可用；`completeTask` 收到空令牌或不属于当前处理过程的令牌时，不应释放资源。
- 非 Core 后端不要直接调用 Core `Runner.start()` / `Runner.stop()`。

## 相关实现

- `JiuwenCoreAgentHandler`
- `DemoAgentHandler`
- `EchoProxyAgentHandler`
