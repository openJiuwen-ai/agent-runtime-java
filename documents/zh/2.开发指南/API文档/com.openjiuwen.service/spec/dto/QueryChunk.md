# com.openjiuwen.service.spec.dto.QueryChunk

## class QueryChunk

```java
public class QueryChunk
```

流式 Query 输出的单个 chunk envelope。

## 常量

| Constant | Value | Description |
| --- | --- | --- |
| `TYPE_INTERRUPT` | `interrupt` | 需要用户输入或中断处理的信号。 |
| `TYPE_ANSWER` | `answer` | 最终回答。 |
| `TYPE_CHUNK` | `chunk` | 中间流式片段。 |
| `TYPE_ERROR` | `error` | 错误片段。 |

## 字段

| Field | Default | Description |
| --- | --- | --- |
| `type` | `TYPE_CHUNK` | chunk 类型。 |
| `data` | `null` | chunk 负载。 |

## 构造方法

| Signature | Description |
| --- | --- |
| `public QueryChunk()` | 默认构造。 |
| `public QueryChunk(String type, Object data)` | 指定类型和负载。 |
