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
| `memory.enabled` | 是否启用长期记忆适配器。 |
| `memory.provider` | 长期记忆服务类型，当前支持 `mem0`。 |
| `memory.endpoint` | 长期记忆服务地址。 |
| `memory.encrypted-api-key` | 加密 API Key，使用 `MEMORY_API_KEY` 场景交给 `CredentialDecryptor` 处理。 |
| `memory.user-id` | 未提供请求级用户时使用的默认用户标识。 |
| `memory.request-scoped-session` | 是否使用携带请求用户信息的 Agent session。 |

## 使用者

- `MiddlewareAdaptersAutoConfiguration`
- `AgentCoreCheckpointerConfigAssembler`
- `RedisConnectionAssembler`
- `RedisMiddlewareAutoConfiguration`
