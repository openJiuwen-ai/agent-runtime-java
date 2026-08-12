# controller.reset

`com.openjiuwen.service.app.controller.reset` 提供 reset conversation 入口。

## 类型

| Type | Description |
| --- | --- |
| `ResetConversationMvcController` | Servlet reset Controller。 |
| `ResetIngressSupport` | reset 请求校验和错误响应辅助。 |

## 路径

| Path | Description |
| --- | --- |
| `/v1/reset_conversation` | 主 reset conversation 路径。 |
| `/reset_conversation` | 兼容路径，需要 `legacy-path-enabled=true`。 |

## 鉴权

`@AuthorizedResource(resource = "session", action = "reset")`。详见 [安全加固](../../../../开发与扩展/安全加固.md)。

## 调用链

```text
ResetConversationMvcController
  -> ResetIngressSupport.validate
  -> ServeOrchestrator.resetConversation
```

`resetConversation` 会先取消活动流，再调用 `AgentHandler.clearSession(conversationId)`。
