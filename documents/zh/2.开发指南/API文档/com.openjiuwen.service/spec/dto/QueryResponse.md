# com.openjiuwen.service.spec.dto.QueryResponse

## class QueryResponse

```java
public class QueryResponse
```

非流式 Query API 响应体。

## 字段

| Field | JSON | Description |
| --- | --- | --- |
| `result` | `result` | 聚合后的 assistant 输出，通常包含 `role`、`content`、事件或模型结果。 |
| `conversationId` | `conversation_id` | 会话 ID。 |

## 构造方法

| Signature | Description |
| --- | --- |
| `public QueryResponse()` | Jackson 反序列化使用。 |
| `public QueryResponse(Object result, String conversationId)` | 创建非流式响应。 |
