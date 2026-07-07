# com.openjiuwen.service.spec.dto.ResetConversationResponse

## class ResetConversationResponse

```java
public class ResetConversationResponse
```

reset conversation 响应体。

## 字段

| Field | Description |
| --- | --- |
| `status` | 结果状态，例如 `ok`。 |
| `message` | 人类可读的操作结果描述。 |

## 方法

| Signature | Description |
| --- | --- |
| `public static ResetConversationResponse ok(String conversationId)` | 创建成功响应，消息格式为 `Conversation <id> reset`。 |
