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
| `memory.enabled` | 是否启用长期记忆适配器，默认 `false`。 |
| `memory.provider` | 长期记忆服务类型，默认 `mem0`；内置 `mem0`、`jiuwen`，并支持通过 `MemoryStoreProvider` 扩展。 |
| `memory.endpoint` | 长期记忆服务地址，默认 `https://api.mem0.ai`。 |
| `memory.encrypted-api-key` | 加密 API Key，使用 `MEMORY_API_KEY` 场景交给 `CredentialDecryptor` 处理；启用 Memory 时解密结果不能为空。 |
| `memory.request-scoped-session` | 是否使用携带 `user_id`、`space_id`、`tenant_id` 的 Agent session，默认 `false`。 |
| `memory.rerank` | 搜索默认是否 rerank，默认 `false`；当前主要用于 mem0。 |
| `memory.auth-header-mode` | mem0 鉴权头模式：`token`、`bearer` 或 `x_api_key`，默认 `token`。 |
| `memory.path-style` | mem0 API 路径模式：Cloud 使用 `v3`，自建 Mem0 OSS 使用 `open`；默认 `v3`。 |
| `memory.timeout-ms` | Memory 外呼超时，默认 `3000`，必须大于 0。 |
| `memory.retry.max` | 最大重试次数，默认 `0`。 |
| `memory.retry.backoff-ms` | 重试退避毫秒数，默认 `0`。 |
| `memory.circuit-breaker.enabled` | 是否启用熔断，默认 `false`。 |
| `memory.circuit-breaker.failure-threshold` | 熔断失败阈值，默认 `5`。 |
| `memory.circuit-breaker.reset-timeout-ms` | 熔断重置等待时间，默认 `30000` 毫秒。 |
| `memory.audit.enabled` | 是否输出外部调用审计，默认 `true`。 |

## Memory Runtime 行为

`MemoryAdaptersAutoConfiguration` 在 `memory.enabled=true` 时：

1. 注册内置的 `Mem0MemoryStoreProvider` 与 `JiuwenMemoryStoreProvider`；
2. 由 `MemoryStoreFactory` 按 `memory.provider` 选择实现并创建 `MemoryStore`；
3. 将 API Key 交给 `CredentialDecryptor` 按 `MEMORY_API_KEY` 场景解密；
4. 当容器中没有其他 `MemoryProvider` bean 时，创建 `MemoryStoreMemoryProvider`，把 Core 的
   `prefetch`/`syncTurn` 映射到 `MemoryStore.search`/`add`。

mem0 实现支持 search、add、get、delete；Jiuwen Memory Engine 支持 search、add，并通过分页检索实现
get，但不支持按 memory id 删除。所有 provider 外呼统一通过 `ExternalCallExecutor` 应用超时、重试、熔断和审计。

## 使用者

- `MiddlewareAdaptersAutoConfiguration`
- `MemoryAdaptersAutoConfiguration`
- `MemoryStoreFactory`
- `MemoryStoreMemoryProvider`
- `AgentCoreCheckpointerConfigAssembler`
- `RedisConnectionAssembler`
- `RedisMiddlewareAutoConfiguration`
