# com.openjiuwen.service.spec.dto.QueryRequest

## class QueryRequest

```java
public class QueryRequest
```

外部 Query API 请求体，对应 `POST /v1/query` 和兼容路径 `POST /query`。

## 字段

| Field | JSON | Default | Description |
| --- | --- | --- | --- |
| `messages` | `messages` | `[]` | OpenAI 风格消息列表。 |
| `conversationId` | `conversation_id` | `null` | 会话 ID，多轮对话和 reset 使用同一 ID。 |
| `userId` | `user_id` | `anonymous` | 用户 ID。 |
| `spaceId` | `space_id` | `default` | 空间 ID。 |
| `tenantId` | `tenant_id` | `null` | 租户 ID。 |
| `stream` | `stream` | `true` | 是否流式输出。 |
| `message` | `message` | `null` | 单轮消息简写。 |

## 方法

| Signature | Description |
| --- | --- |
| `public void normalizeMessages()` | 当 `messages` 为空且 `message` 非空时，包装成单条 `role=user` 消息。 |
| `public void setMessages(List<Map<String, Object>> messages)` | 空值保护，传入 `null` 时使用空列表。 |
| `public void setUserId(String userId)` | 空值保护，传入 `null` 时使用 `anonymous`。 |
| `public void setSpaceId(String spaceId)` | 空值保护，传入 `null` 时使用 `default`。 |

## 使用位置

- `QueryMvcController`
- `QueryWebFluxController`
- `ServeRequest.fromQueryRequest(QueryRequest)`
