# com.openjiuwen.service.spec.dto.ResetConversationRequest

## class ResetConversationRequest

```java
public class ResetConversationRequest
```

`POST /v1/reset_conversation` 和兼容路径 `POST /reset_conversation` 请求体。

## 字段

| Field | JSON | Description |
| --- | --- | --- |
| `conversationId` | `conversation_id` | 需要清理上下文的会话 ID。 |
| `userId` | `user_id` | 可选用户 ID。 |
