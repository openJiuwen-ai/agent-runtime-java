# middleware.redis

`com.openjiuwen.service.adapters.common.middleware.redis` 提供 Redis 配置装配工具。

## 类型

| Type | Description |
| --- | --- |
| `RedisConnectionAssembler` | 从 `MiddlewareProperties.RedisEndpoint` 装配 Redis 连接参数，解析 endpoint type，并生成脱敏摘要。 |
| `RedisJedisClientFactory` | 创建单机 `JedisPooled` 或 `JedisCluster` client。 |
| `RedisMiddlewareAutoConfiguration` | 启用 Redis checkpointer 且未提供自定义 `RuntimeRedisClient` Bean 时，根据 endpoint type 创建默认 Redis client。 |
| `JedisPooledRuntimeRedisClient` | 默认单机 Redis runtime client。 |
| `JedisClusterRuntimeRedisClient` | 默认 Redis Cluster runtime client。 |
| `RedisDatasourceDiagnostics` | 启动时输出当前 Redis client、endpoint type 和非敏感连接摘要。 |

## 配置要点

| Property | Description |
| --- | --- |
| `redis.<name>.type` | 支持 `standalone` 和 `cluster`；未配置时默认 `standalone`。 |
| `redis.<name>.host` / `redis.<name>.port` | 单机 Redis 地址；仅 `standalone` 使用。 |
| `redis.<name>.nodes` | Redis Cluster seed node 列表；仅 `cluster` 使用，格式为 `host:port`。 |
| `redis.<name>.database` | 仅 `standalone` 生效；`cluster` 模式忽略，非 0 时输出 ignored 诊断但不启动失败。 |
| `redis.<name>.timeout-ms` | 单机和集群共用的连接/读写超时。 |
| `redis.<name>.encrypted-password` | 加密密码，日志只输出是否配置，不输出明文或密文。 |

## 使用者

- `RedisTaskStore`
- Agent Core Redis checkpointer 配置

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/middleware/redis/`
