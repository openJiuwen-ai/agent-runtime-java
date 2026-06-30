# adapters.common

`com.openjiuwen.service.adapters.common` 是 adapter leaf 共享模块，提供凭据解密、外部调用治理和 middleware 配置模型。

## 子包

| Package | Description |
| --- | --- |
| [`credential`](common/credential.README.md) | 凭据解密 SPI 和默认 passthrough 实现。 |
| [`external`](common/external.README.md) | 外部调用超时、重试、熔断、审计和异常模型。 |
| [`middleware`](common/middleware.README.md) | Middleware 配置模型。 |
| [`middleware.redis`](common/middleware/redis.README.md) | Redis 连接配置到 Jedis client 的装配。 |

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/`
