# com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties

## class MiddlewareProperties

```java
@ConfigurationProperties(prefix = "openjiuwen.service.middleware")
public class MiddlewareProperties
```

Agent Service middleware 配置绑定对象。

## 主要配置

| Property | Description |
| --- | --- |
| `checkpointer.type` | `in_memory` 或 `redis`。 |
| `checkpointer.redis-ref` | 指向 `redis.<name>` 的 Redis endpoint。 |
| `redis.<name>.host` | Redis host。 |
| `redis.<name>.port` | Redis port。 |
| `redis.<name>.database` | Redis database。 |
| `redis.<name>.timeout-ms` | Redis 连接超时。 |
| `redis.<name>.encrypted-password` | 加密密码，交给 `CredentialDecryptor` 处理。 |

## 使用者

- `MiddlewareAdaptersAutoConfiguration`
- `AgentCoreCheckpointerConfigAssembler`
- `RedisConnectionAssembler`
