# middleware

`com.openjiuwen.service.adapters.common.middleware` 定义 middleware 配置模型。

## 类型

| Type | Description |
| --- | --- |
| [`MiddlewareProperties`](middleware/MiddlewareProperties.md) | `openjiuwen.service.middleware` 配置绑定对象。 |

## 配置范围

| Section | Description |
| --- | --- |
| `checkpointer` | Core Runner checkpointer 类型和 Redis 引用。 |
| `session-store` | Session store 预留配置。 |
| `object-storage` | Object storage 预留配置。 |
| `vector-store` | Vector store 预留配置。 |
| `redis` | 多 Redis endpoint 配置。 |

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/`
