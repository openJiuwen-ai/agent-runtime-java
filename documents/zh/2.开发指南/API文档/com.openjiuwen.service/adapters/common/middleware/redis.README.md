# middleware.redis

`com.openjiuwen.service.adapters.common.middleware.redis` 提供 Redis 配置装配工具。

## 类型

| Type | Description |
| --- | --- |
| `RedisConnectionAssembler` | 从 `MiddlewareProperties.RedisEndpoint` 和 `CredentialDecryptor` 装配 Redis 连接参数。 |
| `RedisJedisClientFactory` | 创建 Jedis client。 |

## 使用者

- `RedisTaskStore`
- Agent Core Redis checkpointer 配置

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/`
