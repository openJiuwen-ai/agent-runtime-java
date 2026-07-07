# com.openjiuwen.service.spec.dto.ServeRequest

## class ServeRequest

```java
public class ServeRequest
```

协议无关的内部编排请求。HTTP Controller 和 A2A Protocol Adapter 都会把入站协议转换为 `ServeRequest` 后交给 `ServeOrchestrator`。

## 字段

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `conversationId` | `String` | `null` | 会话 ID。 |
| `messages` | `List<Map<String, Object>>` | `[]` | 归一化后的消息列表。 |
| `userId` | `String` | `null` | 用户 ID。 |
| `spaceId` | `String` | `null` | 空间 ID。 |
| `tenantId` | `String` | `null` | 租户 ID。 |
| `stream` | `boolean` | `true` | 是否流式输出。 |
| `metadata` | `Map<String, Object>` | `{}` | 协议元数据透传。 |

## 方法

| Signature | Description |
| --- | --- |
| `public static ServeRequest fromQueryRequest(QueryRequest request)` | 从 HTTP Query DTO 构建编排请求。 |
| `public String lastUserQuery()` | 返回最后一条 user 消息内容；没有 user 消息时回退到最后一条消息 content。 |
| `public void setMessages(List<Map<String, Object>> messages)` | 空值保护，传入 `null` 时使用空列表。 |

## 使用位置

- `ServeOrchestrator.query`
- `ServeOrchestrator.streamQuery`
- `AgentHandler.query`
- `AgentHandler.streamQuery`
