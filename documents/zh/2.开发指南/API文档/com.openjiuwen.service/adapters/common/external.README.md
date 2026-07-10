# external

`com.openjiuwen.service.adapters.common.external` 提供外部服务出站调用治理能力。

## 类型

| Type | Description |
| --- | --- |
| [`ExternalCallExecutor`](external/ExternalCallExecutor.md) | 执行带超时、重试、熔断、审计的外部调用。 |
| `ExternalCallPolicy` | 治理策略接口：timeout、retry、circuit breaker、audit。 |
| `ExternalRetryPolicy` | `max`、`backoffMs`。 |
| `ExternalCircuitBreakerPolicy` | `enabled`、`failureThreshold`、`resetTimeoutMs`。 |
| `ExternalAuditPolicy` | `enabled`，默认开启。 |
| `ExternalSvcAdapterException` | 外部服务适配异常。 |
| `ExternalSvcAdapterErrorCode` | 错误码枚举。 |

## 默认策略

| Policy | Default |
| --- | --- |
| `retry.max` | `0` |
| `retry.backoff-ms` | `0` |
| `circuit-breaker.enabled` | `false` |
| `circuit-breaker.failure-threshold` | `5` |
| `circuit-breaker.reset-timeout-ms` | `30000` |
| `audit.enabled` | `true` |

## 使用者

- `DecoratingMcpClient`
- `DecoratingRemoteClient`
- `DecoratingSandboxClient`

## 源码路径

`service/agent-service-adapters/agent-service-adapters-common/src/main/java/com/openjiuwen/service/adapters/common/external/`
