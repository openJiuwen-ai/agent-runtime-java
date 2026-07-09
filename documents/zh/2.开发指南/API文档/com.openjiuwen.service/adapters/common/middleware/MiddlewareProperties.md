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
| `redis.<name>.type` | Redis endpoint 类型，支持 `standalone` 和 `cluster`；未配置时默认 `standalone`，兼容既有单机配置。 |
| `redis.<name>.host` | 单机 Redis host；仅 `standalone` 使用，未配置时默认 `localhost`。 |
| `redis.<name>.port` | 单机 Redis port；仅 `standalone` 使用，默认 `6379`。 |
| `redis.<name>.nodes` | Redis Cluster seed node 列表；仅 `cluster` 使用，至少配置一个 `host:port`。 |
| `redis.<name>.database` | Redis database，默认 `0`；仅 `standalone` 生效，`cluster` 下忽略且不作为启动失败条件。 |
| `redis.<name>.timeout-ms` | Redis 连接超时。 |
| `redis.<name>.encrypted-password` | 加密密码，交给 `CredentialDecryptor` 处理。 |

## 使用者

- `MiddlewareAdaptersAutoConfiguration`
- `AgentCoreCheckpointerConfigAssembler`
- `RedisConnectionAssembler`
- `RedisMiddlewareAutoConfiguration`
