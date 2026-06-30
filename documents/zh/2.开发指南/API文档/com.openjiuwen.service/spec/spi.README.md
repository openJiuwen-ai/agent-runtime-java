# spi

`com.openjiuwen.service.spec.spi` 定义 Agent Service 的核心扩展点。

## 类型

| Type | Description |
| --- | --- |
| [`AgentHandler`](spi/AgentHandler.md) | 执行后端适配入口。 |
| [`ServeOrchestrator`](spi/ServeOrchestrator.md) | 协议无关的 Query 编排入口。 |
| [`QueryStreamObserver`](spi/QueryStreamObserver.md) | 流式输出回调。 |

## 扩展关系

```text
Controller
  -> ServeOrchestrator
      -> AgentHandler
          -> QueryStreamObserver
```

业务通常只需要实现 `AgentHandler`；除非要替换默认编排逻辑，否则不需要自定义 `ServeOrchestrator`。
